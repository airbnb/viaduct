package viaduct.gradle.featureappcontract

import io.kotest.matchers.string.shouldContain
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [FeatureAppContractsExtension]'s cross-language exclusivity guard.
 *
 * Kotlin and Java contract codegen emit the same GRT / resolver-base class names
 * for a given schema, so a single project may activate at most one language block.
 * These tests exercise that rule directly via [ProjectBuilder], using no-op setup
 * runnables and recording whether each was invoked.
 */
class FeatureAppContractsExtensionTest {
    @Test
    fun `activating only kotlin runs the kotlin setup once`() {
        val (project, ext, kotlinRuns, javaRuns) = newExtension()

        ext.kotlin.contractsFrom(project.path)

        assertEquals(1, kotlinRuns.get())
        assertEquals(0, javaRuns.get())
    }

    @Test
    fun `activating only java runs the java setup once`() {
        val (project, ext, kotlinRuns, javaRuns) = newExtension()

        ext.java.contractsFrom(project.path)

        assertEquals(0, kotlinRuns.get())
        assertEquals(1, javaRuns.get())
    }

    @Test
    fun `activating the same language twice is allowed and sets up once`() {
        val (project, ext, kotlinRuns, _) = newExtension()

        ext.kotlin.contractsFrom(project.path)
        ext.kotlin.contractsFrom(project.path)

        // Setup only fires on first activation of the block.
        assertEquals(1, kotlinRuns.get())
    }

    @Test
    fun `activating both kotlin and java fails fast`() {
        val (project, ext, _, javaRuns) = newExtension()

        ext.kotlin.contractsFrom(project.path)

        val e1 = assertThrows<IllegalArgumentException> { ext.java.contractsFrom(project.path) }
        e1.message!! shouldContain "only one language per project"
        e1.message!! shouldContain "'kotlin'"
        e1.message!! shouldContain "'java'"

        // The Java setup must not have run — the guard throws before it fires.
        assertEquals(0, javaRuns.get())
    }

    @Test
    fun `activating both java and kotlin fails fast`() {
        val (project, ext, kotlinRuns, _) = newExtension()

        ext.java.contractsFrom(project.path)

        val e2 = assertThrows<IllegalArgumentException> { ext.kotlin.contractsFrom(project.path) }
        e2.message!! shouldContain "only one language per project"

        assertEquals(0, kotlinRuns.get())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private data class Fixture(
        val project: Project,
        val ext: FeatureAppContractsExtension,
        val kotlinRuns: java.util.concurrent.atomic.AtomicInteger,
        val javaRuns: java.util.concurrent.atomic.AtomicInteger,
    )

    /**
     * Builds an extension wired to a real [ProjectBuilder] project. The
     * `extractContractSchemas` task is created so the same-project `contractsFrom`
     * path resolves, and the two resolvable configurations are created as the
     * plugin would. Setup runnables only increment counters.
     */
    private fun newExtension(): Fixture {
        val project = ProjectBuilder.builder().build()
        project.tasks.create("extractContractSchemas", ContractSchemaExtractTask::class.java)

        val kotlinSchemas = project.configurations.create("contractSchemasResolved")
        val javaSchemas = project.configurations.create("javaContractSchemasResolved")

        val kotlinRuns = java.util.concurrent.atomic.AtomicInteger(0)
        val javaRuns = java.util.concurrent.atomic.AtomicInteger(0)

        val ext = project.objects.newInstance(
            FeatureAppContractsExtension::class.java,
            project,
            kotlinSchemas,
            Runnable { kotlinRuns.incrementAndGet() },
            javaSchemas,
            Runnable { javaRuns.incrementAndGet() },
        )

        return Fixture(project, ext, kotlinRuns, javaRuns)
    }
}
