# Getting started

This guide takes you from the dependency to a configured instance. It assumes Java 17 and a model
class Jackson can bind: public fields, or getters and setters, and a no-argument constructor.

## Dependencies

```kotlin
repositories {
    maven("https://registry.whereareiam.me/maven/packages")
}

dependencies {
    implementation("me.whereareiam:configura:<version>")

    // Optional, each only when you use it; same version as Configura:
    implementation("me.whereareiam.configura.feature:polymorphic:<version>")
    implementation("me.whereareiam.configura.feature:extension:<version>")
    implementation("me.whereareiam.configura.feature:postprocess:<version>")
    implementation("me.whereareiam.configura.feature:comment:<version>")
}
```

## Build an instance

`Config.yaml()` and `Config.json()` give you a plain instance. Build your own when you need
Jackson modules, defaults providers or features:

```java
import me.whereareiam.configura.Configura;
import me.whereareiam.configura.feature.postprocess.PostProcessFeature;
import me.whereareiam.configura.type.Format;

Configura configura = Configura.builder()
		.format(Format.YAML)
		.module(new MyJacksonModule())
		.defaults(SettingsDefaults.class)
		.feature(PostProcessFeature.defaults())
		.build();
```

An instance is immutable. To get a variant, derive one; the original is unchanged:

```java
Configura messages = configura.withDefaults(MessagesDefaults.class);
Configura json = configura.toBuilder().format(Format.JSON).build();
```

`configura.mapper()` returns the `ObjectMapper` files are read and written with, including your
modules and those of the features.

### Let untouched values follow new defaults

By default `update` keeps every value a file has, including defaults an older release wrote. If
values the user never changed should move to your new defaults, enable tracking:

```java
Configura configura = Configura.builder()
		.defaults(MessagesDefaults.class)
		.trackDefaults()
		.build();
```

`update` then keeps a small record of what it wrote in a `.configura` directory beside each file.
Values the user edited are always kept. How values are matched, and how to exempt a field, is in
[Defaults and merging](MERGE.md#let-untouched-values-follow-new-defaults).

### Another format

```java
Configura toml = Configura.builder()
		.format("toml", modules -> new TomlMapper().registerModules(modules))
		.build();
```

The function receives the modules to register and returns the mapper for the format. The
format's Jackson dependency, here `jackson-dataformat-toml`, is yours to add.

## Load, read and write

Paths may be given with or without the extension; without one, the instance's extension is added.

| Call | What it does |
| --- | --- |
| `update(path, Settings.class)` | Reads the file, fills in missing defaults, binds, writes the file back. With `trackDefaults()`, also moves values nobody changed to the current defaults. Use this at startup. |
| `read(path, Settings.class)` | Binds the file as it is. No defaults, nothing written. Also takes `byte[]` or an `InputStream`. |
| `merge(path, settings)` | Reads the file and fills in what it lacks from the given object; returns the bound result, writes nothing. |
| `save(path, settings)` | Writes the given object, completed by the model's defaults. |
| `write(path, settings)` | Writes the given object exactly. |
| `writeBytes(settings)` | Serializes the object without touching a file. |
| `readNode(...)`, `writeNode(...)`, `writeNodeBytes(node)` | The same on Jackson trees, for documents you do not want to bind to a model. |

Failures are reported as `ConfigException`. Writes replace the file atomically, so a failed write
leaves the previous content.

## Supply defaults

Field initializers are the simplest defaults. When defaults need code, or should live apart from
the model, register a provider:

```java
import me.whereareiam.configura.merge.defaults.DefaultsProvider;

public final class SettingsDefaults implements DefaultsProvider<Settings> {
	@Override
	public Settings supply(Settings settings) {
		settings.servers = List.of(server("hub"), server("arena"));
		return settings;
	}
}
```

How defaults and a user's file are combined, and how to change that per field with `@Merge`, is
covered in [Defaults and merging](MERGE.md).

## Share one instance

Code that cannot have the instance handed in can use a shared one:

```java
Config.setConfigured(configura);   // once, during startup

Configura shared = Config.configured();
```

Before `setConfigured` is called, `Config.configured()` is a plain YAML instance.
`Config.builder()`, `Config.yaml()` and `Config.json()` always start fresh; the shared instance has
no influence on them.

## Next

- [Defaults and merging](MERGE.md)
- [Features](FEATURES.md)
