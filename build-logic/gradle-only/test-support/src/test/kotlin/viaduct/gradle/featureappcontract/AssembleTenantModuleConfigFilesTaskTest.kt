package viaduct.gradle.featureappcontract

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.io.File
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
                module("newmod") {
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
}
