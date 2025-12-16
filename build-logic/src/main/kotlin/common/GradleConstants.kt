package common

/**
 * Constants related to Gradle build scripts, primarily for use in custom detekt rules.
 */
internal object GradleConstants {
    /**
     * Common Gradle dependency configurations.
     *
     * Note: This list is not exhaustive. Gradle configurations are dynamic and extensible:
     * - Build variants (e.g., debug/release) generate additional configurations
     * - Plugins add their own configurations (kapt, ksp, etc.)
     * - Projects can define custom configurations
     *
     * This list covers the most common base configurations and should be used in conjunction
     * with [CONFIGURATION_SUFFIXES] to catch variant-specific configurations.
     */
    val KNOWN_CONFIGURATIONS = setOf(
        "api",
        "compileOnly",
        "debugImplementation",
        "implementation",
        "kapt",
        "ksp",
        "runtimeOnly",
        "testFixturesApi",
        "testFixturesCompileOnly",
        "testFixturesImplementation",
        "testFixturesRuntimeOnly",
        "testImplementation",
        "jacocoAggregation"
    )

    /**
     * Common suffixes for Gradle dependency configurations.
     *
     * Used to detect variant-specific and custom configurations that follow Gradle's
     * naming conventions, such as:
     * - `debugImplementation`, `releaseImplementation`
     * - `productionApi`, `stagingApi`
     * - `myCustomCompileOnly`
     *
     * Any configuration ending with these suffixes is treated as a valid dependency declaration.
     */
    val CONFIGURATION_SUFFIXES = listOf(
        "Implementation",
        "Api",
        "CompileOnly",
        "RuntimeOnly"
    )
}
