// Shared settings for every module. Services add Spring Boot in their own build files.
allprojects {
    group = "com.fundaccounting"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }
    tasks.withType<JavaCompile> { options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked")) }
    tasks.withType<Test> { useJUnitPlatform() }
}
