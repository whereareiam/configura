package me.whereareiam.configura.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.whereareiam.configura.type.Format;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class MapperFactoryTest {
	@Test
	void cachesByFormat() {
		ObjectMapper m1 = MapperFactory.buildWriterMapper(Format.YAML);
		ObjectMapper m2 = MapperFactory.buildWriterMapper(Format.YAML);
		ObjectMapper m3 = MapperFactory.buildWriterMapper(Format.JSON);
		assertSame(m1, m2);
		assertNotSame(m1, m3);
	}

	@Test
	void readsAnImmutableClassThroughItsConstructor() throws Exception {
		Range range = MapperFactory.buildReaderMapper(Format.YAML).readValue("from: 2\nto: 5\n", Range.class);

		assertEquals(2, range.from);
		assertEquals(5, range.to);
	}

	@Test
	void readsAnImmutableClassOfOneProperty_FromAMapping() throws Exception {
		Nodes nodes = MapperFactory.buildReaderMapper(Format.YAML).readValue("nodes: [a, b]\n", Nodes.class);

		assertEquals(List.of("a", "b"), nodes.nodes);
	}

	@Test
	void readsARecord() throws Exception {
		Pair pair = MapperFactory.buildReaderMapper(Format.JSON).readValue("{\"left\": \"a\", \"right\": 1}", Pair.class);

		assertEquals(new Pair("a", 1), pair);
	}

	// A record as a downgrade to an older Java leaves it: a final class whose one constructor takes its properties.
	static final class Range {
		private final int from;
		private final int to;

		Range(int from, int to) {
			this.from = from;
			this.to = to;
		}
	}

	static final class Nodes {
		private final List<String> nodes;

		Nodes(List<String> nodes) {
			this.nodes = nodes;
		}
	}

	record Pair(String left, int right) {
	}
}

