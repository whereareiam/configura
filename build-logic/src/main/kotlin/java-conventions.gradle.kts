plugins {
	`java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "me.whereareiam"
version = providers.environmentVariable("VERSION").orElse("dev").get()

repositories {
	mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    // Constructors keep their parameters' names, which the tests' immutable models are read by.
    options.compilerArgs.add("-parameters")
}

dependencies {
	"compileOnly"(libs.findLibrary("jetbrains-annotations").get())
	"testCompileOnly"(libs.findLibrary("jetbrains-annotations").get())
}
