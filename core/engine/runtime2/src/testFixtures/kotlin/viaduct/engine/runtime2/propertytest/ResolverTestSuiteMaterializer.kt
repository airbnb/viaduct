@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.generateResolverTestBatches

object ResolverTestSuiteMaterializer {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) {
            "Usage: ResolverTestSuiteMaterializer <resources-root> [suite-id...]"
        }
        val resourcesRoot = workspaceRelativePath(args.first())
        val selectedIds = args.drop(1).toSet()
        ResolverTestSuiteDefinition.entries
            .filter { definition -> selectedIds.isEmpty() || definition.id in selectedIds }
            .forEach { definition -> materialize(resourcesRoot, definition) }
    }

    fun materialize(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
    ) {
        val generated = runBlocking { generateResolverTestBatches(definition.counts, definition.config, definition.seed) }
        ResolverTestSuite.fromGenerated(definition, generated).retainingDefinitionBatches().writeTo(resourcesRoot)
    }

    fun write(
        resourcesRoot: Path,
        definition: ResolverTestSuiteDefinition,
        generated: List<ResolverTestBatch>,
        partBytes: Int = RESOLVER_TEST_SUITE_PART_BYTES,
    ) = ResolverTestSuite.fromGenerated(definition, generated).writeTo(resourcesRoot, partBytes)
}
