package me.whereareiam.configura.merge.track;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import me.whereareiam.configura.Configura;
import me.whereareiam.configura.annotation.merge.Merge;
import me.whereareiam.configura.type.merge.WhenPresent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedDefaultsIntegrationTest {
	private final Configura configura = Configura.builder().trackDefaults().build();

	@TempDir
	Path directory;

	@Test
	void recordsTheWrittenDefaultsBesideTheFile() {
		configura.update(file(), Before.class);

		Path record = directory.resolve(".configura").resolve("messages.yml.defaults.json");
		assertTrue(Files.isRegularFile(record));
		assertTrue(configura.tracksDefaults());
	}

	@Test
	void addsValuesTheFileLacks() {
		configura.update(file(), Before.class);
		After after = configura.update(file(), After.class);

		assertEquals("Welcome back", after.added);
		assertEquals("Welcome back", written().at("/added").asText());
	}

	@Test
	void movesUntouchedValuesToTheNewDefaults() {
		configura.update(file(), Before.class);
		After after = configura.update(file(), After.class);

		assertEquals("Hi there", after.greeting);
		assertEquals("Hi there", written().at("/greeting").asText());
	}

	@Test
	void keepsValuesTheUserEdited() {
		configura.update(file(), Before.class);
		edit("/greeting", text("Howdy"));

		configura.update(file(), After.class);
		assertEquals("Howdy", written().at("/greeting").asText());

		configura.update(file(), Before.class);
		assertEquals("Howdy", written().at("/greeting").asText());
	}

	@Test
	void followsAgainOnceTheUserRestoresTheDefault() {
		configura.update(file(), Before.class);
		edit("/greeting", text("Howdy"));
		configura.update(file(), Before.class);
		edit("/greeting", text("Hello"));

		configura.update(file(), After.class);

		assertEquals("Hi there", written().at("/greeting").asText());
	}

	@Test
	void neverMovesAFieldKeptAsWritten() {
		configura.update(file(), Before.class);
		configura.update(file(), After.class);

		assertEquals("first", written().at("/pinned").asText());
	}

	@Test
	void tracksTheValuesOfNestedSections() {
		configura.update(file(), Before.class);
		edit("/section/footer", text("My footer"));

		configura.update(file(), After.class);

		assertEquals("New title", written().at("/section/title").asText());
		assertEquals("My footer", written().at("/section/footer").asText());
	}

	@Test
	void tracksAListAsAWhole() {
		configura.update(file(), Before.class);
		configura.update(file(), After.class);

		assertEquals(List.of("a", "b", "c"), texts(written().path("tags")));

		edit("/tags/2", text("mine"));
		configura.update(file(), Before.class);

		assertEquals(List.of("a", "b", "mine"), texts(written().path("tags")));
	}

	@Test
	void tracksTheEntriesOfAKeyedList() {
		configura.update(file(), Before.class);
		edit("/entries/1/text", text("My two"));

		configura.update(file(), After.class);

		JsonNode entries = written().path("entries");
		assertEquals(3, entries.size());
		assertEquals("One!", entries.get(0).path("text").asText());
		assertEquals("My two", entries.get(1).path("text").asText());
		assertEquals("three", entries.get(2).path("id").asText());
	}

	@Test
	void tracksTheEntriesOfAMap() {
		configura.update(file(), Before.class);
		edit("/lines/leave", text("{player} is gone"));

		configura.update(file(), After.class);

		assertEquals("{player} joined the game", written().at("/lines/join").asText());
		assertEquals("{player} is gone", written().at("/lines/leave").asText());
	}

	@Test
	void leavesEntriesTheDefaultsDropped() {
		configura.update(file(), Before.class);
		configura.update(file(), After.class);

		assertEquals("Old", written().at("/lines/old").asText());
	}

	@Test
	void keepsEveryValueWhenTheRecordIsMissing() throws IOException {
		configura.update(file(), Before.class);
		Files.delete(record());

		configura.update(file(), After.class);

		assertEquals("Hello", written().at("/greeting").asText());
		assertEquals("Title", written().at("/section/title").asText());
		assertEquals("Welcome back", written().at("/added").asText());
		assertTrue(Files.isRegularFile(record()));
	}

	@Test
	void keepsEveryValueWhenTheRecordIsCorrupt() throws IOException {
		configura.update(file(), Before.class);
		Files.writeString(record(), "{ not json");

		configura.update(file(), After.class);
		assertEquals("Hello", written().at("/greeting").asText());

		configura.update(file(), Before.class);
		assertEquals("Hello", written().at("/greeting").asText());
	}

	@Test
	void keepsEveryValueOfAFileWrittenBeforeTracking() {
		Configura untracked = Configura.builder().build();
		untracked.update(file(), Before.class);
		assertFalse(Files.exists(record()));

		configura.update(file(), After.class);

		assertEquals("Hello", written().at("/greeting").asText());
	}

	@Test
	void leavesValuesAloneWithoutTracking() {
		Configura untracked = Configura.builder().build();
		untracked.update(file(), Before.class);
		untracked.update(file(), After.class);

		assertEquals("Hello", written().at("/greeting").asText());
		assertFalse(Files.exists(record()));
		assertFalse(untracked.tracksDefaults());
	}

	@Test
	void keepsTrackingInDerivedInstances() {
		assertTrue(configura.toBuilder().build().tracksDefaults());
	}

	private Path file() {
		return directory.resolve("messages.yml");
	}

	private Path record() {
		return directory.resolve(".configura").resolve("messages.yml.defaults.json");
	}

	/** Changes one value of the file as a user would, without touching the record. */
	private void edit(String pointer, JsonNode value) {
		JsonNode document = written();
		JsonPointer at = JsonPointer.compile(pointer);
		JsonNode parent = document.at(at.head());
		if (parent instanceof ObjectNode object) object.set(at.last().getMatchingProperty(), value);
		else ((ArrayNode) parent).set(at.last().getMatchingIndex(), value);

		configura.writeNode(file(), document);
	}

	private static JsonNode text(String value) {
		return TextNode.valueOf(value);
	}

	private JsonNode written() {
		return configura.readNode(file());
	}

	private static List<String> texts(JsonNode array) {
		List<String> texts = new ArrayList<>();
		array.forEach(element -> texts.add(element.asText()));

		return texts;
	}

	private static Map<String, String> lines(String... pairs) {
		Map<String, String> lines = new LinkedHashMap<>();
		for (int index = 0; index < pairs.length; index += 2)
			lines.put(pairs[index], pairs[index + 1]);

		return lines;
	}

	public static class Section {
		public String title;
		public String footer;

		public Section() {
		}

		Section(String title, String footer) {
			this.title = title;
			this.footer = footer;
		}
	}

	public static class Entry {
		public String id;
		public String text = "";

		public Entry() {
		}

		Entry(String id, String text) {
			this.id = id;
			this.text = text;
		}
	}

	public static class Before {
		public String greeting = "Hello";
		@Merge(present = WhenPresent.KEEP_AS_WRITTEN)
		public String pinned = "first";
		public Section section = new Section("Title", "Footer");
		public List<String> tags = List.of("a", "b", "c0");
		@Merge(key = "id")
		public List<Entry> entries = List.of(new Entry("one", "One"), new Entry("two", "Two"));
		public Map<String, String> lines = lines("join", "{player} joined", "leave", "{player} left", "old", "Old");
	}

	public static class After {
		public String greeting = "Hi there";
		public String added = "Welcome back";
		@Merge(present = WhenPresent.KEEP_AS_WRITTEN)
		public String pinned = "second";
		public Section section = new Section("New title", "New footer");
		public List<String> tags = List.of("a", "b", "c");
		@Merge(key = "id")
		public List<Entry> entries = List.of(new Entry("one", "One!"), new Entry("two", "Two!"), new Entry("three", "Three"));
		public Map<String, String> lines = lines("join", "{player} joined the game", "leave", "{player} left the game");
	}
}
