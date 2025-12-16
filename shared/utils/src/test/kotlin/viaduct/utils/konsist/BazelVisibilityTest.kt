package viaduct.utils.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Konsist test that validates package dependencies follow Bazel visibility rules.
 * Only enforces rules for packages that explicitly define visibility constraints.
 *
 * This test runs at the root project level to analyze all subprojects.
 */
class BazelVisibilityTest {
    data class VisibilityRule(
        val packagePath: String,
        val allowedPackages: List<String>,
        val allowsSubpackages: List<String>
    )

    private fun parseBazelVisibilityRules(): List<VisibilityRule> {
        val rules = mutableListOf<VisibilityRule>()
        val scope = Konsist.scopeFromProject("../../../../../")

        // Use Konsist to find all BUILD.bazel files
        scope.files
            .filter { it.name == "BUILD.bazel" }
            .forEach { buildFile ->
                val content = buildFile.text
                val visibilityMatches = Regex("""visibility\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(content)

                visibilityMatches.forEach { match ->
                    val visibilityContent = match.groupValues[1]
                    val (allowedPackages, allowsSubpackages) = parseVisibilityList(visibilityContent)

                    if (allowedPackages.isNotEmpty() || allowsSubpackages.isNotEmpty()) {
                        // Use Konsist's path handling instead of manual string manipulation
                        val packagePath = buildFile.path
                            .substringBeforeLast("/BUILD.bazel")
                            .replace("/", ".")
                        rules.add(VisibilityRule(packagePath, allowedPackages, allowsSubpackages))
                    }
                }
            }

        return rules
    }

    private fun parseVisibilityList(visibilityContent: String): Pair<List<String>, List<String>> {
        val allowedPackages = mutableListOf<String>()
        val allowsSubpackages = mutableListOf<String>()

        visibilityContent
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() && it != "//visibility:public" }
            .forEach { visibility ->
                when {
                    visibility.endsWith(":__subpackages__") -> {
                        val packagePrefix = visibility.removeSuffix(":__subpackages__").removePrefix("//")
                        allowsSubpackages.add(packagePrefix)
                    }
                    visibility.endsWith(":__pkg__") -> {
                        val packageName = visibility.removeSuffix(":__pkg__").removePrefix("//")
                        allowedPackages.add(packageName)
                    }
                    else -> {
                        // Default to exact package match
                        allowedPackages.add(visibility.removePrefix("//"))
                    }
                }
            }

        return Pair(allowedPackages, allowsSubpackages)
    }

    @Test
    fun `packages should only be imported by packages allowed in their visibility rules`() {
        val scope = Konsist.scopeFromProject("../../../../../")
        val visibilityRules = parseBazelVisibilityRules()

        println("=== Bazel Visibility Validation ===")
        println("Found ${visibilityRules.size} visibility rules")
        println("Analyzing ${scope.files.size} files")

        // Use Konsist's assertTrue for better error reporting
        scope.files.assertTrue(
            additionalMessage = "Some files violate Bazel visibility rules"
        ) { file ->
            val filePackageName = file.packagee?.name ?: ""
            val filePackagePath = filePackageName.replace(".", "/")

            // Check all imports against visibility rules
            val violatesVisibilityRules = file.imports.any { import ->
                val importPackagePath = import.name.substringBeforeLast('.').replace(".", "/")

                // Find if this import violates any visibility rule
                visibilityRules.any { rule ->
                    val restrictedPackagePrefix = rule.packagePath.replace(".", "/")

                    // If the import is from a restricted package
                    importPackagePath.startsWith(restrictedPackagePrefix) &&
                        // And the current file is not allowed by the visibility rule
                        !isPackageAllowed(filePackagePath, rule)
                }
            }

            if (violatesVisibilityRules) {
                println("❌ File ${file.path} (package: $filePackageName) violates Bazel visibility rules")
                file.imports
                    .filter { import ->
                        val importPackagePath = import.name.substringBeforeLast('.').replace(".", "/")
                        visibilityRules.any { rule ->
                            val restrictedPackagePrefix = rule.packagePath.replace(".", "/")
                            importPackagePath.startsWith(restrictedPackagePrefix) &&
                                !isPackageAllowed(filePackagePath, rule)
                        }
                    }.forEach { violatingImport ->
                        println("  - Violating import: ${violatingImport.name}")
                    }
            }

            !violatesVisibilityRules
        }

        println("✅ All visibility rules validated successfully")
    }

    @Test
    fun `test subpackage matching logic specifically`() {
        val visibilityRules = parseBazelVisibilityRules()
        println("=== Testing subpackage logic ===")

        // Find a rule with subpackages to test with, or skip if none found
        val testRule = visibilityRules.find { it.allowsSubpackages.isNotEmpty() }

        if (testRule == null) {
            println("ℹ️  No visibility rules with subpackages found - skipping subpackage logic test")
            return
        }

        println("Testing rule: ${testRule.packagePath}")
        println("Allowed subpackages: ${testRule.allowsSubpackages}")

        // Test various package paths
        val testPackagePaths = listOf(
            "viaduct/utils",
            "projects/viaduct/oss/shared/utils/src/test/kotlin/viaduct/utils",
            "projects/viaduct/oss",
            "projects/viaduct/oss/shared",
            "projects/viaduct/oss/tenant",
            "projects/other",
            "common/utils"
        )

        testPackagePaths.forEach { packagePath ->
            val isAllowed = isPackageAllowed(packagePath, testRule)
            println("Package '$packagePath' -> allowed: $isAllowed")
        }

        println("✅ Subpackage matching logic test completed")
    }

    private fun isPackageAllowed(
        filePackagePath: String,
        rule: VisibilityRule
    ): Boolean {
        // Check exact package matches
        if (rule.allowedPackages.any { allowedPackage ->
                filePackagePath == allowedPackage.replace(".", "/")
            }
        ) {
            return true
        }

        // Check subpackage matches (package and all its subpackages)
        if (rule.allowsSubpackages.any { allowedPrefix ->
                val prefix = allowedPrefix.replace(".", "/")
                filePackagePath == prefix || filePackagePath.startsWith("$prefix/")
            }
        ) {
            return true
        }

        return false
    }
}
