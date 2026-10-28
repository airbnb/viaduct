package viaduct.engine.runtime2.benchmark

import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.testing.TestWorld

class ResolverBenchmarkFixture(private val corpus: ResolverBenchmarkCorpus) {
    val checkers = ChinaActiveBenchmarkCheckers(corpus)
    val schemaSDL = checkers.schemaSDL + "\n" + ResolverBenchmarkFeaturePartition.schemaSDL

    fun world(outputs: PrecomputedResolverOutputs): TestWorld =
        corpus.registry.world(
            schemaSDL = schemaSDL,
            precomputedResolverOutputs = outputs,
            additionalFieldResolvers = { schema -> checkers.fieldResolvers(schema) + ResolverBenchmarkFeaturePartition.fieldResolvers(schema) },
            additionalVariableProviders = ResolverBenchmarkFeaturePartition::variableProviders,
            fieldCheckerFactory = { schema -> checkers.fieldCheckers(schema) + ResolverBenchmarkFeaturePartition.fieldCheckers(schema) },
            typeCheckerFactory = { schema -> checkers.typeCheckers(schema) + ResolverBenchmarkFeaturePartition.typeCheckers(schema) },
        )
}

data class ResolverBenchmarkResultShape(
    val fields: Long = 0,
    val activeFields: Long = 0,
    val passiveFields: Long = 0,
    val depth: Int = 0,
) {
    operator fun plus(other: ResolverBenchmarkResultShape) =
        ResolverBenchmarkResultShape(
            fields + other.fields,
            activeFields + other.activeFields,
            passiveFields + other.passiveFields,
            maxOf(depth, other.depth),
        )
}

suspend fun EngineResult?.benchmarkShape(
    world: Assumptions,
    depth: Int = 0
): ResolverBenchmarkResultShape {
    var shape = ResolverBenchmarkResultShape(depth = depth)
    when (this) {
        null, is ErrorEngineResult -> Unit
        is ListEngineResult -> indices.forEach { index ->
            val cell = get(index)
            if (cell.fetchActivated()) shape += cell.value.get().benchmarkShape(world, depth)
        }
        is ObjectEngineResult -> keys.forEach { key ->
            val cell = getCell(key)
            if (!cell.fetchActivated()) return@forEach
            val child = if (key is ObjectEngineResult.ParentKey) {
                ResolverBenchmarkResultShape(depth = depth + 1)
            } else {
                cell.value.get().benchmarkShape(world, depth + 1)
            }
            shape += child.copy(
                fields = child.fields + 1,
                activeFields = child.activeFields + if (key.field in world.resolverRegistry) 1 else 0,
                passiveFields = child.passiveFields + if (key.field in world.resolverRegistry) 0 else 1,
            )
        }
        else -> Unit
    }
    return shape
}
