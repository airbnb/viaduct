package ktlint

internal object GradleConstants {
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
        "jacocoAggregation",
    )

    val CONFIGURATION_SUFFIXES = setOf(
        "Implementation",
        "Api",
        "CompileOnly",
        "RuntimeOnly",
    )
}
