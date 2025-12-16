package viaduct.utils.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test to ensure no circular dependencies exist in the dependency graph using Konsist.
 * This test scans the entire projects/viaduct/oss project.
 */
class DependencyCycleTest {
    @Test
    fun `should not have dependency cycles in the project`() {
        val scope = Konsist.scopeFromProject("../../../../../")

        // Get all unique packages in the project
        val allPackages = scope.files
            .mapNotNull { it.packagee }
            .distinctBy { it.name }

        // Use Konsist's assertTrue for better error reporting
        allPackages.assertTrue(
            additionalMessage = "Circular dependencies detected in the project"
        ) { packageDeclaration ->
            val packageName = packageDeclaration.name

            // Get all packages that this package imports from
            val importedPackageNames = scope.files
                .filter { it.packagee?.name == packageName }
                .flatMap { it.imports }
                .mapNotNull { import ->
                    // Use Konsist's import analysis instead of string manipulation
                    val importPackageName = import.name.substringBeforeLast('.')
                    if (importPackageName != packageName) importPackageName else null
                }.distinct()

            // Check if any imported package also imports back to this package (circular dependency)
            val hasCircularDependency = importedPackageNames.any { importedPackageName ->
                scope.files
                    .filter { it.packagee?.name == importedPackageName }
                    .flatMap { it.imports }
                    .any { reverseImport ->
                        reverseImport.name.substringBeforeLast('.') == packageName
                    }
            }

            if (hasCircularDependency) {
                println("❌ Circular dependency detected involving package: $packageName")
                importedPackageNames
                    .filter { importedPackageName ->
                        scope.files
                            .filter { it.packagee?.name == importedPackageName }
                            .flatMap { it.imports }
                            .any { it.name.substringBeforeLast('.') == packageName }
                    }.forEach { cyclicPackage ->
                        println("  - $packageName ↔ $cyclicPackage")
                    }
            }

            !hasCircularDependency
        }

        println("✅ No circular dependencies found in the project")
    }
}
