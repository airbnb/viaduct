@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.generateResolverTestBatches

/**
 * Writes the first generation of stored suites that have no archive yet. Stored suites are kept, not reproduced, so an existing
 * suite is replaced only with [REGENERATE_FLAG], which discards its batches and their IDs.
 */
object ResolverTestSuiteMaterializer {
    const val REGENERATE_FLAG = "--regenerate"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Usage: ResolverTestSuiteMaterializer <resources-root> [$REGENERATE_FLAG] [suite-id...]"
        }
        val resourcesRoot = workspaceRelativePath(args.first())
        val regenerate = REGENERATE_FLAG in args.drop(1)
        val selectedIds = args.drop(1).filterNot { arg -> arg == REGENERATE_FLAG }.toSet()
        val unknown = selectedIds - ResolverTestSuiteDefinition.entries.map(ResolverTestSuiteDefinition::id).toSet()
        require(unknown.isEmpty()) { "Unknown resolver test suites $unknown" }
        require(!regenerate || selectedIds.isNotEmpty()) { "$REGENERATE_FLAG replaces stored batches, so it requires the suite IDs to replace" }
        ResolverTestSuiteDefinition.entries
            .filter { definition -> selectedIds.isEmpty() || definition.id in selectedIds }
            .forEach { definition ->
                when {
                    !stored(resourcesRoot, definition) || regenerate -> materialize(resourcesRoot, definition)
                    definition.id in selectedIds ->
                        throw IllegalArgumentException("Resolver test suite ${definition.id} is already stored; pass $REGENERATE_FLAG to replace its batches")
                    else -> println("Keeping stored resolver test suite ${definition.id}")
                }
            }
    }

    private fun stored(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
    ): Boolean = Files.exists(resourcesRoot.resolve(suiteArchiveResource(definition.id)))

    fun materialize(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
    ) {
        val generated = generate(definition)
        // Reproducibility is checked once, when the batches are added; afterwards the stored batches are the record.
        check(generate(definition).map(ResolverTestShardCodec::ids) == generated.map(ResolverTestShardCodec::ids)) {
            "Resolver test suite ${definition.id} generated different batches from the same seed and config; generation must be deterministic"
        }
        ResolverTestSuite.fromGenerated(definition, generated).retainingDefinitionBatches().writeTo(resourcesRoot)
    }

    fun write(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
        generated: List<ResolverTestBatch>,
        partBytes: Int = RESOLVER_TEST_SUITE_PART_BYTES,
    ) = ResolverTestSuite.fromGenerated(definition, generated).writeTo(resourcesRoot, partBytes)

    private fun generate(definition: ResolverTestSuiteDefinition): List<ResolverTestBatch> = runBlocking { generateResolverTestBatches(definition.counts, definition.config, definition.seed) }
}
