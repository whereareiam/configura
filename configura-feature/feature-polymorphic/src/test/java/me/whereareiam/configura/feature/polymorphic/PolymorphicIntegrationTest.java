package me.whereareiam.configura.feature.polymorphic;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.configura.feature.polymorphic.api.annotation.Polymorphic;
import me.whereareiam.configura.type.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PolymorphicIntegrationTest {
	@Getter
	@ToString
	@NoArgsConstructor
	@SuperBuilder(toBuilder = true)
	@Polymorphic(
			discriminator = "type",
			mappings = {
					@Polymorphic.Type(value = "SYMBOL", target = SymbolTrigger.class),
					@Polymorphic.Type(value = "REGEX", target = RegexTrigger.class),
					@Polymorphic.Type(value = "COMMAND", target = CommandTrigger.class)
			},
			defaultValue = "SYMBOL"
	)
	public static class TriggerBase {
		protected String type;
		protected boolean strip;
		protected Integer radius;
	}

	@Getter
	@ToString(callSuper = true)
	@SuperBuilder(toBuilder = true)
	@NoArgsConstructor
	public static class SymbolTrigger extends TriggerBase {
		private String symbol;
	}

	@Getter
	@ToString(callSuper = true)
	@SuperBuilder(toBuilder = true)
	@NoArgsConstructor
	public static class RegexTrigger extends TriggerBase {
		private String pattern;
	}

	@Getter
	@ToString(callSuper = true)
	@SuperBuilder(toBuilder = true)
	@NoArgsConstructor
	public static class CommandTrigger extends TriggerBase {
		private String command;
	}

	public static class InferBase {
	}

	public static class InferServers extends InferBase {
		public List<String> servers;
	}

	public static class InferWorlds extends InferBase {
		public List<String> worlds;
	}

	public static class BuilderBase {
		public String type;
		public Integer radius;
	}

	public static class BuilderSymbol extends BuilderBase {
		public String symbol;
	}

	public static class BuilderRegex extends BuilderBase {
		public String pattern;
	}

	public static class BuilderCommand extends BuilderBase {
		public String command;
	}

	public static class TriggerListHolder {
		public List<TriggerBase> triggers;
	}

	@Test
	void readsSymbolVariantFromYamlAnnotation() {
		String yaml = """
				type: SYMBOL
				symbol: "#"
				strip: true
				radius: 10
				""";
		TriggerBase value = annotationConfigura().read(yaml.getBytes(), TriggerBase.class);
		assertInstanceOf(SymbolTrigger.class, value);
		assertEquals("#", ((SymbolTrigger) value).getSymbol());
	}

	@Test
	void readsAnnotatedPolymorphicListEntriesFromYaml() {
		String yaml = """
				triggers:
				  - type: SYMBOL
				    symbol: "#"
				    strip: true
				    radius: 10
				  - type: COMMAND
				    command: reply
				    strip: false
				    radius: 0
				""";
		TriggerListHolder value = annotationConfigura().read(yaml.getBytes(), TriggerListHolder.class);
		assertNotNull(value.triggers);
		assertEquals(2, value.triggers.size());
		assertInstanceOf(SymbolTrigger.class, value.triggers.get(0));
		assertEquals("#", ((SymbolTrigger) value.triggers.get(0)).getSymbol());
		assertInstanceOf(CommandTrigger.class, value.triggers.get(1));
		assertEquals("reply", ((CommandTrigger) value.triggers.get(1)).getCommand());
	}

	@Test
	void writesOnlyRelevantFieldsJsonAnnotation() {
		RegexTrigger value = RegexTrigger.builder()
				.type("REGEX")
				.pattern("^hi")
				.strip(false)
				.radius(null)
				.build();

		String text = new String(annotationConfigura(Format.JSON).writeBytes(value));

		assertTrue(text.contains("type"));
		assertTrue(text.contains("REGEX"));
		assertTrue(text.contains("pattern"));
		assertFalse(text.contains("symbol"));
		assertFalse(text.contains("command"));
	}

	@Test
	void readsCommandVariantWithBuilderMappingBuilder() {
		PolymorphicFeature feature = PolymorphicFeature.defaults();
		feature.register(BuilderBase.class)
				.discriminator("type")
				.map("SYMBOL", BuilderSymbol.class)
				.map("REGEX", BuilderRegex.class)
				.map("COMMAND", BuilderCommand.class)
				.defaultValue("SYMBOL")
				.build();

		String yaml = """
				type: COMMAND
				command: reply
				radius: 0
				""";

		BuilderBase value = Config.builder()
				.format(Format.YAML)
				.feature(feature)
				.build()
				.read(yaml.getBytes(), BuilderBase.class);
		assertInstanceOf(BuilderCommand.class, value);
		assertEquals("reply", ((BuilderCommand) value).command);
	}

	@Test
	void readsWithBuilderInferenceOnly() {
		PolymorphicFeature feature = PolymorphicFeature.defaults();
		feature.register(InferBase.class)
				.inferByField("servers", InferServers.class)
				.inferByField("worlds", InferWorlds.class)
				.build();

		String yaml = """
				servers: ["s1", "s2"]
				""";
		InferBase value = Config.builder()
				.format(Format.YAML)
				.feature(feature)
				.build()
				.read(yaml.getBytes(), InferBase.class);
		assertInstanceOf(InferServers.class, value);
	}

	@Test
	void trackedValuesInsideASectionFollowNewDefaults(@TempDir Path directory) {
		Configura configura = annotationConfigura().toBuilder().trackDefaults().build();
		Path file = directory.resolve("trigger.yml");

		configura.update(file, SymbolHolder.class);
		TriggerHolder updated = configura.update(file, ChangedSymbolHolder.class);

		SymbolTrigger trigger = assertInstanceOf(SymbolTrigger.class, updated.trigger);
		assertEquals("?", trigger.getSymbol());
	}

	@Test
	void trackedSectionKeepsItsTypeWhenTheDefaultTypeChanges(@TempDir Path directory) {
		Configura configura = annotationConfigura().toBuilder().trackDefaults().build();
		Path file = directory.resolve("trigger.yml");

		configura.update(file, SymbolHolder.class);
		TriggerHolder updated = configura.update(file, RegexHolder.class);

		SymbolTrigger trigger = assertInstanceOf(SymbolTrigger.class, updated.trigger);
		assertEquals("SYMBOL", trigger.getType());
		assertEquals("!", trigger.getSymbol());
		assertEquals("SYMBOL", configura.readNode(file).at("/trigger/type").asText());
	}

	public static class TriggerHolder {
		public TriggerBase trigger;
	}

	public static class SymbolHolder extends TriggerHolder {
		public SymbolHolder() {
			trigger = SymbolTrigger.builder().type("SYMBOL").symbol("!").build();
		}
	}

	public static class ChangedSymbolHolder extends TriggerHolder {
		public ChangedSymbolHolder() {
			trigger = SymbolTrigger.builder().type("SYMBOL").symbol("?").build();
		}
	}

	public static class RegexHolder extends TriggerHolder {
		public RegexHolder() {
			trigger = RegexTrigger.builder().type("REGEX").pattern("^x").build();
		}
	}

	private Configura annotationConfigura() {
		return annotationConfigura(Format.YAML);
	}

	private Configura annotationConfigura(Format format) {
		return Config.builder()
				.format(format)
				.feature(PolymorphicFeature.defaults())
				.build();
	}
}
