package me.whereareiam.configura.common.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import me.whereareiam.configura.annotation.PreserveUnknownFields;
import me.whereareiam.configura.common.merge.defaults.ModelDefaults;
import me.whereareiam.configura.common.merge.track.TrackedDefaults;
import me.whereareiam.configura.common.util.SerializedFieldResolver;
import me.whereareiam.configura.document.DocumentProcessor;
import me.whereareiam.configura.document.DocumentTypeContext;
import me.whereareiam.configura.exception.ConfigException;
import me.whereareiam.configura.type.PrimitiveDefaultPolicy;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Merges what a user wrote with the defaults of the model, key by key, following each field's
 * {@link FieldRule}. The user's values always win; the rules only decide what is added around them.
 * <p>
 * With {@link TrackedDefaults}, a value that is still the default the previous write put there is
 * not the user's: it is replaced by the current default. Every default the merge meets is recorded
 * for the next write. Fields kept as written are never tracked, and neither are the entries of a
 * map or keyed list that the defaults do not have.
 */
@RequiredArgsConstructor
final class TreeMerge {
	private final ObjectMapper mapper;
	private final DocumentProcessor documents;
	private final ModelDefaults modelDefaults;
	private final PrimitiveDefaultPolicy defaultsPolicy;
	private final @Nullable TrackedDefaults tracked;

	/**
	 * Merges two objects bound to a model type.
	 *
	 * @param source       what the file has, if anything
	 * @param defaults     what the model has by default, if anything
	 * @param ownerType    model type the keys belong to
	 * @param keepsUnknown whether keys the model does not know are kept
	 * @param path         JSON Pointer of the object for tracking, or null when it is not tracked
	 */
	ObjectNode object(
			@Nullable JsonNode source,
			@Nullable JsonNode defaults,
			Class<?> ownerType,
			boolean keepsUnknown,
			@Nullable String path
	) {
		ObjectNode result = mapper.createObjectNode();
		ObjectNode sourceObject = source instanceof ObjectNode object ? object : mapper.createObjectNode();
		ObjectNode defaultObject = defaults instanceof ObjectNode object ? object : mapper.createObjectNode();
		ObjectNode current = sourceObject.isEmpty() ? defaultObject : sourceObject;

		Set<String> keys = new LinkedHashSet<>();
		sourceObject.fieldNames().forEachRemaining(keys::add);
		defaultObject.fieldNames().forEachRemaining(keys::add);

		for (String key : keys) {
			JsonNode sourceValue = sourceObject.get(key);
			JsonNode defaultValue = defaultObject.get(key);
			Field field = SerializedFieldResolver.resolveField(ownerType, key);
			if (sourceValue != null && sourceValue.isNull()) {
				result.set(key, sourceValue);
				continue;
			}

			if (defaultValue == null) {
				if (field != null || keepsUnknown) result.set(key, sourceValue.deepCopy());
				continue;
			}

			DocumentTypeContext context = new DocumentTypeContext(
					sourceValue != null ? sourceValue : defaultValue,
					current,
					field,
					key,
					null,
					null
			);
			Class<?> valueType = documents.resolveType(SerializedFieldResolver.valueType(ownerType, field), context);
			boolean valueKeepsUnknown = keepsUnknown || keepsUnknown(field) || keepsUnknown(valueType);

			String valuePath = path == null ? null : TrackedDefaults.child(path, key);
			JsonNode merged = field(FieldRule.of(field), field, sourceValue, defaultValue, valueType, valueKeepsUnknown, valuePath);
			if (merged != null && !merged.isNull()) result.set(key, merged);
		}

		return result;
	}

	static boolean keepsUnknown(@Nullable Class<?> type) {
		return type != null && type.isAnnotationPresent(PreserveUnknownFields.class);
	}

	private static boolean keepsUnknown(@Nullable Field field) {
		return field != null && field.isAnnotationPresent(PreserveUnknownFields.class);
	}

	private @Nullable JsonNode field(
			FieldRule rule,
			@Nullable Field field,
			@Nullable JsonNode source,
			JsonNode defaults,
			Class<?> valueType,
			boolean keepsUnknown,
			@Nullable String path
	) {
		if (source == null && !rule.addsDefaultWhenAbsent()) return null;
		if (source != null && !rule.fillsMissing()) return source.deepCopy();

		if (rule.isMap()) return map(rule, field, source, defaults, valueType, keepsUnknown, path);
		if (rule.isKeyedList()) return keyedList(rule, field, source, defaults, valueType, keepsUnknown, path);
		if (source == null) return added(rule, field, defaults, valueType, keepsUnknown, path);
		if (source.isObject() && defaults.isObject()) return object(source, defaults, valueType, keepsUnknown, path);

		return value(source, defaults, path);
	}

	/**
	 * Adds a default the file lacks as it is. When tracked, the default is also merged with itself,
	 * only to record its values at the paths the next merge will look them up by.
	 */
	private JsonNode added(
			FieldRule rule,
			@Nullable Field field,
			JsonNode defaults,
			Class<?> valueType,
			boolean keepsUnknown,
			@Nullable String path
	) {
		if (tracks(path)) field(rule, field, defaults, defaults, valueType, keepsUnknown, path);

		return defaults.deepCopy();
	}

	/**
	 * Decides a value the merge does not descend into: the user's value, unless tracking shows it is
	 * the default the previous write put there, in which case the current default takes its place.
	 */
	private JsonNode value(JsonNode source, JsonNode defaults, @Nullable String path) {
		if (!tracks(path)) return source.deepCopy();

		boolean untouched = tracked.untouched(path, source);
		tracked.record(path, defaults);

		return untouched ? defaults.deepCopy() : source.deepCopy();
	}

	private boolean tracks(@Nullable String path) {
		return tracked != null && path != null;
	}

	private ObjectNode map(
			FieldRule rule,
			Field field,
			@Nullable JsonNode source,
			JsonNode defaults,
			Class<?> valueType,
			boolean keepsUnknown,
			@Nullable String path
	) {
		if (source != null && !source.isObject()) throw wrongNode(field, "a map", source);
		if (!defaults.isObject()) throw wrongNode(field, "a map", defaults);

		Map<String, JsonNode> sourceEntries = new LinkedHashMap<>();
		if (source != null) source.properties().forEach(entry -> sourceEntries.put(entry.getKey(), entry.getValue()));

		Map<String, JsonNode> defaultEntries = new LinkedHashMap<>();
		defaults.properties().forEach(entry -> defaultEntries.put(entry.getKey(), entry.getValue()));

		ObjectNode result = mapper.createObjectNode();
		entries(rule, field, sourceEntries, defaultEntries, source == null, valueType, keepsUnknown, false, path)
				.forEach(result::set);

		return result;
	}

	private ArrayNode keyedList(
			FieldRule rule,
			Field field,
			@Nullable JsonNode source,
			JsonNode defaults,
			Class<?> entryType,
			boolean keepsUnknown,
			@Nullable String path
	) {
		if (source != null && !source.isArray()) throw wrongNode(field, "a list", source);
		if (!defaults.isArray()) throw wrongNode(field, "a list", defaults);

		Map<String, JsonNode> sourceEntries = source == null ? new LinkedHashMap<>() : index(rule, field, source, "file");
		Map<String, JsonNode> defaultEntries = index(rule, field, defaults, "default");

		ArrayNode result = mapper.createArrayNode();
		entries(rule, field, sourceEntries, defaultEntries, source == null, entryType, keepsUnknown, true, path)
				.values()
				.forEach(result::add);

		return result;
	}

	/**
	 * Merges the entries of a map or keyed list: the user's entries first, each completed from the
	 * default entry of the same key and the defaults of the entry type, then the default entries the
	 * user does not have, if the rule adds them or the user has no collection at all.
	 */
	private Map<String, JsonNode> entries(
			FieldRule rule,
			Field field,
			Map<String, JsonNode> sourceEntries,
			Map<String, JsonNode> defaultEntries,
			boolean sourceAbsent,
			Class<?> entryType,
			boolean keepsUnknown,
			boolean list,
			@Nullable String path
	) {
		Map<String, JsonNode> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : sourceEntries.entrySet()) {
			String key = entry.getKey();
			JsonNode defaultEntry = defaultEntries.get(key);
			if (defaultEntry == null && rule.rejectsUnknownEntries())
				throw new ConfigException("Field " + describe(field) + " does not allow the entry '" + key + "'");

			String entryPath = path == null || defaultEntry == null ? null : TrackedDefaults.child(path, key);
			result.put(key, entry(field, key, entry.getValue(), defaultEntry, entryType, keepsUnknown, list, entryPath));
		}

		if (!rule.addsMissingEntries() && !sourceAbsent) return result;

		for (Map.Entry<String, JsonNode> entry : defaultEntries.entrySet()) {
			String key = entry.getKey();
			if (sourceEntries.containsKey(key)) continue;

			String entryPath = path == null ? null : TrackedDefaults.child(path, key);
			result.put(key, entry(field, key, null, entry.getValue(), entryType, keepsUnknown, list, entryPath));
		}

		return result;
	}

	private JsonNode entry(
			Field field,
			String key,
			@Nullable JsonNode source,
			@Nullable JsonNode keyedDefault,
			Class<?> declaredType,
			boolean keepsUnknown,
			boolean list,
			@Nullable String path
	) {
		DocumentTypeContext context = new DocumentTypeContext(
				source != null ? source : keyedDefault,
				null,
				field,
				field.getName(),
				list ? null : key,
				list ? key : null
		);
		Class<?> entryType = documents.resolveType(declaredType, context);
		JsonNode defaults = entryDefaults(modelDefaults.ofType(entryType, defaultsPolicy, context), keyedDefault, entryType, keepsUnknown);

		if (list && !(defaults instanceof ObjectNode))
			throw new ConfigException("Field " + describe(field) + " is merged by key, so its entries must be objects");

		if (defaults == null) return source == null ? mapper.nullNode() : source.deepCopy();
		if (defaults.isObject()) return object(source, defaults, entryType, keepsUnknown, path);
		return value(source == null || source.isNull() ? defaults : source, defaults, path);
	}

	/** Lays the default entry of a key over the defaults every entry of that type has. */
	private @Nullable JsonNode entryDefaults(
			@Nullable JsonNode typeDefaults,
			@Nullable JsonNode keyedDefault,
			Class<?> entryType,
			boolean keepsUnknown
	) {
		boolean keyed = keyedDefault != null && !keyedDefault.isNull();
		if (typeDefaults == null) return keyed ? keyedDefault.deepCopy() : null;
		if (!keyed) return typeDefaults.deepCopy();
		if (!typeDefaults.isObject() || !keyedDefault.isObject()) return keyedDefault.deepCopy();

		return object(keyedDefault, typeDefaults, entryType, keepsUnknown, null);
	}

	private Map<String, JsonNode> index(FieldRule rule, Field field, JsonNode list, String origin) {
		Map<String, JsonNode> indexed = new LinkedHashMap<>();
		for (int position = 0; position < list.size(); position++) {
			JsonNode entry = list.get(position);
			if (entry == null || !entry.isObject())
				throw new ConfigException("Field " + describe(field) + " is merged by key, but entry " + position + " of the " + origin + " list is not an object");

			JsonNode key = entry.get(rule.key());
			if (key == null || !key.isValueNode() || key.isNull() || key.asText().isBlank())
				throw new ConfigException("Field " + describe(field) + " has no '" + rule.key() + "' in entry " + position + " of the " + origin + " list");

			if (indexed.put(key.asText(), entry) != null)
				throw new ConfigException("Field " + describe(field) + " has the entry '" + key.asText() + "' twice in the " + origin + " list");
		}

		return indexed;
	}

	private static ConfigException wrongNode(Field field, String expected, JsonNode actual) {
		return new ConfigException("Field " + describe(field) + " expects " + expected + " but found " + actual.getNodeType());
	}

	private static String describe(Field field) {
		return field.getDeclaringClass().getName() + "#" + field.getName();
	}
}
