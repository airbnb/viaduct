package viaduct.utils.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test to ensure all tests extending FeatureAppTestBase follow the naming convention.
 * This test scans the entire projects/viaduct/oss project.
 */
class FeatureAppTestNamingTest {
    @Test
    fun `all tests extending FeatureAppTestBase should contain FeatureAppTest in their name`() {
        val scope = Konsist.scopeFromProject("../../../../../")

        println("=== FeatureAppTest Naming Convention Validation ===")

        // Find all classes that extend FeatureAppTestBase
        val featureAppTestClasses = scope
            .classes()
            .filter { it.hasParentClass { parent -> parent.name == "FeatureAppTestBase" } }

        println("Found ${featureAppTestClasses.size} classes extending FeatureAppTestBase")

        // Use Konsist's assertTrue for better error reporting
        featureAppTestClasses.assertTrue(
            additionalMessage = "Test class names extending FeatureAppTestBase must contain 'FeatureAppTest' to follow naming conventions"
        ) { testClass ->
            val hasCorrectNaming = testClass.name.contains("FeatureAppTest")

            if (!hasCorrectNaming) {
                println("❌ Class ${testClass.name} in ${testClass.packagee?.name} does not follow naming convention")
                println("  - Expected: class name should contain 'FeatureAppTest'")
                println("  - Actual: ${testClass.name}")
            }

            hasCorrectNaming
        }

        if (featureAppTestClasses.isNotEmpty()) {
            println("✅ All FeatureAppTest classes follow proper naming conventions")
        } else {
            println("ℹ️  No classes extending FeatureAppTestBase found")
        }
    }
}
