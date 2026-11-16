package viaduct.engine.runtime2.schema

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ValuesResolver
import graphql.execution.conditional.ConditionalNodeDecision
import graphql.execution.conditional.ConditionalNodes
import graphql.introspection.Introspection
import graphql.language.DirectivesContainer
import graphql.language.FragmentSpread
import graphql.language.OperationDefinition
import graphql.normalized.NormalizedDocument
import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedSelection
import graphql.normalized.NormalizedSelectionScope
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLSchema
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.deferredSelectionForest
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.toSelectionForest
import viaduct.engine.runtime2.schema.lowering.loweredFieldFromSourceCoordinate
import viaduct.graphql.schema.ViaductSchema

/**
 * One request's interpretation of an immutable ND. Caches never escape this binding environment.
 * Shared fragment descendants retain the same forest reference. Only observing a forest collects
 * its immediate value level; field children are never recursively collected by this adapter.
 */
internal class NormalizedSelectionSource(
    private val document: NormalizedDocument,
    private val sourceSchema: GraphQLSchema,
    private val schema: ViaductSchema,
    private val variables: CoercedVariables,
    private val graphQLContext: GraphQLContext,
    private val locale: Locale,
    private val keyDecoder: ((NormalizedField) -> ObjectEngineResult.Key)? = null,
    private val conditionDecoder: ((DirectivesContainer<*>) -> InclusionCondition)? = null,
    private val eraseIntrospection: Boolean = true,
    private val preserveSourceResponseKeys: Boolean = true,
) {
    private val keys = ConcurrentHashMap<NormalizedField, ObjectEngineResult.Key>()
    private val forests = ConcurrentHashMap<Scope, SelectionForest>()
    private val conditionalNodes = ConditionalNodes()

    internal val decodedFieldCount: Int get() = keys.size

    fun selections(operation: OperationDefinition): SelectionForest {
        val normalizedOperation = requireNotNull(document.operations.singleOrNull { it.source === operation }) {
            "The operation must belong to the validated NormalizedDocument"
        }
        val root = when (operation.operation) {
            OperationDefinition.Operation.QUERY -> schema.requireQueryTypeDef()
            OperationDefinition.Operation.SUBSCRIPTION -> requireNotNull(schema.subscriptionTypeDef)
            else -> requireNotNull(schema.mutationTypeDef)
        }
        val selections = if (graphQLContext.get<ConditionalNodeDecision?>(ConditionalNodeDecision::class.java) != null) {
            eagerOccurrences(normalizedOperation.selectionSetId, root.possibleObjectTypes)
        } else {
            forest(normalizedOperation.selectionSetId, root.possibleObjectTypes)
        }
        return if (operation.operation == OperationDefinition.Operation.MUTATION) selections.mutationSelections(schema, root) else selections
    }

    fun forest(
        id: Int,
        types: Set<ViaductSchema.Object>,
    ): SelectionForest {
        if (id < 0) return selectionForestOf()
        return forests.computeIfAbsent(Scope(id, types)) { scope ->
            deferredSelectionForest { collect(scope) }
        }
    }

    private fun collect(scope: Scope): SelectionForest {
        val output = mutableListOf<Selection>()
        val frames = ArrayDeque<Frame>()
        frames.addLast(frame(scope.id, scope.types, InclusionCondition.Always))
        // Fragment scopes stay at the same value level. Iteration also handles long spread chains.
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (!frame.selections.hasNext()) {
                frames.removeLast()
                continue
            }
            when (val selection = frame.selections.next()) {
                is NormalizedField -> {
                    if (eraseIntrospection && selection.source.name in Introspection.INTROSPECTION_SYSTEM_FIELDS) continue
                    val key = key(selection)
                    val children = if (selection.selectionSetId < 0) {
                        selectionForestOf()
                    } else {
                        val childType = key.field.type.baseTypeDef as ViaductSchema.CompositeTypeDef
                        forest(selection.selectionSetId, childType.possibleObjectTypes)
                    }
                    output += Selection.of(
                        key,
                        frame.types,
                        children,
                        frame.condition.and(include(selection.source)),
                        responseKey = selection.source.alias ?: if (preserveSourceResponseKeys) selection.source.name else key.field.name,
                        fieldDirectives = ParsedFieldDirectives(selection.source.directives.map { it.name }.filterNot { it in setOf("skip", "include", "defer") }),
                    )
                    SelectionForest.requireSupportedSize(output.size)
                }
                is NormalizedSelectionScope -> {
                    val types = selection.typeCondition?.let {
                        frame.types intersect (schema.requireType(it) as ViaductSchema.CompositeTypeDef).possibleObjectTypes
                    } ?: frame.types
                    frames.addLast(frame(selection.selectionSetId, types, frame.condition.and(include(selection.source))))
                }
            }
        }
        return output.toSelectionForest()
    }

    // Effectful inclusion hooks retain source occurrence evaluation rather than sharing decisions.
    // This still reads ND references; only this exceptional path eagerly visits descendants.
    private fun eagerOccurrences(
        id: Int,
        types: Set<ViaductSchema.Object>
    ): SelectionForest {
        if (id < 0) return selectionForestOf()
        val output = mutableListOf<Selection>()
        document.selectionSets[id].selections.forEach { selection ->
            when (selection) {
                is NormalizedField -> {
                    if (eraseIntrospection && selection.source.name in Introspection.INTROSPECTION_SYSTEM_FIELDS) return@forEach
                    val key = decodeKey(selection)
                    val children = if (selection.selectionSetId < 0) {
                        selectionForestOf()
                    } else {
                        eagerOccurrences(selection.selectionSetId, (key.field.type.baseTypeDef as ViaductSchema.CompositeTypeDef).possibleObjectTypes)
                    }
                    output += Selection.of(
                        key,
                        types,
                        children,
                        include(selection.source),
                        selection.source.resultKey,
                        ParsedFieldDirectives(selection.source.directives.map { it.name }.filterNot { it in setOf("skip", "include", "defer") })
                    )
                }
                is NormalizedSelectionScope -> {
                    val scopeTypes = selection.typeCondition?.let { types intersect (schema.requireType(it) as ViaductSchema.CompositeTypeDef).possibleObjectTypes } ?: types
                    val spreadCondition = if (selection.source is FragmentSpread) include(selection.source) else null
                    val children = eagerOccurrences(selection.selectionSetId, scopeTypes)
                    val condition = spreadCondition ?: include(selection.source)
                    children.guardedBy(condition).forEach(output::add)
                }
            }
        }
        return output.toSelectionForest()
    }

    private fun frame(
        id: Int,
        types: Set<ViaductSchema.Object>,
        condition: InclusionCondition
    ): Frame = Frame(document.selectionSets[id].selections.iterator(), types, condition)

    private fun include(source: DirectivesContainer<*>): InclusionCondition =
        conditionDecoder?.invoke(source)
            ?: if (conditionalNodes.shouldInclude(source, variables.toMap(), sourceSchema, graphQLContext)) {
                InclusionCondition.Always
            } else {
                InclusionCondition.Never
            }

    fun key(field: NormalizedField): ObjectEngineResult.Key = keys.computeIfAbsent(field, ::decodeKey)

    private fun decodeKey(field: NormalizedField): ObjectEngineResult.Key {
        keyDecoder?.let { return it(field) }
        val parent = sourceSchema.getType(field.parentTypeName) as GraphQLCompositeType
        val definition = requireNotNull(Introspection.getFieldDef(sourceSchema, parent, field.source.name))
        val values = ValuesResolver.getArgumentValues(
            sourceSchema.codeRegistry,
            definition.arguments,
            field.source.arguments,
            variables,
            graphQLContext,
            locale,
        )
        val arguments = definition.arguments.mapNotNull { argument ->
            if (argument.name in values) {
                argument.name to decodeExternalInputValue(argument.type, values[argument.name], schema)
            } else {
                null
            }
        }.toMap()
        val canonical = schema.loweredFieldFromSourceCoordinate(field.parentTypeName, field.source.name)
        return ObjectEngineResult.Key.of(canonical, Arguments.of(canonical, arguments))
    }

    private data class Scope(val id: Int, val types: Set<ViaductSchema.Object>)

    private class Frame(
        val selections: Iterator<NormalizedSelection>,
        val types: Set<ViaductSchema.Object>,
        val condition: InclusionCondition,
    )
}
