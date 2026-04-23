package viaduct.gradle.shared

/**
 * Build flag utilities shared across Viaduct code generation plugins.
 *
 * This lives in build-logic/shared so that both build-test-plugins and
 * gradle-plugins/common can depend on it without pulling in core modules.
 */
object BuildFlags {
    val DEFAULT: Map<String, String> = mapOf(
        "enable_binary_schema" to "True"
    )

    /**
     * Generates the content for a Viaduct build flags file in .bzl format.
     *
     * The generated file can be:
     * 1. Imported directly in Starlark (BUILD files, macros, rules)
     * 2. Read as a file in Bazel actions (via ctx.actions.run)
     *
     * @param flags Map of flag names to their values
     * @return The formatted build flags file content
     */
    fun toFileContent(flags: Map<String, String>): String {
        val flagEntries = flags.entries.joinToString("\n    ") { (key, value) ->
            "\"$key\": \"$value\","
        }
        return """
            |# Viaduct build flags configuration
            |#
            |# This file uses .bzl format so it can be:
            |# 1. Imported directly in Starlark (BUILD files, macros, rules)
            |# 2. Read as a file in Bazel actions (via ctx.actions.run)
            |#
            |# Note: Values are strings ("True"/"False") for compatibility when
            |# passed as action inputs.
            |viaduct_build_flags = {
            |    $flagEntries
            |}
            """.trimMargin()
    }
}
