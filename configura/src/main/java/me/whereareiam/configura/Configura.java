package me.whereareiam.configura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.common.ConfiguraFeatureRegistry;
import me.whereareiam.configura.common.MapperFactory;
import me.whereareiam.configura.type.Format;
import me.whereareiam.configura.common.document.DefaultDocumentProcessor;
import me.whereareiam.configura.common.merge.MergeEngine;
import me.whereareiam.configura.common.merge.defaults.DefaultsProviderRegistry;
import me.whereareiam.configura.common.merge.track.TrackedDefaults;
import me.whereareiam.configura.common.merge.track.TrackedDefaultsFile;
import me.whereareiam.configura.common.reader.DefaultConfigReader;
import me.whereareiam.configura.common.util.FileUtil;
import me.whereareiam.configura.common.writer.DefaultConfigWriter;
import me.whereareiam.configura.document.DocumentProcessor;
import me.whereareiam.configura.document.DocumentTypeContext;
import me.whereareiam.configura.exception.ConfigException;
import me.whereareiam.configura.merge.MergeBehavior;
import me.whereareiam.configura.merge.defaults.DefaultsProvider;
import me.whereareiam.configura.reader.ConfigReader;
import me.whereareiam.configura.writer.ConfigWriter;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@SuppressWarnings("unused")
public final class Configura {
	private final String extension;

	private final Function<List<Module>, ObjectMapper> mapperFactory;
	private final List<Module> modules;
	private final List<ConfiguraFeature> features;

	private final DefaultsProviderRegistry defaultProviderRegistry;

	private final MergeBehavior mergeBehavior;
	private final boolean tracksDefaults;

	private final Set<String> reservedKeys;
	private final ObjectMapper mapper;
	private final DocumentProcessor documentProcessor;
	private final MergeEngine mergeEngine;
	private final ConfigReader reader;
	private final DefaultConfigWriter writer;

	Configura(
			String extension,

			Function<List<Module>, ObjectMapper> mapperFactory,
			List<Module> modules,
			List<ConfiguraFeature> features,

			DefaultsProviderRegistry defaultProviderRegistry,
			MergeBehavior mergeBehavior,
			boolean tracksDefaults
	) {
		this.extension = extension;

		this.mapperFactory = mapperFactory;
		this.modules = List.copyOf(modules);
		this.features = List.copyOf(features);

		this.defaultProviderRegistry = defaultProviderRegistry.copy();

		this.mergeBehavior = mergeBehavior != null ? mergeBehavior : MergeBehavior.defaults();
		this.tracksDefaults = tracksDefaults;

		ConfiguraFeatureRegistry featureRegistry = new ConfiguraFeatureRegistry();
		for (ConfiguraFeature feature : this.features)
			featureRegistry.add(feature);
		this.reservedKeys = featureRegistry.reservedKeys();
		ObjectMapper plainMapper = mapperFactory.apply(this.modules);
		List<Module> mapperModules = new ArrayList<>(this.modules);
		mapperModules.addAll(featureRegistry.modules(plainMapper));
		this.mapper = mapperFactory.apply(mapperModules);
		this.documentProcessor = new DefaultDocumentProcessor(featureRegistry.typeResolvers(), featureRegistry.phases());
		this.mergeEngine = new MergeEngine(this.mapper, this.defaultProviderRegistry, this.documentProcessor, this.mergeBehavior);

		this.reader = new DefaultConfigReader(this.extension, this.mapper, this.documentProcessor);
		this.writer = new DefaultConfigWriter(this.extension, this.mapper, featureRegistry.writeHooks(), this.documentProcessor);
	}

	/**
	 * Starts a builder with the built-in merge rules and the YAML format.
	 *
	 * @return new builder
	 */
	public static @NotNull Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the Jackson mapper files are read and written with, including the modules of the
	 * registered features.
	 *
	 * @return configured mapper
	 */
	public ObjectMapper mapper() {
		return mapper;
	}







	ConfigReader reader() {
		return reader;
	}

	ConfigWriter writer() {
		return writer;
	}

	public <T> T read(String file, Class<T> type) {
		return read(resolve(file), type);
	}

	public <T> T read(Path path, Class<T> type) {
		if (path == null) throw new ConfigException("path must not be null");
		if (type == null) throw new ConfigException("configClass must not be null");

		JsonNode resolved = readTree(resolve(path));
		return bind(resolved, type, "Failed to bind config to " + type.getName());
	}

	public <T> T read(byte[] bytes, Class<T> type) {
		if (type == null) throw new ConfigException("configClass must not be null");

		JsonNode resolved = readTree(bytes);
		return bind(resolved, type, "Failed to read config bytes for " + type.getName());
	}

	public <T> T read(InputStream inputStream, Class<T> type) {
		if (type == null) throw new ConfigException("configClass must not be null");

		JsonNode resolved = readTree(inputStream);
		return bind(resolved, type, "Failed to read config stream for " + type.getName());
	}

	public <T> void write(String file, T value) {
		if (file == null) throw new NullPointerException("file");
		write(Path.of(file), value);
	}

	public <T> void write(Path path, T value) {
		if (value == null) {
			writer.write(path, null);
			return;
		}

		writer.writeNode(path, carryReservedKeys(existingResolvedTree(path, value.getClass()), mapper.valueToTree(value)), value.getClass());
	}

	public <T> void save(String file, T value) {
		if (file == null) throw new NullPointerException("file");
		save(Path.of(file), value);
	}

	@SuppressWarnings("unchecked")
	public <T> void save(Path path, T value) {
		if (value == null) throw new NullPointerException("value");

		Class<T> type = (Class<T>) value.getClass();
		JsonNode existing = existingResolvedTree(path, type);

		ObjectNode source = mapper.valueToTree(value);
		ObjectNode merged = mergeUserModel(source, value, type);
		writer.writeNode(path, carryReservedKeys(existing, merged), type);
	}

	public <T> byte[] writeBytes(T value) {
		if (value == null)
			return writer.writeBytes(null);

		return writer.writeNodeBytes(mapper.valueToTree(value), value.getClass());
	}

	public <T> T merge(String file, T value) {
		return merge(resolve(file), value);
	}

	@SuppressWarnings("unchecked")
	public <T> T merge(Path path, T value) {
		if (value == null) throw new ConfigException("config must not be null");

		Class<T> type = (Class<T>) value.getClass();
		JsonNode existing = existingResolvedTree(path, type);
		ObjectNode merged = mergeEngine.mergeDefaults(existing, value, type, null);
		return bind(merged, type, "Failed to bind merged config to " + type.getName());
	}

	public <T> T update(String file, Class<T> type) {
		return update(Path.of(file), type);
	}

	public <T> T update(Path path, Class<T> type) {
		T empty = instantiate(type);
		Path target = resolve(path);
		JsonNode existing = existingResolvedTree(target, type);
		TrackedDefaults tracked = tracksDefaults ? TrackedDefaultsFile.read(target) : null;

		ObjectNode merged = mergeEngine.mergeDefaults(existing, empty, type, tracked);
		T value = bind(merged, type, "Failed to bind updated config to " + type.getName());
		writer.writeNode(target, carryReservedKeys(existing, merged), type);
		if (tracked != null) TrackedDefaultsFile.write(target, tracked);

		return value;
	}

	public JsonNode readNode(String file) {
		if (file == null) throw new NullPointerException("file");
		return reader.readNode(Path.of(file));
	}

	public JsonNode readNode(Path path) {
		return reader.readNode(path);
	}

	public JsonNode readNode(byte[] bytes) {
		return reader.readNode(bytes);
	}

	public JsonNode readNode(InputStream inputStream) {
		return reader.readNode(inputStream);
	}

	public void writeNode(String file, JsonNode node) {
		if (file == null) throw new NullPointerException("file");
		writer.writeNode(Path.of(file), node);
	}

	public void writeNode(Path path, JsonNode node) {
		writer.writeNode(path, node);
	}

	public byte[] writeNodeBytes(JsonNode node) {
		return writer.writeNodeBytes(node);
	}

	/**
	 * Returns a copy of this instance that also takes defaults from a provider.
	 *
	 * @param providerClass provider of the defaults of one model type
	 * @param <T>           model type
	 * @param <P>           provider type
	 * @return independent instance
	 */
	public <T, P extends DefaultsProvider<T>> Configura withDefaults(Class<P> providerClass) {
		return toBuilder().defaults(providerClass).build();
	}

	/**
	 * Returns a copy of this instance with one more feature.
	 *
	 * @param feature feature to add
	 * @return independent instance
	 */
	public Configura withFeature(ConfiguraFeature feature) {
		return toBuilder().feature(feature).build();
	}

	/**
	 * Starts a builder holding everything this instance was built with, to derive a variant of it.
	 *
	 * @return builder that leaves this instance untouched
	 */
	public @NotNull Builder toBuilder() {
		return new Builder(this);
	}

	public String extension() {
		return extension;
	}

	public List<Module> modules() {
		return modules;
	}

	DefaultsProviderRegistry registeredDefaultProviders() {
		return defaultProviderRegistry.copy();
	}

	List<ConfiguraFeature> features() {
		return List.copyOf(features);
	}



	public MergeBehavior mergeBehavior() {
		return mergeBehavior;
	}

	/**
	 * Tells whether {@code update} remembers the defaults it writes, so that values nobody touched
	 * follow later defaults.
	 *
	 * @return true when built with {@link Builder#trackDefaults()}
	 */
	public boolean tracksDefaults() {
		return tracksDefaults;
	}

	/**
	 * Returns the top-level document keys owned by the registered features.
	 *
	 * @return reserved keys, which survive every write of a model over an existing file
	 */
	public @NotNull Set<String> reservedKeys() {
		return reservedKeys;
	}


	private Path resolve(String file) {
		return FileUtil.resolvePathWithExtension(file, extension);
	}

	private Path resolve(Path path) {
		return FileUtil.resolvePathWithExtension(path, extension);
	}

	private JsonNode readTree(Path path) {
		if (path == null) throw new ConfigException("path must not be null");

		Path target = resolve(path);
		if (!Files.exists(target))
			throw new ConfigException("Config file does not exist: " + target);

		try (BufferedReader reader = Files.newBufferedReader(target)) {
			JsonNode node = mapper.readTree(reader);
			if (node == null) throw new ConfigException("Config file is empty or invalid: " + target);
			return node;
		} catch (IOException e) {
			throw new ConfigException("Failed to read config tree: " + target, e);
		}
	}

	private JsonNode readTree(byte[] bytes) {
		if (bytes == null || bytes.length == 0)
			return mapper.createObjectNode();

		try {
			JsonNode node = mapper.readTree(bytes);
			return node != null ? node : mapper.createObjectNode();
		} catch (IOException e) {
			throw new ConfigException("Failed to read config tree from bytes", e);
		}
	}

	private JsonNode readTree(InputStream inputStream) {
		if (inputStream == null)
			return mapper.createObjectNode();

		try {
			JsonNode node = mapper.readTree(inputStream);
			return node != null ? node : mapper.createObjectNode();
		} catch (IOException e) {
			throw new ConfigException("Failed to read config tree from stream", e);
		}
	}




	private <T> JsonNode existingResolvedTree(Path path, Class<T> type) {
		Path target = resolve(path);
		if (!Files.exists(target))
			return mapper.createObjectNode();

		return readTree(target);
	}

	private <T> T instantiate(Class<T> type) {
		try {
			Class<?> effectiveType = documentProcessor.resolveType(type, null);
			return (T) mapper.treeToValue(mapper.createObjectNode(), effectiveType);
		} catch (Exception e) {
			throw new ConfigException("Failed to instantiate config type " + type.getName(), e);
		}
	}

	private <T> T bind(JsonNode node, Class<T> type, String failureMessage) {
		try {
			Class<?> effectiveType = documentProcessor.resolveType(
					type,
					new DocumentTypeContext(node != null ? node : mapper.createObjectNode(), null, null, null, null, null)
			);
			T value = (T) mapper.treeToValue(node != null ? node : mapper.createObjectNode(), effectiveType);
			documentProcessor.afterBind(value);
			return value;
		} catch (Exception e) {
			throw new ConfigException(failureMessage, e);
		}
	}





	/**
	 * Puts the values of reserved keys found in the file being replaced at the top of its new content.
	 */
	private ObjectNode carryReservedKeys(JsonNode existing, ObjectNode document) {
		ObjectNode carried = mapper.createObjectNode();
		for (String key : reservedKeys)
			if (existing.has(key)) carried.set(key, existing.get(key));

		if (carried.isEmpty()) return document;

		document.remove(reservedKeys);
		carried.setAll(document);
		return carried;
	}

	private <T> ObjectNode mergeUserModel(JsonNode source, T model, Class<T> type) {
		return mergeEngine.mergeUserModel(source, model, type);
	}

	/**
	 * Collects what a {@link Configura} is built from.
	 */
	public static final class Builder {
		private final List<Module> modules = new ArrayList<>();
		private final List<ConfiguraFeature> features = new ArrayList<>();

		private final DefaultsProviderRegistry defaultProviderRegistry;

		private String extension;
		private Function<List<Module>, ObjectMapper> mapperFactory;
		private MergeBehavior mergeBehavior;
		private boolean tracksDefaults;

		private Builder() {
			this.extension = Format.YAML.getExtension();
			this.mapperFactory = MapperFactory::createYamlMapper;
			this.defaultProviderRegistry = new DefaultsProviderRegistry();
			this.mergeBehavior = MergeBehavior.defaults();
		}

		private Builder(Configura source) {
			this.extension = source.extension;
			this.mapperFactory = source.mapperFactory;
			this.modules.addAll(source.modules);
			this.features.addAll(source.features);
			this.defaultProviderRegistry = source.defaultProviderRegistry.copy();
			this.mergeBehavior = source.mergeBehavior;
			this.tracksDefaults = source.tracksDefaults;
		}

		/**
		 * Reads and writes files in a built-in format.
		 *
		 * @param format YAML or JSON
		 * @return this builder
		 */
		public Builder format(Format format) {
			this.extension = format.getExtension();
			this.mapperFactory = format == Format.JSON
					? MapperFactory::createJsonMapper
					: MapperFactory::createYamlMapper;
			return this;
		}

		/**
		 * Reads and writes files with a mapper of your own, for a format Configura does not ship.
		 *
		 * @param extension     file extension of the format, with or without the leading dot
		 * @param mapperFactory creates the mapper from the modules to register on it
		 * @return this builder
		 */
		public Builder format(String extension, Function<List<Module>, ObjectMapper> mapperFactory) {
			this.extension = extension;
			this.mapperFactory = mapperFactory;
			return this;
		}

		/**
		 * Registers a Jackson module on the mapper.
		 *
		 * @param module module, ignored when null
		 * @return this builder
		 */
		public Builder module(Module module) {
			if (module != null) this.modules.add(module);
			return this;
		}

		/**
		 * Registers Jackson modules on the mapper.
		 *
		 * @param modules modules; null entries are ignored
		 * @return this builder
		 */
		public Builder modules(Module... modules) {
			if (modules == null) return this;

			for (Module module : modules)
				module(module);
			return this;
		}

		/**
		 * Takes the defaults of a model type from a provider instead of its field initializers.
		 *
		 * @param providerClass provider with an accessible no-argument constructor
		 * @param <T>           model type
		 * @param <P>           provider type
		 * @return this builder
		 */
		public <T, P extends DefaultsProvider<T>> Builder defaults(Class<P> providerClass) {
			this.defaultProviderRegistry.registerProvider(providerClass);
			return this;
		}

		/**
		 * Adds a feature.
		 *
		 * @param feature feature, ignored when null
		 * @return this builder
		 */
		public Builder feature(ConfiguraFeature feature) {
			if (feature != null) this.features.add(feature);
			return this;
		}

		/**
		 * Sets how unknown keys and primitive defaults are treated.
		 *
		 * @param mergeBehavior merge behavior
		 * @return this builder
		 */
		public Builder mergeBehavior(MergeBehavior mergeBehavior) {
			this.mergeBehavior = mergeBehavior;
			return this;
		}

		/**
		 * Lets {@code update} tell defaults it wrote from values a user edited. Each update records
		 * the defaults of the document in {@code .configura/<file name>.defaults.json} beside it. On
		 * the next update, a value that is still the default recorded for it takes the current
		 * default; a value that differs is the user's and is kept. Missing values are added as
		 * always. Fields with {@code @Merge(present = WhenPresent.KEEP_AS_WRITTEN)} never change by
		 * themselves.
		 * <p>
		 * Without a readable record, as on the first tracked update of an existing file, every value
		 * the file has is kept.
		 *
		 * <pre>{@code
		 * Configura configura = Configura.builder()
		 *         .defaults(MessagesDefaults.class)
		 *         .trackDefaults()
		 *         .build();
		 * }</pre>
		 *
		 * @return this builder
		 */
		public Builder trackDefaults() {
			this.tracksDefaults = true;
			return this;
		}

		/**
		 * Creates the instance.
		 *
		 * @return immutable Configura
		 */
		public Configura build() {
			return new Configura(
					extension,
					mapperFactory,
					modules,
					features,
					defaultProviderRegistry,
					mergeBehavior,
					tracksDefaults
			);
		}
	}
}
