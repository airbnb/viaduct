package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineSchema
import viaduct.engine.runtime.execution.QueryPlan.Fragments

/**
 * Spec section 6.3.2, CollectSubfields.
 * Collect subfields in each parent's defer context, union occurrences by response key,
 * and return new defer usages.
 *
 * For example, when collecting the selection set under `user`:
 *
 * ```graphql
 * {
 *   user {
 *     id
 *   }
 *   ... @defer(label: "A") {
 *     user {
 *       name
 *       ... @defer(label: "B") {
 *         bio
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * `id` has no defer context, `name` inherits defer A, and `bio` gets defer B with parent A.
 */
internal object CollectSubfields {
    operator fun invoke(
        schema: EngineSchema,
        objectType: GraphQLObjectType,
        fields: List<FieldDetails>,
        variables: CoercedVariables,
        fragments: Fragments,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean,
        incrementalExecutionEnabled: Boolean,
        collectFields: CollectFields,
    ): CollectFields.Result {
        val collectedFieldsMap = linkedMapOf<String, MutableSet<FieldDetails>>()
        val newDeferUsages = mutableListOf<DeferUsage>()
        for ((field, deferUsage) in fields) {
            val selectionSet = field.selectionSet ?: continue
            if (selectionSet.selections.isEmpty()) continue
            val result = collectFields(
                schema,
                selectionSet,
                variables,
                objectType,
                fragments,
                fieldRssOriginFilteringKillSwitchEnabled,
                incrementalExecutionEnabled,
                deferUsage,
            )
            // A fragment must be collected for each parent occurrence because it may inherit different defer contexts.
            // Each parent gets fresh visitation state, so user { ...F } user { ...F } can collect the same field twice.
            // Deduplicate equal field/defer-context pairs across those results, preserving encounter order;
            // occurrences in different defer contexts remain distinct.
            for ((responseName, subfields) in result.collectedFieldsMap) {
                collectedFieldsMap
                    .getOrPut(responseName) { linkedSetOf() }
                    .addAll(subfields.occurrences)
            }
            newDeferUsages.addAll(result.newDeferUsages)
        }
        return CollectFields.Result(
            collectedFieldsMap.mapValues { (_, fields) ->
                CollectedField(fields.toList(), schema.schema)
            },
            newDeferUsages,
        )
    }
}
