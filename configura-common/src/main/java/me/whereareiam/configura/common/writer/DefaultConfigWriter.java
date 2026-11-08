package me.whereareiam.configura.common.writer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import me.whereareiam.configura.common.MapperFactory;
import me.whereareiam.configura.common.document.DefaultDocumentProcessor;
import me.whereareiam.configura.common.util.FileUtil;
import me.whereareiam.configura.document.DocumentProcessor;
import me.whereareiam.configura.document.DocumentWrite;
import me.whereareiam.configura.document.DocumentWriteHook;
import me.whereareiam.configura.exception.ConfigException;
import me.whereareiam.configura.type.Format;
import me.whereareiam.configura.writer.ConfigWriter;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DefaultConfigWriter implements ConfigWriter {
	private final String extension;
	private final ObjectMapper mapper;
	private final List<DocumentWriteHook> hooks;
	private final DocumentProcessor documents;

	public DefaultConfigWriter() {
		this(Format.YAML);
	}

	public DefaultConfigWriter(Format format) {
		this(format.getExtension(), MapperFactory.buildWriterMapper(format));
	}

	public DefaultConfigWriter(String extension, ObjectMapper mapper) {
		this(extension, mapper, List.of(), new DefaultDocumentProcessor(List.of(), List.of()));
	}

	/**
	 * Creates a writer that writes documents through the given hooks.
	 *
	 * @param extension file extension added to paths without one
	 * @param mapper    mapper documents are serialized with
	 * @param hooks     hooks of the registered features, in registration order
	 * @param documents resolves the types of a document's sections for the hooks
	 */
	public DefaultConfigWriter(
			String extension,
			ObjectMapper mapper,
			List<DocumentWriteHook> hooks,
			DocumentProcessor documents
	) {
		this.extension = extension;
		this.mapper = mapper;
		this.hooks = List.copyOf(hooks);
		this.documents = documents;
	}

	@Override
	public <T> void write(Path path, T config) {
		Path target = resolve(path);
		try {
			ensureParent(target);
			FileUtil.writeAtomically(target, serialize(config));
		} catch (IOException e) {
			throw new ConfigException("Failed to write config file: " + target, e);
		}
	}

	@Override
	public <T> byte[] writeBytes(T config) {
		try {
			return serialize(config);
		} catch (IOException e) {
			throw new ConfigException("Failed to serialize config to bytes", e);
		}
	}

	@Override
	public void writeNode(Path path, JsonNode node) {
		writeNode(path, node, null);
	}

	/**
	 * Writes a configuration tree that belongs to a model, so that write hooks can see the model.
	 *
	 * @param path      full path to the config file
	 * @param node      the configuration tree to write
	 * @param modelType the model of the tree, or {@code null}
	 */
	public void writeNode(Path path, JsonNode node, @Nullable Class<?> modelType) {
		Path target = resolve(path);
		try {
			ensureParent(target);
			FileUtil.writeAtomically(target, serialize(tree(node), modelType));
		} catch (IOException e) {
			throw new ConfigException("Failed to write config tree: " + target, e);
		}
	}

	@Override
	public byte[] writeNodeBytes(JsonNode node) {
		return writeNodeBytes(node, null);
	}

	/**
	 * Serializes a configuration tree that belongs to a model, so that write hooks can see the model.
	 *
	 * @param node      the configuration tree to serialize
	 * @param modelType the model of the tree, or {@code null}
	 */
	public byte[] writeNodeBytes(JsonNode node, @Nullable Class<?> modelType) {
		try {
			return serialize(tree(node), modelType);
		} catch (IOException e) {
			throw new ConfigException("Failed to write config tree bytes", e);
		}
	}

	private byte[] serialize(Object config) throws IOException {
		if (hooks.isEmpty() || config == null) return mapper.writeValueAsBytes(config);

		return serialize(mapper.valueToTree(config), config.getClass());
	}

	private byte[] serialize(JsonNode document, @Nullable Class<?> modelType) throws IOException {
		if (hooks.isEmpty()) return mapper.writeValueAsBytes(document);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ObjectWriter writer = mapper.writer();
		DocumentWrite write = new DocumentWrite(document, modelType, mapper, bytes, documents);
		try (JsonGenerator generator = open(write, writer, 0)) {
			writer.writeValue(generator, document);
		}

		return bytes.toByteArray();
	}

	private JsonGenerator open(DocumentWrite write, ObjectWriter writer, int hook) throws IOException {
		if (hook == hooks.size()) return writer.createGenerator(write.getOutput());

		return hooks.get(hook).open(write, () -> open(write, writer, hook + 1));
	}

	private JsonNode tree(JsonNode node) {
		return node != null ? node : mapper.createObjectNode();
	}

	private Path resolve(Path path) {
		return FileUtil.resolvePathWithExtension(path, extension);
	}

	private void ensureParent(Path path) throws IOException {
		Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
	}
}
