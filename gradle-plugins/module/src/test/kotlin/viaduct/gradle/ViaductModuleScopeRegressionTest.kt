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
 * Regression guard: module plugins must remain scope-unaware.
 * Scope validation runs only after central assembly (AssembleCentralSchemaTask).
 * These tests verify that the module plugin and its task do not introduce any scope-awareness.
 */
class ViaductModuleScopeRegressionTest {

    // ── task registration and input/output contract ───────────────────

    @Test
    fun `AssembleSchemaPartitionTask can be registered and has expected task contract`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java)
        assertNotNull(task)
        assertTrue(task is AssembleSchemaPartitionTask)
    }

    @Test
    fun `AssembleSchemaPartitionTask prefixPath is annotated Input`() {
        val getter = AssembleSchemaPartitionTask::class.java.getMethod("getPrefixPath")
        assertTrue(getter.isAnnotationPresent(Input::class.java),
            "prefixPath must be @Input for UP-TO-DATE checking")
    }

    @Test
    fun `AssembleSchemaPartitionTask schemaFiles is annotated InputFiles`() {
        val getter = AssembleSchemaPartitionTask::class.java.getMethod("getSchemaFiles")
        assertTrue(getter.isAnnotationPresent(InputFiles::class.java),
            "schemaFiles must be @InputFiles for file tracking and build caching")
    }

    @Test
    fun `AssembleSchemaPartitionTask outputDirectory is annotated OutputDirectory`() {
        val getter = AssembleSchemaPartitionTask::class.java.getMethod("getOutputDirectory")
        assertTrue(getter.isAnnotationPresent(OutputDirectory::class.java),
            "outputDirectory must be @OutputDirectory")
    }

    @Test
    fun `AssembleSchemaPartitionTask graphqlSrcDir is Internal not tracked`() {
        val getter = AssembleSchemaPartitionTask::class.java.getMethod("getGraphqlSrcDir")
        assertTrue(getter.isAnnotationPresent(Internal::class.java),
            "graphqlSrcDir must be @Internal — tracking is done via schemaFiles")
        assertFalse(getter.isAnnotationPresent(Input::class.java),
            "graphqlSrcDir must NOT be @Input")
        assertFalse(getter.isAnnotationPresent(InputFiles::class.java),
            "graphqlSrcDir must NOT be @InputFiles")
    }

    // ── module extension does not expose scope DSL ────────────────────

    @Test
    fun `ViaductModuleExtension does not expose declaredSchemaScopes`() {
        val methodNames = ViaductModuleExtension::class.java.methods.map { it.name }
        assertFalse("declaredSchemaScopes" in methodNames,
            "ViaductModuleExtension must NOT expose declaredSchemaScopes — scope DSL is application-only")
    }

    @Test
    fun `ViaductModuleExtension does not expose declaredScopedSchema`() {
        val methodNames = ViaductModuleExtension::class.java.methods.map { it.name }
        assertFalse("declaredScopedSchema" in methodNames,
            "ViaductModuleExtension must NOT expose declaredScopedSchema — scope DSL is application-only")
    }

    @Test
    fun `ViaductModuleExtension has no scopeUniverse or scopedSchemaEntries fields`() {
        val fieldNames = ViaductModuleExtension::class.java.declaredFields.map { it.name }
        assertFalse("scopeUniverse" in fieldNames,
            "ViaductModuleExtension must NOT have scopeUniverse field")
        assertFalse("scopedSchemaEntries" in fieldNames,
            "ViaductModuleExtension must NOT have scopedSchemaEntries field")
    }

    // ── scope @Input properties only on AssembleCentralSchemaTask ─────

    @Test
    fun `AssembleSchemaPartitionTask has no scope-related @Input properties`() {
        val scopeInputs = AssembleSchemaPartitionTask::class.java.methods.filter { m ->
            m.isAnnotationPresent(Input::class.java) && m.name.lowercase().contains("scope")
        }
        assertTrue(scopeInputs.isEmpty(),
            "AssembleSchemaPartitionTask must be scope-unaware — found scope @Input methods: ${scopeInputs.map { it.name }}")
    }

    @Test
    fun `AssembleCentralSchemaTask has scope-related @Input properties confirming it is the scope gate`() {
        val scopeInputs = AssembleCentralSchemaTask::class.java.methods.filter { m ->
            m.isAnnotationPresent(Input::class.java) && m.name.lowercase().contains("scope")
        }
        assertFalse(scopeInputs.isEmpty(),
            "AssembleCentralSchemaTask must be scope-aware — it is the only scope validation gate")
        val names = scopeInputs.map { it.name }
        assertTrue(names.any { "scopeUniverse" in it || "ScopeUniverse" in it },
            "Expected scopeUniverse @Input on AssembleCentralSchemaTask, found: $names")
        assertTrue(names.any { "scopedSchemaEntries" in it || "ScopedSchemaEntries" in it },
            "Expected scopedSchemaEntries @Input on AssembleCentralSchemaTask, found: $names")
    }

    @Test
    fun `ViaductModulePlugin has no scope-validation logic in setupAssembleSchemaPartitionTask`() {
        // The plugin itself must not introduce any scope-awareness.
        // We verify that ViaductModulePlugin does not reference scope-related classes
        // by confirming it has no field or method with "scope" in the name.
        val pluginMethods = ViaductModulePlugin::class.java.declaredMethods.map { it.name }
        val scopeMethods = pluginMethods.filter { it.lowercase().contains("scope") }
        assertTrue(scopeMethods.isEmpty(),
            "ViaductModulePlugin must have no scope-related methods: $scopeMethods")
    }
}
