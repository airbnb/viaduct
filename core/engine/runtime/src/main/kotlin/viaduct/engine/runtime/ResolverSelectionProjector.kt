package viaduct.engine.runtime

import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.select.EngineSelectionSetImpl
import viaduct.engine.runtime.select.FieldSelection
import viaduct.engine.runtime.select.ProjectedEngineSelectionSet
import viaduct.engine.runtime.select.TypedFieldSelection

/** Projects a runtime selection set to fields owned by the current resolver. */
internal class ResolverSelectionProjector(
    private val schema: ViaductSchema,
    private val dispatcherRegistry: DispatcherRegistry,
) {
    private val nonBoundaryObjectTypes = ConcurrentHashMap<String, List<GraphQLObjectType>>()

    fun project(
        selectionSet: EngineSelectionSet,
        resolverType: ResolverType,
    ): EngineSelectionSet =
        when (selectionSet) {
            is ResolverOwnedSelectionProjectable ->
                selectionSet.projectOwnedSelections(dispatcherRegistry, resolverType)
            else ->
                project(selectionSet.asImpl(), resolverType, topLevel = true)
        }

    private fun project(
        selectionSet: EngineSelectionSetImpl,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): EngineSelectionSetImpl =
        when (val type = selectionSet.def) {
            is GraphQLObjectType -> {
                if (isNodeBoundary(type, resolverType, topLevel)) {
                    selectionSet.withProjectedSelections(emptyList())
                } else {
                    project(selectionSet, type, resolverType, topLevel)
                }
            }
            else -> projectAbstract(selectionSet, resolverType, topLevel)
        }

    private fun project(
        selectionSet: EngineSelectionSetImpl,
        parentType: GraphQLObjectType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): EngineSelectionSetImpl =
        selectionSet.withProjectedSelections(
            selectionSet.typedSelections().mapNotNull {
                project(it, parentType, resolverType, topLevel)
            }
        )

    private fun projectAbstract(
        selectionSet: EngineSelectionSetImpl,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): EngineSelectionSetImpl {
        val branches = nonBoundaryObjectTypes(selectionSet.def, resolverType, topLevel)
            .mapNotNull { objectType ->
                val concreteSelectionSet = selectionSet.selectionSetForType(objectType.name).asImpl()
                if (
                    concreteSelectionSet.isEmpty() &&
                    concreteSelectionSet.conditionallyExcludedResultKeys().isEmpty()
                ) {
                    return@mapNotNull null
                }

                InlineFragment.newInlineFragment()
                    .typeCondition(TypeName(objectType.name))
                    .selectionSet(
                        project(
                            concreteSelectionSet,
                            objectType,
                            resolverType,
                            topLevel,
                        ).validSelectionSet()
                    )
                    .build()
            }
        val projected = selectionSet.withProjectedSelections(emptyList())
        return if (branches.isEmpty()) projected else projected + SelectionSet(branches)
    }

    private fun project(
        selected: TypedFieldSelection,
        parentType: GraphQLObjectType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): FieldSelection? {
        if (selected.fieldName.startsWith("__")) return null
        if (isBoundary(parentType.name to selected.fieldName)) return null
        if (
            topLevel &&
            resolverType == ResolverType.NODE &&
            selected.fieldName == "id"
        ) {
            return null
        }
        if (!selected.hasSubselections) return selected.selection

        val outputType = selected.outputType(parentType)
        if (outputType is GraphQLObjectType && isBoundary(outputType.name)) return null

        val projectedChild = project(
            selected.childSelectionSet(outputType),
            resolverType,
            topLevel = false,
        )
        return if (outputType !is GraphQLObjectType && projectedChild.isEmpty()) {
            null
        } else {
            selected.selection.withChildren(projectedChild.validSelectionSet())
        }
    }

    private fun nonBoundaryObjectTypes(
        type: GraphQLCompositeType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): List<GraphQLObjectType> {
        if (topLevel && resolverType == ResolverType.NODE) {
            return schema.rels.possibleObjectTypes(type).toList()
        }
        return nonBoundaryObjectTypes.computeIfAbsent(type.name) {
            schema.rels.possibleObjectTypes(type)
                .filterNot { isBoundary(it.name) }
        }
    }

    private fun isNodeBoundary(
        type: GraphQLObjectType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): Boolean =
        (!topLevel || resolverType != ResolverType.NODE) &&
            isBoundary(type.name)

    private fun isBoundary(coordinate: Coordinate): Boolean =
        dispatcherRegistry.getFieldResolverDispatcher(
            coordinate.first,
            coordinate.second
        ) != null

    private fun isBoundary(typeName: String): Boolean = dispatcherRegistry.getNodeResolverDispatcher(typeName) != null

    private fun EngineSelectionSetImpl.validSelectionSet(): SelectionSet =
        if (selections.isEmpty()) {
            SelectionSet(listOf(Field.newField("__typename").build()))
        } else if (def is GraphQLObjectType) {
            SelectionSet(selections.map { it.field })
        } else {
            toSelectionSet()
        }

    private fun FieldSelection.withChildren(children: SelectionSet): FieldSelection = copy(field = field.transform { it.selectionSet(children) })

    private fun EngineSelectionSet.asImpl(): EngineSelectionSetImpl =
        when (this) {
            is EngineSelectionSetImpl -> this
            is ProjectedEngineSelectionSet -> sourceImpl
            else -> error("Cannot project ${this::class.qualifiedName}")
        }
}

/** Runtime selection representations that can preserve their native projection metadata. */
internal interface ResolverOwnedSelectionProjectable {
    fun projectOwnedSelections(
        dispatcherRegistry: DispatcherRegistry,
        resolverType: ResolverType,
    ): EngineSelectionSet
}
