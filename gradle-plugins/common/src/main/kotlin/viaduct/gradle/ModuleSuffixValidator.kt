package viaduct.gradle

import viaduct.apiannotations.InternalApi

@InternalApi
data class ModuleSuffixEntry(
    val projectPath: String,
    val suffix: String,
)

@InternalApi
data class ViaductPluginValidationError(
    val code: String,
    val message: String,
)

@InternalApi
object ModuleSuffixValidator {
    const val DUPLICATE_SUFFIX = "MODULE_SUFFIX_DUPLICATE"
    const val PREFIX_COLLISION = "MODULE_SUFFIX_PREFIX_COLLISION"
    const val EMPTY_SUFFIX_WITH_SIBLINGS = "MODULE_SUFFIX_EMPTY_WITH_SIBLINGS"

    fun validate(entries: List<ModuleSuffixEntry>): List<ViaductPluginValidationError> {
        val errors = mutableListOf<ViaductPluginValidationError>()
        val withNormalized = entries.map { it to normalize(it.suffix) }
        val (blankPairs, nonBlankPairs) = withNormalized.partition { it.second.isEmpty() }

        if (entries.size > 1) {
            blankPairs.forEach { (blankEntry, _) ->
                errors += ViaductPluginValidationError(
                    code = EMPTY_SUFFIX_WITH_SIBLINGS,
                    message = "Module '${blankEntry.projectPath}' declares an empty modulePackageSuffix, " +
                        "but empty suffix is only valid when the application declares exactly one module. " +
                        "Give each module a non-empty modulePackageSuffix when an application has multiple modules.",
                )
            }
        }

        val sorted = nonBlankPairs.sortedBy { it.second }
        for (i in sorted.indices) {
            val (a, normalizedA) = sorted[i]
            var j = i + 1
            while (j <= sorted.lastIndex && sorted[j].second.startsWith(normalizedA)) {
                val (b, normalizedB) = sorted[j]
                if (normalizedB == normalizedA) {
                    errors += ViaductPluginValidationError(
                        code = DUPLICATE_SUFFIX,
                        message = "Modules '${a.projectPath}' and '${b.projectPath}' declare the same " +
                            "modulePackageSuffix ('${a.suffix}'). Each module in an application must " +
                            "have a unique modulePackageSuffix.",
                    )
                } else {
                    errors += ViaductPluginValidationError(
                        code = PREFIX_COLLISION,
                        message = "Module '${a.projectPath}' declares modulePackageSuffix '${a.suffix}', " +
                            "which is a package prefix of module '${b.projectPath}'s modulePackageSuffix " +
                            "'${b.suffix}'. Viaduct tooling assumes a module owns every sub-package under " +
                            "its own full package prefix, so '${a.projectPath}' would already claim to own " +
                            "'${b.projectPath}'. No module's modulePackageSuffix may be a package prefix of " +
                            "another module's.",
                    )
                }
                j++
            }
        }

        return errors
    }

    private fun normalize(suffix: String): String {
        val trimmed = suffix.trim().trim('.')
        return if (trimmed.isEmpty()) "" else "$trimmed."
    }
}
