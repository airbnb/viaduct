package semantics.resolver26.inclusion

import model.objectOf
import model.outputValue
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

internal const val CHAIN_DEPTH = 4
internal const val SEED_VALUE = 7

internal data class InputSnapshot(
    val paths: Set<List<String>>,
    val intValues: Map<List<String>, Int>,
) {
    fun topLevelAliases(): Set<String> =
        paths
            .filterTo(linkedSetOf()) { path -> path.size == 1 }
            .mapTo(linkedSetOf()) { path -> path.single() }
}

internal class InputSnapshotBuilder {
    private val paths = linkedSetOf<List<String>>()
    private val values = linkedMapOf<List<String>, Int>()

    fun objectPath(
        active: Boolean,
        vararg components: String,
    ) {
        if (active) paths += components.toList()
    }

    fun intPath(
        active: Boolean,
        value: Int,
        vararg components: String,
    ) {
        if (!active) return
        val path = components.toList()
        paths += path
        values[path] = value
    }

    fun build(): InputSnapshot = InputSnapshot(paths, values)
}

internal fun ViaductSchema.outputChain(
    valueAt: (Int) -> Int,
    depth: Int = 0,
): EngineObjectData.Sync =
    objectOf("InclusionTester") {
        "i" setTo valueAt(depth)
        "n" setTo
            if (depth < CHAIN_DEPTH - 1) {
                outputChain(valueAt, depth + 1)
            } else {
                null
            }
    }

internal fun List<String>.depth(): Int = count { it == "n" }

internal fun iPathsForAliases(vararg aliases: String): List<List<String>> =
    aliases.flatMap { alias ->
        (0 until CHAIN_DEPTH).map { depth ->
            buildList {
                add(alias)
                repeat(depth) { add("n") }
                add("i")
            }
        }
    }

internal fun EngineObjectData.Sync.snapshot(): InputSnapshot {
    val paths = linkedSetOf<List<String>>()
    val values = linkedMapOf<List<String>, Int>()

    fun visit(
        value: EngineObjectData.Sync,
        prefix: List<String>,
    ) {
        value.getSelections().forEach { responseKey ->
            val path = prefix + responseKey
            paths += path
            when (val selected = value.outputValue(responseKey)) {
                is Int -> values[path] = selected
                is EngineObjectData.Sync -> visit(selected, path)
            }
        }
    }

    visit(this, emptyList())
    return InputSnapshot(paths, values)
}

internal fun EngineObjectData.Sync.valueAtIfPresent(path: List<String>): Int? {
    var current: EngineObjectData.Sync = this
    path.forEachIndexed { index, responseKey ->
        if (!current.isPresent(responseKey)) return null
        val value = current.get(responseKey)
        if (index == path.lastIndex) return value as Int
        current = value as EngineObjectData.Sync
    }
    error("An input path must not be empty")
}
