package viaduct.engine.runtime2.contract

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.test.assertEquals
import viaduct.engine.runtime2.model.Arguments
import viaduct.graphql.schema.ViaductSchema

/** Records invocation arguments without retaining materialized input graphs. */
open class ResolverApplicationArguments : viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver() {
    private val argumentsByField = linkedMapOf<ViaductSchema.Field, MutableList<Arguments.Resolved>>()

    override fun onResolverInvocation(observation: viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation) {
        super.onResolverInvocation(observation)
        synchronized(argumentsByField) {
            argumentsByField.getOrPut(observation.field, ::mutableListOf).add(observation.arguments)
        }
    }

    fun arguments(field: ViaductSchema.Field): List<Arguments.Resolved> = synchronized(argumentsByField) { argumentsByField[field].orEmpty().toList() }

    fun all(): Map<ViaductSchema.Field, List<Arguments.Resolved>> = synchronized(argumentsByField) { argumentsByField.mapValues { (_, values) -> values.toList() } }
}

internal fun ResolverApplicationArguments.assertApplicationCount(
    field: ViaductSchema.Field,
    expected: Int,
) {
    assertEquals(expected, arguments(field).size)
}

/** Asserts the complete field-to-arguments application ledger. */
internal fun ResolverApplicationArguments.assertApplications(expected: Map<ViaductSchema.Field, List<Map<String, Any?>>>) {
    assertEquals(
        expected.mapValues { (field, applications) ->
            applications.map { arguments -> Arguments.Resolved.of(field, arguments) }
        },
        all(),
    )
}

internal fun ResolverApplicationArguments.assertArguments(
    field: ViaductSchema.Field,
    vararg expected: Map<String, Any?>,
) {
    arguments(field).shouldContainExactlyInAnyOrder(
        expected.map { arguments -> Arguments.Resolved.of(field, arguments) },
    )
}

/**
 * Asserts which argument tuples occurred without constraining duplicate application counts.
 */
internal fun ResolverApplicationArguments.assertDistinctArguments(
    field: ViaductSchema.Field,
    vararg expected: Map<String, Any?>,
) {
    assertEquals(
        expected.mapTo(linkedSetOf()) { arguments ->
            Arguments.Resolved.of(field, arguments)
        },
        arguments(field).toSet(),
    )
}
