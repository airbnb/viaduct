package viaduct.engine.runtime.select

import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName
import viaduct.utils.string.sha256Hash

/** Generate a hash string based on the selections in this EngineSelectionSet */
fun EngineSelectionSet.hash(): String = this.printAsFieldSet().sha256Hash()

/**
 * Render this EngineSelectionSet into a graphql-java [graphql.language.Document].
 * Any fragment spreads that are used by this EngineSelectionSet will be converted into inline fragments
 */
fun EngineSelectionSet.toDocument(fragmentName: String = EntryPointFragmentName): Document =
    if (isEmpty()) {
        Document(emptyList())
    } else {
        Document(listOf(toFragmentDefinition(fragmentName)))
    }

/**
 * Render this EngineSelectionSet into a graphql-java [graphql.language.FragmentDefinition].
 * Any fragment spreads that are used by this EngineSelectionSet will be converted into inline fragments.
 */
fun EngineSelectionSet.toFragmentDefinition(fragmentName: String = EntryPointFragmentName): FragmentDefinition =
    FragmentDefinition.newFragmentDefinition()
        .name(fragmentName)
        .typeCondition(TypeName(type))
        .selectionSet(toSelectionSet())
        .build()

/**
 * Recursively returns all field coordinates transitively selected by
 * this [EngineSelectionSet].
 */
fun EngineSelectionSet.allCoords(schema: ViaductSchema): Set<Coordinate> =
    buildSet {
        fun visit(selectionSet: EngineSelectionSet) {
            for (sel in selectionSet.selections()) {
                val concreteParentTypes = concreteObjectTypeNames(sel.typeCondition, schema)
                concreteParentTypes.forEach { objectTypeName -> add(objectTypeName to sel.fieldName) }

                for (parentType in concreteParentTypes) {
                    val fieldDef = schema.schema.getFieldDefinition((parentType to sel.fieldName).gj)
                    if (GraphQLTypeUtil.unwrapAll(fieldDef.type) !is GraphQLCompositeType) continue

                    visit(selectionSet.selectionSetForField(parentType, sel.fieldName))
                }
            }
        }

        visit(this@allCoords)
    }

/**
 * Returns all concrete object type names reachable through composite selections in this
 * [EngineSelectionSet].
 */
fun EngineSelectionSet.reachableObjects(schema: ViaductSchema): Set<String> =
    buildSet {
        fun visit(selectionSet: EngineSelectionSet) {
            for (sel in selectionSet.selections()) {
                val concreteParentTypes = concreteObjectTypeNames(sel.typeCondition, schema)

                for (parentType in concreteParentTypes) {
                    val fieldDef = schema.schema.getFieldDefinition((parentType to sel.fieldName).gj)
                    if (GraphQLTypeUtil.unwrapAll(fieldDef.type) !is GraphQLCompositeType) continue

                    val nested = selectionSet.selectionSetForField(parentType, sel.fieldName)
                    concreteObjectTypeNames(nested.type, schema).forEach(::add)
                    visit(nested)
                }
            }
        }

        visit(this@reachableObjects)
    }

/** Returns a [Coordinate] representation of this [EngineSelection]. */
val EngineSelection.coord: Coordinate get() = this.typeCondition to this.fieldName

/**
 * Returns the set of [Coordinate]s (typeCondition to fieldName) for all selections in this
 * [EngineSelectionSet]. Aliases use the underlying field name, not the alias.
 */
val EngineSelectionSet.coordinates: Set<Coordinate>
    get() =
        selections()
            .map { sel -> sel.coord }
            .toSet()

/**
 * Returns a [GraphQLTypeRelation.Relation] describing the relationship between this
 * [EngineSelectionSet]'s type and the type condition of the provided [selection].
 */
fun EngineSelectionSet.relation(
    schema: ViaductSchema,
    selection: EngineSelection
): GraphQLTypeRelation.Relation {
    val ssType = schema.schema.getTypeAs<GraphQLCompositeType>(type)
    val selectionType = schema.schema.getTypeAs<GraphQLCompositeType>(selection.typeCondition)
    return schema.rels.relation(ssType, selectionType)
}

private fun concreteObjectTypeNames(
    typeName: String,
    schema: ViaductSchema
): List<String> {
    val type = schema.schema.getTypeAs<GraphQLCompositeType>(typeName)
    return schema.rels.possibleObjectTypes(type).toList().map { it.name }
}
