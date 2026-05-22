package viaduct.gradle.featureappcontract

import java.io.File
import org.gradle.work.ChangeType
import viaduct.gradle.testing.incremental.IncTestFS
import viaduct.gradle.testing.incremental.IncTestFile

// ── Status shorthands ───────────────────────────────────────────────────────

/** Existing / unchanged — no change in this increment. */
internal val E: ChangeType? = null

/** Added in this increment. */
internal val A: ChangeType = ChangeType.ADDED

/** Modified in this increment. */
internal val M: ChangeType = ChangeType.MODIFIED

/** Removed in this increment. */
internal val R: ChangeType = ChangeType.REMOVED

// ── DSL builders ────────────────────────────────────────────────────────────

/**
 * Top-level entry point for building an assembly-task test case.
 *
 * Usage:
 * ```
 * assemblyTestCase {
 *     module("mod1") {
 *         schema(E)
 *         desc("d1", E)
 *         desc("d2", R)
 *     }
 *     module("mod2") {
 *         schema(A)
 *         desc("d1", A)
 *     }
 *     expect {
 *         assembled("mod1")
 *         deleted("mod2")
 *     }
 * }
 * ```
 */
internal fun assemblyTestCase(block: AssemblyTestCaseBuilder.() -> Unit): AssemblyTestCase {
    val builder = AssemblyTestCaseBuilder()
    builder.block()
    return builder.build()
}

internal class AssemblyTestCaseBuilder {
    private val modules = mutableListOf<ModuleSpec>()
    private var expectation: ExpectationSpec? = null

    fun module(
        name: String,
        block: ModuleBuilder.() -> Unit
    ) {
        val builder = ModuleBuilder(name)
        builder.block()
        modules.add(builder.build())
    }

    fun expect(block: ExpectationBuilder.() -> Unit) {
        val builder = ExpectationBuilder()
        builder.block()
        expectation = builder.build()
    }

    fun build(): AssemblyTestCase {
        return AssemblyTestCase(
            modules = modules,
            expectation = requireNotNull(expectation) { "expect { } block is required" },
        )
    }
}

internal class ModuleBuilder(private val name: String) {
    private var schemaStatus: ChangeType? = null
    private var schemaSet = false
    private val descriptors = mutableListOf<DescriptorSpec>()

    fun schema(status: ChangeType?) {
        schemaStatus = status
        schemaSet = true
    }

    fun desc(
        name: String,
        status: ChangeType?
    ) {
        descriptors.add(DescriptorSpec(name, status))
    }

    fun build(): ModuleSpec {
        require(schemaSet) { "schema() must be called for module '$name'" }
        return ModuleSpec(name, schemaStatus, descriptors)
    }
}

internal class ExpectationBuilder {
    private val actions = mutableListOf<ExpectedAction>()

    /** Expect assembleForSchema to be called for this module. */
    fun assembled(module: String) {
        actions.add(ExpectedAction.Assembled(module))
    }

    /** Expect deleteConfig to be called for this module. */
    fun deleted(module: String) {
        actions.add(ExpectedAction.Deleted(module))
    }

    fun build(): ExpectationSpec = ExpectationSpec(actions)
}

// ── Data types ──────────────────────────────────────────────────────────────

internal data class DescriptorSpec(val name: String, val status: ChangeType?)

internal data class ModuleSpec(
    val name: String,
    val schemaStatus: ChangeType?,
    val descriptors: List<DescriptorSpec>,
)

internal sealed interface ExpectedAction {
    data class Assembled(val module: String) : ExpectedAction

    data class Deleted(val module: String) : ExpectedAction
}

internal data class ExpectationSpec(val actions: List<ExpectedAction>)

/**
 * A fully-built test case: the module specs (which become an [IncTestFile] tree)
 * plus the expected incremental actions.
 */
internal data class AssemblyTestCase(
    val modules: List<ModuleSpec>,
    val expectation: ExpectationSpec,
) {
    /**
     * Builds the [IncTestFile] entries with two top-level directories:
     * `descriptors/` and `schemas/`, laid out as the assembly task expects.
     */
    fun buildEntries(): List<IncTestFile> {
        val descriptorChildren = modules.map { mod ->
            IncTestFile(
                name = mod.name,
                status = null,
                files = mod.descriptors.map { desc ->
                    IncTestFile("${desc.name}.json", status = desc.status, files = null)
                },
            )
        }

        val schemaChildren = modules.map { mod ->
            IncTestFile(
                name = mod.name,
                status = null,
                files = listOf(
                    IncTestFile("schema.graphql", status = mod.schemaStatus, files = null),
                ),
            )
        }

        return listOf(
            IncTestFile("descriptors", status = null, files = descriptorChildren),
            IncTestFile("schemas", status = null, files = schemaChildren),
        )
    }

    /**
     * Creates an [IncTestFS], materializes the file tree, and returns
     * the descriptor and schema change lists ready for [processChanges].
     */
    fun materialize(root: File): MaterializedTestCase {
        val entries = buildEntries()
        val fs = IncTestFS(root, entries)
        return MaterializedTestCase(
            descriptorRoot = File(root, "descriptors"),
            schemasDir = File(root, "schemas"),
            descriptorChanges = fs.toChanges("descriptors"),
            schemaChanges = fs.toChanges("schemas"),
            expectation = expectation,
        )
    }
}

internal data class MaterializedTestCase(
    val descriptorRoot: File,
    val schemasDir: File,
    val descriptorChanges: List<org.gradle.work.FileChange>,
    val schemaChanges: List<org.gradle.work.FileChange>,
    val expectation: ExpectationSpec,
)
