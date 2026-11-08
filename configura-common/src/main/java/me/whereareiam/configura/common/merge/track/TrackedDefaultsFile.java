package me.whereareiam.configura.common.merge.track;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.common.util.FileUtil;
import me.whereareiam.configura.exception.ConfigException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores the {@link TrackedDefaults} of a document in a hidden file beside it:
 * {@code .configura/<file name>.defaults.json}.
 */
public final class TrackedDefaultsFile {
	private static final ObjectMapper MAPPER = JsonMapper.builder().build();
	private static final String DIRECTORY = ".configura";
	private static final String SUFFIX = ".defaults.json";
	private static final int VERSION = 1;

	private TrackedDefaultsFile() {
	}

	/**
	 * Returns where the defaults written into a document are stored.
	 *
	 * @param document path of the document, with its extension
	 * @return path of the hidden file
	 */
	public static @NotNull Path of(@NotNull Path document) {
		Path absolute = document.toAbsolutePath();

		return absolute.resolveSibling(DIRECTORY).resolve(absolute.getFileName() + SUFFIX);
	}

	/**
	 * Reads what the previous write of a document stored. A missing or unreadable record yields
	 * tracking without history, under which every value of the document counts as the user's.
	 *
	 * @param document path of the document, with its extension
	 * @return tracking for the next write
	 * @throws ConfigException when the record exists but cannot be read from disk
	 */
	public static @NotNull TrackedDefaults read(@NotNull Path document) {
		Path file = of(document);
		if (!Files.isRegularFile(file)) return TrackedDefaults.unknown();

		JsonNode root;
		try {
			root = MAPPER.readTree(Files.readAllBytes(file));
		} catch (JacksonException corrupt) {
			return TrackedDefaults.unknown();
		} catch (IOException failure) {
			throw new ConfigException("Failed to read the tracked defaults of " + document + ": " + file, failure);
		}

		return parse(root);
	}

	/**
	 * Stores the defaults recorded for the current write of a document, replacing the file in one
	 * step.
	 *
	 * @param document path of the document, with its extension
	 * @param defaults tracking that went through the merge of the document
	 * @throws ConfigException when the record cannot be written
	 */
	public static void write(@NotNull Path document, @NotNull TrackedDefaults defaults) {
		Path file = of(document);
		ObjectNode root = MAPPER.createObjectNode();
		root.put("version", VERSION);
		ObjectNode values = root.putObject("values");
		defaults.written().forEach(values::put);

		try {
			Files.createDirectories(file.getParent());
			FileUtil.writeAtomically(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
		} catch (IOException failure) {
			throw new ConfigException("Failed to write the tracked defaults of " + document + ": " + file, failure);
		}
	}

	private static TrackedDefaults parse(JsonNode root) {
		if (root == null || root.path("version").asInt() != VERSION) return TrackedDefaults.unknown();

		JsonNode values = root.get("values");
		if (values == null || !values.isObject()) return TrackedDefaults.unknown();

		Map<String, String> previous = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : values.properties()) {
			if (!entry.getValue().isTextual()) return TrackedDefaults.unknown();
			previous.put(entry.getKey(), entry.getValue().asText());
		}

		return TrackedDefaults.of(previous);
	}
}
