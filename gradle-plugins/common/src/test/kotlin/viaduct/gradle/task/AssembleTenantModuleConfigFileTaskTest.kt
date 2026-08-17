package viaduct.gradle.task

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import java.io.File
import org.gradle.api.file.FileType
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.gradle.task.AssembleTenantModuleConfigFileTask.Companion.processChanges

/**
 * Unit tests for the incremental routing logic in [processChanges].
 *
 * Each test materializes a descriptor directory state, builds a
 * [FileChange] list, runs [processChanges] with a [RecordingIncrementalActions],
 * and asserts on the recorded actions.
 */
private const val TEST_PACKAGE = "com.example.test"

class AssembleTenantModuleConfigFileTaskTest {
    @TempDir
    private lateinit var root: File

    private fun descriptorDir(): File = File(root, "descriptors").also { it.mkdirs() }

    private fun change(
        dir: File,
        name: String,
        type: ChangeType
    ): FileChange {
        val file = File(dir, name)
        return TestFileChange(
            file = file,
            changeType = type,
            normalizedPath = name,
            fileType = FileType.FILE,
        )
    }

    // ── No changes ──────────────────────────────────────────────────────────

    @Test
    fun `no changes produces no actions`() {
        val dir = descriptorDir()
        File(dir, "Existing.json").createNewFile()

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, emptyList())

        recorder.actions.shouldBeEmpty()
    }

    // ── Descriptor added ────────────────────────────────────────────────────

    @Test
    fun `added descriptor triggers assembly`() {
        val dir = descriptorDir()
        File(dir, "NewResolver.json").createNewFile()

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(change(dir, "NewResolver.json", ChangeType.ADDED)))

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Assembled)
    }

    // ── Descriptor modified ─────────────────────────────────────────────────

    @Test
    fun `modified descriptor triggers assembly`() {
        val dir = descriptorDir()
        File(dir, "MyResolver.json").createNewFile()

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(change(dir, "MyResolver.json", ChangeType.MODIFIED)))

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Assembled)
    }

    // ── Descriptor removed with remaining ───────────────────────────────────

    @Test
    fun `removed descriptor with remaining descriptors triggers assembly`() {
        val dir = descriptorDir()
        File(dir, "Remaining.json").createNewFile()

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(change(dir, "Removed.json", ChangeType.REMOVED)))

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Assembled)
    }

    // ── All descriptors removed ─────────────────────────────────────────────

    @Test
    fun `all descriptors removed triggers delete`() {
        val dir = descriptorDir()
        // Directory exists but is empty (all files removed)

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(change(dir, "Last.json", ChangeType.REMOVED)))

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Deleted(TEST_PACKAGE))
    }

    @Test
    fun `directory gone triggers delete`() {
        // Don't create the directory — it doesn't exist
        val dir = File(root, "nonexistent")

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(change(dir, "Gone.json", ChangeType.REMOVED)))

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Deleted(TEST_PACKAGE))
    }

    // ── Multiple changes ────────────────────────────────────────────────────

    @Test
    fun `multiple descriptor changes produce single assembly`() {
        val dir = descriptorDir()
        File(dir, "A.json").createNewFile()
        File(dir, "B.json").createNewFile()

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(
            dir,
            TEST_PACKAGE,
            listOf(
                change(dir, "A.json", ChangeType.MODIFIED),
                change(dir, "B.json", ChangeType.ADDED),
                change(dir, "C.json", ChangeType.REMOVED),
            ),
        )

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Assembled)
    }

    // ── Non-file changes are skipped ────────────────────────────────────────

    @Test
    fun `directory-type changes are silently skipped`() {
        val dir = descriptorDir()
        File(dir, "Real.json").createNewFile()

        val dirChange = TestFileChange(
            file = File(dir, "subdir"),
            changeType = ChangeType.ADDED,
            normalizedPath = "subdir",
            fileType = FileType.DIRECTORY,
        )

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(dirChange))

        recorder.actions.shouldBeEmpty()
    }

    @Test
    fun `non-json files are silently skipped`() {
        val dir = descriptorDir()
        File(dir, "Real.json").createNewFile()

        val nonJsonChange = TestFileChange(
            file = File(dir, "README.txt"),
            changeType = ChangeType.ADDED,
            normalizedPath = "README.txt",
            fileType = FileType.FILE,
        )

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, TEST_PACKAGE, listOf(nonJsonChange))

        recorder.actions.shouldBeEmpty()
    }

    // ── Regression: deleteConfig receives correct package ───────────────────

    @Test
    fun `deleteConfig receives tenant package not empty string`() {
        val dir = descriptorDir()
        val pkg = "com.example.myapp.resolvers"

        val recorder = RecordingIncrementalActions()
        recorder.processChanges(dir, pkg, listOf(change(dir, "Last.json", ChangeType.REMOVED)))

        recorder.actions.shouldContainExactly(RecordingIncrementalActions.Action.Deleted(pkg))
    }
}

/**
 * Tests for [AssembleTenantModuleConfigFileTask.clearOwnedModuleConfigs].
 */
class AssembleTenantModuleConfigFileCleanupTest {
    @TempDir
    private lateinit var outputDir: File

    private fun modulesDir(): File = File(outputDir, "META-INF/viaduct/modules").also { it.mkdirs() }

    @Test
    fun `clearOwnedModuleConfigs removes stale json files`() {
        val modules = modulesDir()
        File(modules, "com.example.old.json").writeText("{}")
        File(modules, "com.example.current.json").writeText("{}")

        val task = createTaskForTest(outputDir)
        task.clearOwnedModuleConfigs()

        assertTrue(modules.listFiles()!!.isEmpty())
    }

    @Test
    fun `clearOwnedModuleConfigs does not delete non-json files`() {
        val modules = modulesDir()
        File(modules, "com.example.json").writeText("{}")
        File(modules, "README.txt").writeText("keep me")

        val task = createTaskForTest(outputDir)
        task.clearOwnedModuleConfigs()

        modules.listFiles()!!.map { it.name }.shouldContainExactly("README.txt")
    }

    @Test
    fun `clearOwnedModuleConfigs does not touch siblings of META-INF`() {
        modulesDir()
        val sibling = File(outputDir, "other-resources").also { it.mkdirs() }
        File(sibling, "important.txt").writeText("don't touch")

        val task = createTaskForTest(outputDir)
        task.clearOwnedModuleConfigs()

        assertTrue(File(sibling, "important.txt").exists())
    }

    @Test
    fun `clearOwnedModuleConfigs is safe when modules dir does not exist`() {
        val task = createTaskForTest(outputDir)
        task.clearOwnedModuleConfigs()
        // No exception thrown
    }

    private fun createTaskForTest(outputDir: File): AssembleTenantModuleConfigFileTask {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val task = project.tasks.create("testAssemble", AssembleTenantModuleConfigFileTask::class.java)
        task.outputDir.set(outputDir)
        return task
    }
}
