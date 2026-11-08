package me.whereareiam.configura.merge.tree.list;

import me.whereareiam.configura.type.merge.UnknownEntries;
import me.whereareiam.configura.type.merge.MissingEntries;
import me.whereareiam.configura.type.merge.WhenPresent;
import me.whereareiam.configura.type.merge.WhenAbsent;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.configura.annotation.merge.Merge;
import me.whereareiam.configura.exception.ConfigException;
import me.whereareiam.configura.merge.defaults.DefaultsProvider;
import me.whereareiam.configura.type.Format;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Config Collection Merge")
class ListMergeIntegrationTest {
	@Test
	@DisplayName("Missing keyed list fields seed the whole default list")
	void missingKeyedListFieldsSeedWholeDefaultList(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");

		ProviderListConfig config = yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class);

		assertEquals(2, config.providers.size());
		assertEquals("premium", config.providers.get(0).id);
		assertEquals("credential", config.providers.get(1).id);
		assertTrue(Files.readString(file).contains("premium"));
		assertTrue(Files.readString(file).contains("credential"));
	}

	@Test
	@DisplayName("Explicit empty keyed list stays empty under declared only presence")
	void explicitEmptyKeyedListStaysEmptyUnderDeclaredOnly(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, "providers: []\n");

		ProviderListConfig config = yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class);

		assertTrue(config.providers.isEmpty());
		assertTrue(Files.readString(file).contains("providers: []"));
	}

	@Test
	@DisplayName("Matching keyed entries receive missing defaults")
	void matchingKeyedEntriesReceiveMissingDefaults(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: premium
				    enabled: false
				""");

		ProviderListConfig config = yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class);

		assertEquals(1, config.providers.size());
		ProviderEntry premium = config.providers.get(0);
		assertEquals("premium", premium.id);
		assertFalse(premium.enabled);
		assertEquals(100, premium.priority);
		assertEquals("", premium.displayName);
		assertNotNull(premium.joinRestriction);
		assertFalse(premium.joinRestriction.enabled);
		assertNotNull(premium.verification);
		assertTrue(premium.verification.enabled);
		assertNotNull(premium.session);
		assertNotNull(premium.session.recognition);
		assertFalse(premium.session.recognition.allowOnUntrustedIps);
		assertNull(premium.session.concurrencyPolicy);
	}

	@Test
	@DisplayName("Custom keyed entries receive generic entry defaults without a keyed default match")
	void customKeyedEntriesReceiveGenericEntryDefaults(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: community-oauth
				""");

		ProviderListConfig config = yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class);

		assertEquals(1, config.providers.size());
		ProviderEntry provider = config.providers.get(0);
		assertEquals("community-oauth", provider.id);
		assertEquals("", provider.displayName);
		assertFalse(provider.enabled);
		assertEquals(0, provider.priority);
		assertNotNull(provider.joinRestriction);
		assertFalse(provider.joinRestriction.enabled);
		assertNotNull(provider.verification);
		assertFalse(provider.verification.enabled);
		assertNotNull(provider.session);
		assertNotNull(provider.session.recognition);
		assertTrue(provider.session.recognition.signals.isEmpty());
		assertNull(provider.session.concurrencyPolicy);
	}

	@Test
	@DisplayName("Custom keyed entries of a type without defaults are kept as written")
	void customKeyedEntriesWithoutTypeDefaultsAreKept(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("labels.yml");
		Files.writeString(file, """
				labels:
				  - id: custom
				    text: Mine
				""");

		LabelListConfig config = Config.yaml().update(file, LabelListConfig.class);

		assertEquals(2, config.labels.size());
		assertEquals("custom", config.labels.get(0).id);
		assertEquals("Mine", config.labels.get(0).text);
		assertEquals("shipped", config.labels.get(1).id);
	}

	@Test
	@DisplayName("Seed defaults presence appends omitted default entries")
	void seedDefaultsPresenceAppendsOmittedDefaultEntries(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: premium
				    enabled: false
				""");

		SeededProviderListConfig config = yaml(SeededProviderListDefaults.class).update(file, SeededProviderListConfig.class);

		assertEquals(2, config.providers.size());
		assertEquals("premium", config.providers.get(0).id);
		assertEquals("credential", config.providers.get(1).id);
	}

	@Test
	@DisplayName("Seeded keyed entries are materialized through object merge")
	void seededKeyedEntriesAreMaterializedThroughObjectMerge(@TempDir Path tempDir) {
		Path file = tempDir.resolve("providers.yml");

		DeclaredProviderListConfig config = yaml(DeclaredProviderListDefaults.class).update(file, DeclaredProviderListConfig.class);

		assertEquals(2, config.providers.size());
		DeclaredProviderEntry premium = config.providers.get(0);
		assertEquals("premium", premium.id);
		assertFalse(premium.enabled);
		assertEquals(100, premium.priority);
		assertTrue(premium.entrypoints.isEmpty());
		assertNull(premium.session);
		assertNull(premium.verification);
		assertNull(premium.joinRestriction);
	}

	@Test
	@DisplayName("Default domain only rejects unknown keyed entries")
	void defaultDomainOnlyRejectsUnknownKeyedEntries(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: custom-provider
				""");

		ConfigException exception = assertThrows(
				ConfigException.class,
				() -> yaml(LockedProviderListDefaults.class).update(file, LockedProviderListConfig.class)
		);
		assertTrue(exception.getMessage().contains("custom-provider"));
	}

	@Test
	@DisplayName("Source owns field short-circuits keyed list merge")
	void sourceOwnsFieldShortCircuitsKeyedListMerge(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: premium
				    enabled: false
				""");

		SourceOwnedProviderListConfig config = yaml(SourceOwnedProviderListDefaults.class).update(file, SourceOwnedProviderListConfig.class);

		assertEquals(1, config.providers.size());
		ProviderEntry premium = config.providers.get(0);
		assertEquals("premium", premium.id);
		assertFalse(premium.enabled);
		assertEquals(0, premium.priority);
	}

	@Test
	@DisplayName("Nested keyed list entries merge by id")
	void nestedKeyedListEntriesMergeById(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: premium
				    verification:
				      methods:
				        - id: totp
				          priority: 200
				""");

		ProviderListConfig config = yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class);

		assertEquals(1, config.providers.size());
		List<MethodEntry> methods = config.providers.get(0).verification.methods;
		assertEquals(1, methods.size());
		assertEquals("totp", methods.get(0).id);
		assertTrue(methods.get(0).enabled);
		assertEquals(200, methods.get(0).priority);
	}

	@Test
	@DisplayName("Duplicate source keys fail clearly")
	void duplicateSourceKeysFailClearly(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - id: premium
				  - id: premium
				""");

		ConfigException exception = assertThrows(
				ConfigException.class,
				() -> yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class)
		);
		assertTrue(exception.getMessage().contains("twice"));
		assertTrue(exception.getMessage().contains("premium"));
	}

	@Test
	@DisplayName("Missing source keys fail clearly")
	void missingSourceKeysFailClearly(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("providers.yml");
		Files.writeString(file, """
				providers:
				  - enabled: true
				""");

		ConfigException exception = assertThrows(
				ConfigException.class,
				() -> yaml(ProviderListDefaults.class).update(file, ProviderListConfig.class)
		);
		assertTrue(exception.getMessage().contains("no 'id'"));
	}

	@Test
	@DisplayName("Duplicate default keys fail clearly")
	void duplicateDefaultKeysFailClearly(@TempDir Path tempDir) {
		Path file = tempDir.resolve("providers.yml");

		ConfigException exception = assertThrows(
				ConfigException.class,
				() -> yaml(DuplicateProviderListDefaults.class).update(file, ProviderListConfig.class)
		);
		assertTrue(exception.getMessage().contains("twice"));
		assertTrue(exception.getMessage().contains("premium"));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private Configura yaml(Class<? extends DefaultsProvider<?>> defaultsProvider) {
		return Config.builder()
				.format(Format.YAML)
				.defaults((Class) defaultsProvider)
				.build();
	}

	public static class ProviderListConfig {
		@Merge(key = "id", missingEntries = MissingEntries.OMIT)
		public List<ProviderEntry> providers = new ArrayList<>();
	}

	public static class SeededProviderListConfig {
		@Merge(key = "id")
		public List<ProviderEntry> providers = new ArrayList<>();
	}

	public static class LockedProviderListConfig {
		@Merge(key = "id", unknownEntries = UnknownEntries.REJECT)
		public List<ProviderEntry> providers = new ArrayList<>();
	}

	public static class SourceOwnedProviderListConfig {
		@Merge(present = WhenPresent.KEEP_AS_WRITTEN, key = "id")
		public List<ProviderEntry> providers = new ArrayList<>();
	}

	public static class DeclaredProviderListConfig {
		@Merge(key = "id")
		public List<DeclaredProviderEntry> providers = new ArrayList<>();
	}

	public static class LabelListConfig {
		@Merge(key = "id")
		public List<LabelEntry> labels = List.of(new LabelEntry("shipped", "Shipped"));
	}

	public static class LabelEntry {
		public String id;
		public String text;

		public LabelEntry() {
		}

		LabelEntry(String id, String text) {
			this.id = id;
			this.text = text;
		}
	}

	public static class ProviderEntry {
		public String id;
		public String displayName = "";
		public boolean enabled;
		public int priority;
		public JoinRestriction joinRestriction = new JoinRestriction();
		public Verification verification = new Verification();
		public Session session = new Session();
	}

	public static class JoinRestriction {
		public boolean enabled;
	}

	public static class Verification {
		public boolean enabled;
		public boolean required;
		@Merge(key = "id", missingEntries = MissingEntries.OMIT)
		public List<MethodEntry> methods = new ArrayList<>();
	}

	public static class MethodEntry {
		public String id;
		public boolean enabled;
		public int priority;
	}

	public static class Session {
		public String concurrencyPolicy;
		public Recognition recognition = new Recognition();
	}

	public static class Recognition {
		public List<String> signals = new ArrayList<>();
		public boolean allowOnUntrustedIps;
	}

	public static class DeclaredProviderEntry {
		public String id;
		public boolean enabled;
		public int priority;
		public List<String> entrypoints = new ArrayList<>();
		@Merge(absent = WhenAbsent.LEAVE_ABSENT)
		public Session session;
		@Merge(absent = WhenAbsent.LEAVE_ABSENT)
		public Verification verification;
		@Merge(absent = WhenAbsent.LEAVE_ABSENT)
		public JoinRestriction joinRestriction;
	}

	public static class ProviderListDefaults implements DefaultsProvider<ProviderListConfig> {
		@Override
		public ProviderListConfig supply(ProviderListConfig config) {
			config.providers = defaultProviders();
			return config;
		}
	}

	public static class SeededProviderListDefaults implements DefaultsProvider<SeededProviderListConfig> {
		@Override
		public SeededProviderListConfig supply(SeededProviderListConfig config) {
			config.providers = defaultProviders();
			return config;
		}
	}

	public static class DeclaredProviderListDefaults implements DefaultsProvider<DeclaredProviderListConfig> {
		@Override
		public DeclaredProviderListConfig supply(DeclaredProviderListConfig config) {
			config.providers = new ArrayList<>();
			config.providers.add(declared("premium", 100));
			config.providers.add(declared("credential", 50));
			return config;
		}

		private DeclaredProviderEntry declared(String id, int priority) {
			DeclaredProviderEntry entry = new DeclaredProviderEntry();
			entry.id = id;
			entry.enabled = false;
			entry.priority = priority;
			entry.session = new Session();
			entry.verification = new Verification();
			entry.joinRestriction = new JoinRestriction();
			return entry;
		}
	}

	public static class LockedProviderListDefaults implements DefaultsProvider<LockedProviderListConfig> {
		@Override
		public LockedProviderListConfig supply(LockedProviderListConfig config) {
			config.providers = defaultProviders();
			return config;
		}
	}

	public static class SourceOwnedProviderListDefaults implements DefaultsProvider<SourceOwnedProviderListConfig> {
		@Override
		public SourceOwnedProviderListConfig supply(SourceOwnedProviderListConfig config) {
			config.providers = defaultProviders();
			return config;
		}
	}

	public static class DuplicateProviderListDefaults implements DefaultsProvider<ProviderListConfig> {
		@Override
		public ProviderListConfig supply(ProviderListConfig config) {
			config.providers = List.of(provider("premium", 100), provider("premium", 90));
			return config;
		}
	}

	private static List<ProviderEntry> defaultProviders() {
		ProviderEntry premium = provider("premium", 100);
		premium.verification.enabled = true;
		premium.verification.methods = List.of(method());

		ProviderEntry credential = provider("credential", 50);
		credential.verification.enabled = true;
		credential.verification.methods = List.of(method());

		return List.of(premium, credential);
	}

	private static ProviderEntry provider(String id, int priority) {
		ProviderEntry entry = new ProviderEntry();
		entry.id = id;
		entry.enabled = true;
		entry.priority = priority;
		return entry;
	}

	private static MethodEntry method() {
		MethodEntry entry = new MethodEntry();
		entry.id = "totp";
		entry.enabled = true;
		entry.priority = 100;
		return entry;
	}
}
