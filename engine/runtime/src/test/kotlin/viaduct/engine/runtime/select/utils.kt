package viaduct.engine.runtime.select

import graphql.language.Field
import graphql.schema.GraphQLCompositeType
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.graphql.utils.GraphQLTypeRelation

/**
 * Return a Map of selected [Field]s of a EngineSelectionSet that are defined on the selected
 * type.
 *
 * The keys in this map are field names and always correspond to a field defined on the
 * current [type].
 *
 * The [Field] values in this map are not merged -- values in this map may have different
 * aliases, arguments, query directives, or originate from fragments with different
 * type conditions. Field values in this map are guaranteed to be defined on the underlying
 * GraphQL type that this EngineSelectionSet is on.
 */
internal val EngineSelectionSetImpl.typeFields: Map<String, List<Field>>
    get() {
        return selections
            .filter { (_, t) ->
                val type = ctx.schema.schema.getTypeAs<GraphQLCompositeType>(this.type)
                val rel = ctx.schema.rels.relationUnwrapped(type, t)
                rel == GraphQLTypeRelation.NarrowerThan || rel == GraphQLTypeRelation.Same
            }
            .groupBy({ it.field.name }, { it.field })
    }

/**
 * A [typeFields] variant that works with any [EngineSelectionSet] implementation,
 * including [ProjectedEngineSelectionSet].
 *
 * Kotlin resolves extensions by static receiver type, so this is used when the receiver
 * is typed as [EngineSelectionSet] (e.g., after [EngineSelectionSet.selectionSetForType]
 * which now returns [EngineSelectionSet]). When the receiver is typed as
 * [EngineSelectionSetImpl], the more specific extension above is used instead.
 *
 * The keys are field names identical to the [EngineSelectionSetImpl] variant.
 * Test assertions only use [Map.keys], so the different value type is irrelevant.
 */
internal val EngineSelectionSet.typeFields: Map<String, List<EngineSelection>>
    get() = selections()
        .filter { sel -> containsField(type, sel.fieldName) }
        .groupBy { it.fieldName }
