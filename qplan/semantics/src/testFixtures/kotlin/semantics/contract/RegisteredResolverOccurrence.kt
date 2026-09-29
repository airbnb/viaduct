@file:Suppress("ForbiddenImport")

package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.registry.ResolverRegistry
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionWitnessBoundExceededException
import semantics.arbitrary.ResolutionWitnessBounds
import semantics.arbitrary.ResolverApplicationKey
import semantics.arbitrary.resolutionFingerprint
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import semantics.shared.SharedOperationContext

/** One registered resolver occurrence discovered independently in a completed result tree. */
data class RegisteredResolverOccurrence(
    val applicationKey: ResolverApplicationKey,
    val field: viaduct.graphql.schema.ViaductSchema.ObjectField,
    val occurrencePath: List<PathComponent>,
    val containingObject: ObjectEngineResult,
) {
    val canonicalField: FieldCoordinate
        get() = applicationKey.field
}

fun EngineResult?.registeredResolverOccurrences(
    operation: SharedOperationContext<*>,
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): List<RegisteredResolverOccurrence> {
    val occurrences = mutableListOf<RegisteredResolverOccurrence>()
    visitRegisteredResolverOccurrences(
        operation = operation,
        registry = registry,
        bounds = bounds,
        canonicalOrder = true,
        visitOccurrence = occurrences::add,
    )
    return occurrences
}

fun EngineResult?.forEachRegisteredResolverOccurrence(
    operation: SharedOperationContext<*>,
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
    visitOccurrence: (RegisteredResolverOccurrence) -> Unit,
) {
    visitRegisteredResolverOccurrences(
        operation,
        registry,
        bounds,
        canonicalOrder = false,
        initialPath = emptyList(),
        visitOccurrence,
    )
}

internal fun EngineResult?.forEachRegisteredResolverOccurrenceAt(
    operation: SharedOperationContext<*>,
    registry: ResolverRegistry,
    initialPath: List<PathComponent>,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
    visitOccurrence: (RegisteredResolverOccurrence) -> Unit,
) {
    visitRegisteredResolverOccurrences(
        operation,
        registry,
        bounds,
        canonicalOrder = false,
        initialPath = initialPath,
        visitOccurrence,
    )
}

private fun EngineResult?.visitRegisteredResolverOccurrences(
    operation: SharedOperationContext<*>,
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds,
    canonicalOrder: Boolean,
    initialPath: List<PathComponent> = emptyList(),
    visitOccurrence: (RegisteredResolverOccurrence) -> Unit,
) {
    var visitedNodes = 0

    fun visit(
        value: EngineResult?,
        path: List<PathComponent>,
    ) {
        visitedNodes += 1
        if (visitedNodes > bounds.maxResultNodes) {
            throw ResolutionWitnessBoundExceededException("result-node", bounds.maxResultNodes)
        }
        if (value == null || value is ErrorEngineResult) return

        when (value) {
            is ObjectEngineResult -> {
                val keys =
                    if (canonicalOrder) {
                        value.keys.sortedBy { key -> key.canonicalFingerprint(bounds) }
                    } else {
                        value.keys
                    }
                keys.forEach { key ->
                    val cell = value.getCell(key)
                    if (!runBlocking { cell.fetchActivated() }) return@forEach
                    val fieldPath = path + key
                    require(key.isContextuallyGrounded(operation)) {
                        "Resolver occurrence key is not contextually grounded: $key"
                    }
                    val arguments = key.groundedArguments(operation) as? Arguments.Resolved
                    if (key.field in registry && arguments != null) {
                        visitOccurrence(
                            RegisteredResolverOccurrence(
                                applicationKey =
                                    ResolverApplicationKey(
                                        field =
                                            FieldCoordinate(
                                                key.field.containingDef.name,
                                                key.field.name,
                                            ),
                                        arguments = arguments,
                                    ),
                                field = key.field,
                                occurrencePath = fieldPath,
                                containingObject = value,
                            ),
                        )
                    }
                    if (key !is ObjectEngineResult.ParentKey) {
                        visit(cell.value.get(), fieldPath)
                    }
                }
            }

            is ListEngineResult ->
                value.forEachIndexed { index, cell ->
                    if (runBlocking { cell.fetchActivated() }) {
                        visit(cell.value.get(), path + ListEngineResult.Index.of(index))
                    }
                }

            else -> Unit
        }
    }

    visit(this, initialPath)
}

fun EngineResult?.registeredResolverOccurrenceCounts(
    operation: SharedOperationContext<*>,
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): Map<ResolverApplicationKey, Int> {
    val counts = linkedMapOf<ResolverApplicationKey, Int>()
    forEachRegisteredResolverOccurrence(operation, registry, bounds) { occurrence ->
        counts[occurrence.applicationKey] =
            counts.getOrDefault(occurrence.applicationKey, 0) + 1
    }
    return counts
}

private fun ObjectEngineResult.ObjectKey.canonicalFingerprint(
    bounds: ResolutionWitnessBounds,
): String =
    "${field.containingDef.name.length}:${field.containingDef.name}/" +
        "${field.name.length}:${field.name};" +
        arguments.resolutionFingerprint(field, bounds).value
