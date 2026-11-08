package me.whereareiam.configura.common.merge.track;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.exception.ConfigException;
import org.jetbrains.annotations.NotNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The defaults written into one document, as fingerprints by the value's path, so that an update can
 * tell a value nobody touched from one a user edited.
 * <p>
 * An instance holds the defaults of the previous write, which the merge asks about, and collects
 * the defaults of the current one, which are stored once the document is written. Paths are JSON
 * Pointers; the entries of a keyed list are addressed by their key.
 */
public final class TrackedDefaults {
	private static final ObjectMapper CANONICAL = JsonMapper.builder().build();

	private final Map<String, String> previous;
	private final Map<String, String> written;

	private TrackedDefaults(Map<String, String> previous, Map<String, String> written) {
		this.previous = previous;
		this.written = written;
	}

	/**
	 * Starts tracking a document whose previous defaults are unknown, so that every value it has
	 * counts as the user's.
	 *
	 * @return tracking without history
	 */
	public static @NotNull TrackedDefaults unknown() {
		return new TrackedDefaults(Map.of(), new LinkedHashMap<>());
	}

	/**
	 * Starts tracking a document from the fingerprints of its previous write.
	 *
	 * @param previous fingerprints by path, as {@link #written()} returned them
	 * @return tracking with history
	 */
	public static @NotNull TrackedDefaults of(@NotNull Map<String, String> previous) {
		return new TrackedDefaults(Map.copyOf(previous), new LinkedHashMap<>());
	}

	/**
	 * Returns a view that records into this one but knows no previous write, so that nothing it is
	 * asked about counts as untouched. Used for a part of the document whose values must stay as
	 * they are in this write, while their defaults are still recorded for the next.
	 *
	 * @return recording view
	 */
	public @NotNull TrackedDefaults recordingOnly() {
		return new TrackedDefaults(Map.of(), written);
	}

	/**
	 * Tells whether values can follow new defaults through this instance.
	 *
	 * @return false for a {@link #recordingOnly()} view or without history
	 */
	public boolean follows() {
		return !previous.isEmpty();
	}

	/**
	 * Tells whether a value is the default the previous write put at a path.
	 *
	 * @param path  JSON Pointer of the value
	 * @param value value the document has there
	 * @return true when nobody changed it since
	 */
	public boolean untouched(@NotNull String path, @NotNull JsonNode value) {
		String fingerprint = previous.get(path);

		return fingerprint != null && fingerprint.equals(fingerprint(value));
	}

	/**
	 * Remembers the default of a path for the current write.
	 *
	 * @param path         JSON Pointer of the value
	 * @param defaultValue default the model has there
	 */
	public void record(@NotNull String path, @NotNull JsonNode defaultValue) {
		written.put(path, fingerprint(defaultValue));
	}

	/**
	 * Returns the fingerprints recorded for the current write.
	 *
	 * @return fingerprints by path, in document order
	 */
	public @NotNull Map<String, String> written() {
		return Collections.unmodifiableMap(written);
	}

	/**
	 * Extends a path by one key, escaped as JSON Pointer requires.
	 *
	 * @param path parent path, empty for the root
	 * @param key  object key, map key or key of a list entry
	 * @return child path
	 */
	public static @NotNull String child(@NotNull String path, @NotNull String key) {
		return path + "/" + key.replace("~", "~0").replace("/", "~1");
	}

	/** Hashes the value with its object keys sorted, so that formats and key order do not matter. */
	private static String fingerprint(JsonNode value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(canonical(value)));

			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException | JsonProcessingException failure) {
			throw new ConfigException("Failed to fingerprint a default value", failure);
		}
	}

	private static JsonNode canonical(JsonNode value) {
		if (value.isObject()) {
			Map<String, JsonNode> sorted = new TreeMap<>();
			value.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));

			ObjectNode object = JsonNodeFactory.instance.objectNode();
			sorted.forEach(object::set);
			return object;
		}

		if (value.isArray()) {
			ArrayNode array = JsonNodeFactory.instance.arrayNode();
			value.forEach(element -> array.add(canonical(element)));
			return array;
		}

		return value;
	}
}
