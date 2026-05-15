package viaduct.gradle

import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.AssembleSchemaPartitionTask

/**
 * Regression guard for the Java module plugin: it must remain scope-unaware.
 * Both ViaductModulePlugin (Kotlin) and ViaductJavaModulePlugin (Java) reuse the same
 * AssembleSchemaPartitionTask and ViaductModuleExtension. Scope validation runs only after
 * central assembly (AssembleCentralSchemaTask).
 * These tests mirror ViaductModuleScopeRegressionTest from the Kotlin module plugin and
 * confirm that the Java module plugin also satisfies the scope isolation contract.
 */
class ViaductJavaModuleScopeRegressionTest {

    // ── Java variant: task has expected input/output contract ─────────

    @Test
    fun `AssembleSchemaPartitionTask can be registered from Java module plugin`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java)
        assertNotNull(task)
        assertTrue(task is AssembleSchemaPartitionTask)
    }

    @Test
    fun `AssembleSchemaPartitionTask has correct annotations`() {
        val clazz = AssembleSchemaPartitionTask::class.java
        assertTrue(clazz.getMethod("getPrefixPath").isAnnotationPresent(Input::class.java))
        assertTrue(clazz.getMethod("getSchemaFiles").isAnnotationPresent(InputFiles::class.java))
        assertTrue(clazz.getMethod("getOutputDirectory").isAnnotationPresent(OutputDirectory::class.java))
        assertTrue(clazz.getMethod("getGraphqlSrcDir").isAnnotationPresent(Internal::class.java))
    }

    // ── Java variant: module extension does not expose scope DSL ──────

    @Test
    fun `ViaductModuleExtension used by Java plugin does not expose scope DSL`() {
        // ViaductJavaModulePlugin reuses ViaductModuleExtension from the Kotlin module plugin.
        val methodNames = ViaductModuleExtension::class.java.methods.map { it.name }
        assertFalse("declaredSchemaScopes" in methodNames,
            "ViaductModuleExtension must NOT expose declaredSchemaScopes")
        assertFalse("declaredScopedSchema" in methodNames,
            "ViaductModuleExtension must NOT expose declaredScopedSchema")
    }

    @Test
    fun `ViaductJavaModulePlugin itself has no scope-related methods`() {
        val pluginMethods = ViaductJavaModulePlugin::class.java.declaredMethods.map { it.name }
        val scopeMethods = pluginMethods.filter { it.lowercase().contains("scope") }
        assertTrue(scopeMethods.isEmpty(),
            "ViaductJavaModulePlugin must have no scope-related methods: $scopeMethods")
    }

    // ── Java variant: partition task is scope-unaware ────────────────

    @Test
    fun `AssembleSchemaPartitionTask has no scope @Input properties`() {
        val scopeInputs = AssembleSchemaPartitionTask::class.java.methods.filter { m ->
            m.isAnnotationPresent(Input::class.java) && m.name.lowercase().contains("scope")
        }
        assertTrue(scopeInputs.isEmpty(),
            "AssembleSchemaPartitionTask must be scope-unaware: ${scopeInputs.map { it.name }}")
    }

    @Test
    fun `AssembleCentralSchemaTask is the sole scope gate`() {
        val scopeInputs = AssembleCentralSchemaTask::class.java.methods.filter { m ->
            m.isAnnotationPresent(Input::class.java) && m.name.lowercase().contains("scope")
        }
        assertFalse(scopeInputs.isEmpty(),
            "AssembleCentralSchemaTask must have @Input scope properties")
    }
}
