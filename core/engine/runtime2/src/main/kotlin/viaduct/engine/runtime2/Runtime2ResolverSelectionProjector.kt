package viaduct.engine.runtime2

import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverType
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.select.EngineSelectionSetImpl
import viaduct.graphql.utils.ParsedSelections

/** Projects selections to fields owned by the current resolver, stopping at dispatcher boundaries. */
// TODO: After integrating engine2 into Treehouse, consolidate this with runtime's pre-existing
// ResolverSelectionProjector implementation.
internal class Runtime2ResolverSelectionProjector(
    private val schema: EngineSchema,
    private val dispatcherRegistry: DispatcherRegistry,
) {
    private val nonBoundaryObjectTypes = ConcurrentHashMap<String, List<GraphQLObjectType>>()

    fun project(
        selectionSet: EngineSelectionSet,
        resolverType: ResolverType,
    ): EngineSelectionSet {
        val type = requireCompositeType(selectionSet.type)
        return when (type) {
            is GraphQLObjectType -> {
                if (isNodeBoundary(type, resolverType, topLevel = true)) {
                    emptySelectionSet(type, selectionSet.variables)
                } else {
                    projectConcrete(selectionSet, type, resolverType, topLevel = true)
                }
            }
            else -> projectAbstract(selectionSet, type, resolverType, topLevel = true)
        }
    }

    private fun projectConcrete(
        selectionSet: EngineSelectionSet,
        parentType: GraphQLObjectType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): EngineSelectionSet {
        val concrete = selectionSet.selectionSetForType(parentType.name)
        val fields =
            concrete.toSelectionSet().applicableFields(parentType).mapNotNull { field ->
                project(field, parentType, resolverType, topLevel, concrete.variables)
            }
        return selectionSet(parentType, SelectionSet(fields), concrete.variables)
    }

    private fun projectAbstract(
        selectionSet: EngineSelectionSet,
        type: GraphQLCompositeType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): EngineSelectionSet {
        val branches =
            nonBoundaryObjectTypes(type, resolverType, topLevel).mapNotNull { objectType ->
                val concrete = selectionSet.selectionSetForType(objectType.name)
                if (concrete.isEmpty() && concrete.conditionallyExcludedResultKeys().isEmpty()) {
                    return@mapNotNull null
                }
                val projected = projectConcrete(concrete, objectType, resolverType, topLevel)
                InlineFragment.newInlineFragment()
                    .typeCondition(TypeName(objectType.name))
                    .selectionSet(projected.validSelectionSet())
                    .build()
            }
        return selectionSet(type, SelectionSet(branches), selectionSet.variables)
    }

    private fun project(
        field: Field,
        parentType: GraphQLObjectType,
        resolverType: ResolverType,
        topLevel: Boolean,
        variables: Map<String, Any?>,
    ): Field? {
        if (field.name.startsWith("__")) return null
        if (isBoundary(parentType.name to field.name)) return null
        if (topLevel && resolverType == ResolverType.NODE && field.name == "id") return null

        val children = field.selectionSet ?: return field
        val fieldDefinition = requireNotNull(parentType.getFieldDefinition(field.name))
        val outputType = GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as? GraphQLCompositeType
            ?: return field
        if (outputType is GraphQLObjectType && isBoundary(outputType.name)) return null

        val childSelectionSet = selectionSet(outputType, children, variables)
        val projectedChild =
            when (outputType) {
                is GraphQLObjectType -> {
                    if (isNodeBoundary(outputType, resolverType, topLevel = false)) {
                        emptySelectionSet(outputType, variables)
                    } else {
                        projectConcrete(childSelectionSet, outputType, resolverType, topLevel = false)
                    }
                }
                else -> projectAbstract(childSelectionSet, outputType, resolverType, topLevel = false)
            }
        if (outputType !is GraphQLObjectType && projectedChild.isEmpty()) return null
        return field.transform { builder -> builder.selectionSet(projectedChild.validSelectionSet()) }
    }

    private fun nonBoundaryObjectTypes(
        type: GraphQLCompositeType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): List<GraphQLObjectType> =
        if (topLevel && resolverType == ResolverType.NODE) {
            schema.rels.possibleObjectTypes(type).toList()
        } else {
            nonBoundaryObjectTypes.computeIfAbsent(type.name) {
                schema.rels.possibleObjectTypes(type).filterNot { isBoundary(it.name) }
            }
        }

    private fun SelectionSet.applicableFields(type: GraphQLObjectType): List<Field> =
        selections.flatMap { selection ->
            when (selection) {
                is Field -> listOf(selection)
                is InlineFragment -> {
                    val condition = selection.typeCondition?.name?.let(::requireCompositeType)
                    if (condition == null || schema.rels.isSpreadable(type, condition)) {
                        selection.selectionSet.applicableFields(type)
                    } else {
                        emptyList()
                    }
                }
                is FragmentSpread -> error("EngineSelectionSet.toSelectionSet() must inline fragment spreads")
                else -> error("Unsupported selection ${selection::class.qualifiedName}")
            }
        }

    private fun EngineSelectionSet.validSelectionSet(): SelectionSet =
        if (isEmpty()) {
            SelectionSet(listOf(Field.newField("__typename").build()))
        } else {
            toSelectionSet()
        }

    private fun emptySelectionSet(
        type: GraphQLCompositeType,
        variables: Map<String, Any?>,
    ): EngineSelectionSet = selectionSet(type, SelectionSet(emptyList()), variables)

    private fun selectionSet(
        type: GraphQLCompositeType,
        selections: SelectionSet,
        variables: Map<String, Any?>,
    ): EngineSelectionSet =
        EngineSelectionSetImpl.create(
            parsedSelections =
                ParsedSelections(
                    typeName = type.name,
                    selections = selections,
                    fragmentMap = emptyMap(),
                ),
            variables = variables,
            schema = schema,
        )

    private fun requireCompositeType(name: String): GraphQLCompositeType =
        requireNotNull(schema.schema.getType(name) as? GraphQLCompositeType) {
            "Selection type $name is not a GraphQL composite type"
        }

    private fun isNodeBoundary(
        type: GraphQLObjectType,
        resolverType: ResolverType,
        topLevel: Boolean,
    ): Boolean = (!topLevel || resolverType != ResolverType.NODE) && isBoundary(type.name)

    private fun isBoundary(coordinate: Coordinate): Boolean {
        return dispatcherRegistry.getFieldResolverDispatcher(coordinate.first, coordinate.second) != null
    }

    private fun isBoundary(typeName: String): Boolean {
        return dispatcherRegistry.getNodeResolverDispatcher(typeName) != null
    }
}
