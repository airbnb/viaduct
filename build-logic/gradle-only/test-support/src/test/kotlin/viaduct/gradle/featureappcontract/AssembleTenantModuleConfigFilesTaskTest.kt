package viaduct.gradle.featureappcontract

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.gradle.featureappcontract.AssembleTenantModuleConfigFilesTask.Companion.processChanges

/**
 * Unit tests for the incremental routing logic in [processChanges].
 *
 * Each test builds a test case via the [assemblyTestCase] DSL, materializes
 * the file tree, runs [processChanges] with a [RecordingIncrementalActions],
 * and asserts on the recorded actions.
 */
class AssembleTenantModuleConfigFilesTaskTest {
    private fun run(
        testCase: AssemblyTestCase,
        root: File
    ) {
        val m = testCase.materialize(root)
        val recorder = RecordingIncrementalActions()
        recorder.processChanges(
            m.descriptorRoot,
            m.schemasDir,
            m.descriptorChanges,
            m.schemaChanges,
        )
        recorder.actions.shouldContainExactlyInAnyOrder(m.expectation.actions)
    }

    // ── No changes ──────────────────────────────────────────────────────────

    @Test
    fun `no changes produces no actions`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", E)
                }
                module("mod2") {
                    schema(E)
                    desc("d1", E)
                }
                expect {}
            },
            root,
        )
    }

    // ── Descriptor changes only ─────────────────────────────────────────────

    @Test
    fun `added descriptor triggers assembly for that module only`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", E)
                    desc("d2", A)
                }
                module("mod2") {
                    schema(E)
                    desc("d1", E)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `modified descriptor triggers assembly`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", M)
                }
                module("mod2") {
                    schema(E)
                    desc("d1", E)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `removed descriptor with remaining descriptors triggers assembly`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", E)
                    desc("d2", R)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `all descriptors removed triggers delete`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", R)
                }
                expect {
                    deleted("mod1")
                }
            },
            root,
        )
    }

    // ── Schema changes only ─────────────────────────────────────────────────

    @Test
    fun `modified schema triggers assembly for that module only`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(M)
                    desc("d1", E)
                }
                module("mod2") {
                    schema(E)
                    desc("d1", E)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `removed schema triggers delete even with descriptors present`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(R)
                    desc("d1", E)
                }
                expect {
                    deleted("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `added schema with existing descriptors triggers assembly`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(A)
                    desc("d1", E)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    // ── Combined descriptor + schema changes ────────────────────────────────

    @Test
    fun `schema and descriptor both change in same module triggers one assembly`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(M)
                    desc("d1", M)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `schema removed and descriptors removed triggers one delete`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(R)
                    desc("d1", R)
                }
                expect {
                    deleted("mod1")
                }
            },
            root,
        )
    }

    // ── Multi-module scenarios ───────────────────────────────────────────────

    @Test
    fun `changes in multiple modules produce independent actions`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", M)
                }
                module("mod2") {
                    schema(R)
                    desc("d1", R)
                }
                module("mod3") {
                    schema(E)
                    desc("d1", E)
                }
                expect {
                    assembled("mod1")
                    deleted("mod2")
                }
            },
            root,
        )
    }

    @Test
    fun `new module added alongside existing unchanged module`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("existing") {
                    schema(E)
                    desc("d1", E)
                }
                module("newmod", A) {
                    schema(A)
                    desc("d1", A)
                }
                expect {
                    assembled("newmod")
                }
            },
            root,
        )
    }

    @Test
    fun `module whose directories are removed triggers delete`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("gone", R) {
                    schema(R)
                    desc("d1", R)
                }
                expect {
                    deleted("gone")
                }
            },
            root,
        )
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `module with no descriptors at all and schema removed triggers delete`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(R)
                }
                expect {
                    deleted("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `module with multiple descriptors all removed triggers delete`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", R)
                    desc("d2", R)
                    desc("d3", R)
                }
                expect {
                    deleted("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `module with one descriptor added and one removed triggers assembly`(
        @TempDir root: File
    ) {
        run(
            assemblyTestCase {
                module("mod1") {
                    schema(E)
                    desc("d1", A)
                    desc("d2", R)
                }
                expect {
                    assembled("mod1")
                }
            },
            root,
        )
    }

    @Test
    fun `Gradle reports leaf file changes when package directories are added and removed`(
        @TempDir projectDir: File
    ) {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"incremental-probe\"")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import org.gradle.api.DefaultTask
            import org.gradle.api.file.DirectoryProperty
            import org.gradle.api.file.RegularFileProperty
            import org.gradle.api.tasks.Incremental
            import org.gradle.api.tasks.InputDirectory
            import org.gradle.api.tasks.InputFiles
            import org.gradle.api.tasks.Optional
            import org.gradle.api.tasks.OutputFile
            import org.gradle.api.tasks.PathSensitive
            import org.gradle.api.tasks.PathSensitivity
            import org.gradle.api.tasks.TaskAction
            import org.gradle.work.InputChanges

            abstract class ObserveChanges : DefaultTask() {
                @get:Incremental
                @get:InputFiles
                @get:Optional
                @get:PathSensitive(PathSensitivity.RELATIVE)
                abstract val descriptors: DirectoryProperty

                @get:Incremental
                @get:InputDirectory
                @get:Optional
                @get:PathSensitive(PathSensitivity.RELATIVE)
                abstract val schemas: DirectoryProperty

                @get:OutputFile
                abstract val report: RegularFileProperty

                @TaskAction
                fun observe(inputChanges: InputChanges) {
                    val changes = buildList {
                        add("incremental=" + inputChanges.isIncremental)
                        inputChanges.getFileChanges(descriptors).forEach {
                            add("descriptor:" + it.changeType + ":" + it.fileType + ":" + it.normalizedPath)
                        }
                        inputChanges.getFileChanges(schemas).forEach {
                            add("schema:" + it.changeType + ":" + it.fileType + ":" + it.normalizedPath)
                        }
                    }
                    report.get().asFile.writeText(changes.joinToString("\\n"))
                }
            }

            tasks.register<ObserveChanges>("observe") {
                descriptors.set(layout.projectDirectory.dir("descriptors"))
                schemas.set(layout.projectDirectory.dir("schemas"))
                report.set(layout.buildDirectory.file("changes.txt"))
            }
            """.trimIndent()
        )

        projectDir.resolve("descriptors/gone/Old.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        projectDir.resolve("schemas/gone/schema.graphql").apply {
            parentFile.mkdirs()
            writeText("type Query")
        }

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("observe", "--stacktrace")
            .build()

        projectDir.resolve("descriptors/gone").deleteRecursively()
        projectDir.resolve("schemas/gone").deleteRecursively()
        projectDir.resolve("descriptors/added/New.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        projectDir.resolve("schemas/added/schema.graphql").apply {
            parentFile.mkdirs()
            writeText("type Query")
        }

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("observe", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":observe")?.outcome)
        val changes = projectDir.resolve("build/changes.txt").readLines()
        assertTrue("incremental=true" in changes)
        assertTrue("descriptor:REMOVED:FILE:gone/Old.json" in changes)
        assertTrue("schema:REMOVED:FILE:gone/schema.graphql" in changes)
        assertTrue("descriptor:ADDED:FILE:added/New.json" in changes)
        assertTrue("schema:ADDED:FILE:added/schema.graphql" in changes)
    }
}
