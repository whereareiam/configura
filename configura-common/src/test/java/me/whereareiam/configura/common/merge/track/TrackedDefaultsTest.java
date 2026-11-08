package me.whereareiam.configura.common.merge.track;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedDefaultsTest {
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void recognizesTheRecordedDefault() throws Exception {
		TrackedDefaults tracked = TrackedDefaults.unknown();
		tracked.record("/greeting", mapper.readTree("\"Hello\""));

		TrackedDefaults next = TrackedDefaults.of(tracked.written());

		assertTrue(next.untouched("/greeting", mapper.readTree("\"Hello\"")));
		assertFalse(next.untouched("/greeting", mapper.readTree("\"Howdy\"")));
		assertFalse(next.untouched("/farewell", mapper.readTree("\"Hello\"")));
	}

	@Test
	void ignoresTheOrderOfKeys() throws Exception {
		TrackedDefaults tracked = TrackedDefaults.unknown();
		tracked.record("/server", mapper.readTree("{\"host\": \"localhost\", \"port\": 25565}"));

		TrackedDefaults next = TrackedDefaults.of(tracked.written());

		assertTrue(next.untouched("/server", mapper.readTree("{\"port\": 25565, \"host\": \"localhost\"}")));
	}

	@Test
	void knowsNothingWithoutHistory() throws Exception {
		JsonNode value = mapper.readTree("true");

		assertFalse(TrackedDefaults.unknown().untouched("/enabled", value));
	}

	@Test
	void escapesKeysAsJsonPointer() {
		assertEquals("/lines/a~1b~0c", TrackedDefaults.child(TrackedDefaults.child("", "lines"), "a/b~c"));
	}
}
