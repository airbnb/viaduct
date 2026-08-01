package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.Directive
import graphql.language.Document
import graphql.language.Field as GJField
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FieldDirectives
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentSource
import viaduct.engine.api.fragment.FragmentVariables
import viaduct.engine.api.gj
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.SelectionsParserUtils.EntryPointFragmentName

/** An [EngineSelectionSet] projection of a [QueryPlan] */
internal data class ExecutionSelectionSet(
    private val ctx: Ctx,
    private val projectionType: GraphQLCompositeType,
    private val source: QueryPlan.SelectionSet,
    private val requestsBaseType: Boolean,
) : EngineSelectionSet {
    data class Ctx(
        val schema: ViaductSchema,
        val fragments: QueryPlan.Fragments,
        val variables: Map<String, Any?>,
        val graphQLContext: GraphQLContext,
        val locale: Locale = Locale.getDefault(),
    ) {
        val coercedVariables: CoercedVariables = CoercedVariables.of(variables)
        val constraintsCtxWithoutTypes: Constraints.Ctx = Constraints.Ctx(coercedVariables, null)

        fun constraintsCtxFor(type: GraphQLCompositeType): Constraints.Ctx = Constraints.Ctx(coercedVariables, schema.rels.possibleObjectTypes(type))
    }

    override val schema: ViaductSchema get() = ctx.schema
    override val variables: Map<String, Any?> get() = ctx.variables
    override val type: String get() = projectionType.name

    private val projection: ProjectedSelectionSet by lazy {
        collectSelectionSet(source, source.parentType, emptySet(), requestsBaseType)
    }
    private val fieldSelections: List<FieldSelection> by lazy {
        projection.fields.filter { it.isSpreadableTo(projectionType) }
    }

    override fun selections(): List<EngineSelection> = fieldSelections.map { it.toEngineSelection() }

    override fun conditionallyExcludedResultKeys(): Set<String> =
        projection.excludedResultKeysByType
            .filter { (_, typeConditions) -> typeConditions.any { schema.rels.isSpreadable(it, projectionType) } }
            .keys

    override fun traversableSelections(): List<EngineSelection> =
        fieldSelections.mapNotNull { selection ->
            val selectionType = GraphQLTypeUtil.unwrapAll(fieldDefinition(selection).type)
            if (selectionType is GraphQLCompositeType) selection.toEngineSelection() else null
        }

    override fun toSelectionSet(): GJSelectionSet {
        val selections = fieldSelections
            .groupBy { it.typeCondition }
            .mapNotNull { (type, selections) ->
                val fields = selections.mapNotNull { it.toAstField() }
                if (fields.isEmpty()) return@mapNotNull null

                GJInlineFragment.newInlineFragment()
                    .typeCondition(TypeName(type.name))
                    .selectionSet(GJSelectionSet(fields))
                    .build()
            }
        return GJSelectionSet(selections)
    }

    override fun addVariables(variables: Map<String, Any?>): EngineSelectionSet {
        this.variables.forEach { (key, _) ->
            require(!variables.containsKey(key)) {
                "cannot rebind variable with key $key"
            }
        }
        return copy(ctx = ctx.copy(variables = this.variables + variables))
    }

    override fun toFragment(): Fragment =
        Fragment(
            FragmentSource.create(toDocument()),
            FragmentVariables.fromMap(variables),
        )

    override fun toNodelikeSelectionSet(
        nodeFieldName: String,
        arguments: List<Argument>
    ): EngineSelectionSet {
        val isNode = type == "Node"
        val implementsNode = (projectionType as? GraphQLImplementingType)?.interfaces?.any { it.name == "Node" } == true
        require(isNode || implementsNode) {
            "Cannot call toNodelikeSelectionSet for a type that does not implement Node: ${projectionType.name}"
        }

        val queryType = schema.schema.queryType
        if (isEmpty()) {
            return copy(
                projectionType = queryType,
                source = QueryPlan.SelectionSet.empty(queryType),
                requestsBaseType = false,
            )
        }

        val nodeField = GJField.newField(nodeFieldName)
            .arguments(arguments)
            .selectionSet(toSelectionSet())
            .build()
        val nodeSelection = QueryPlan.Field(
            resultKey = nodeFieldName,
            constraints = Constraints.Unconstrained,
            field = nodeField,
            selectionSet = toQueryPlanSelectionSet(),
            childPlans = emptyList(),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
        )
        return copy(
            projectionType = queryType,
            source = QueryPlan.SelectionSet(queryType, nodeSelection),
            requestsBaseType = true,
        )
    }

    private fun toQueryPlanSelectionSet(): QueryPlan.SelectionSet =
        QueryPlan.SelectionSet(
            parentType = projectionType,
            selections = fieldSelections
                .groupBy { it.typeCondition }
                .map { (type, selections) ->
                    QueryPlan.InlineFragment(
                        selectionSet = QueryPlan.SelectionSet(type, selections.map { it.selection }),
                        constraints = Constraints.Unconstrained.narrowToImpls(type, schema),
                        inlineFragment = GJInlineFragment.newInlineFragment()
                            .typeCondition(TypeName(type.name))
                            .selectionSet(GJSelectionSet(emptyList()))
                            .build(),
                    )
                },
            conditionallyExcludedCoordinates = projection.excludedResultKeysByType
                .flatMapTo(mutableSetOf()) { (resultKey, types) ->
                    types.map { it.name to resultKey }
                },
        )

    override fun printAsFieldSet(): String =
        toSelectionSet().selections.joinToString("\n") {
            AstPrinter.printAstCompact(it)
        }

    override fun containsField(
        type: String,
        field: String
    ): Boolean = findSelection(type) { it.fieldName == field } != null

    override fun containsSelection(
        type: String,
        selectionName: String
    ): Boolean = findSelection(type) { it.resultKey == selectionName } != null

    override fun resolveSelection(
        type: String,
        selectionName: String
    ): EngineSelection {
        val selection = findSelection(type) { it.resultKey == selectionName }
        return requireNotNull(selection?.toEngineSelection()) {
            "No selection found for selectionName `$selectionName`"
        }
    }

    override fun requestsType(type: String): Boolean {
        val requestedType = compositeType(type)
        return projection.requestedTypes.any {
            schema.rels.isSpreadable(it, projectionType) &&
                when (schema.rels.relationUnwrapped(it, requestedType)) {
                    GraphQLTypeRelation.Same, GraphQLTypeRelation.NarrowerThan -> true
                    else -> false
                }
        }
    }

    override fun selectionSetForField(
        type: String,
        field: String
    ): EngineSelectionSet {
        val selectionType = compositeType(type)
        val subselectionType = fieldDefinition(type, field).compositeOutputType(type, field)
        return subselections(selectionType, subselectionType) { it.fieldName == field }
    }

    override fun selectionSetForSelection(
        type: String,
        selectionName: String
    ): EngineSelectionSet {
        val selectionType = fieldsContainer(type)
        val fieldName = resolveSelection(type, selectionName).fieldName
        val subselectionType = fieldDefinition(type, fieldName).compositeOutputType(type, fieldName)
        return subselections(selectionType, subselectionType) { it.resultKey == selectionName }
    }

    override fun selectionSetForType(type: String): EngineSelectionSet {
        val nextType = compositeType(type)
        if (nextType == projectionType) return this
        require(schema.rels.isSpreadable(projectionType, nextType)) {
            "Selections of type $type are not spreadable in type ${projectionType.name}"
        }
        return copy(projectionType = nextType)
    }

    override fun isEmpty(): Boolean = fieldSelections.isEmpty()

    override fun isTransitivelyEmpty(): Boolean {
        if (isEmpty()) return true
        return fieldSelections
            .groupBy { it.fieldName }
            .all { (_, selections) ->
                val selection = selections.first()
                val outputType = GraphQLTypeUtil.unwrapAll(fieldDefinition(selection).type)
                outputType is GraphQLCompositeType &&
                    selectionSetForField(selection.typeCondition.name, selection.fieldName).isTransitivelyEmpty()
            }
    }

    override fun argumentsOfSelection(
        type: String,
        selectionName: String
    ): Map<String, Any?>? =
        findSelection(type) { it.resultKey == selectionName }
            ?.let { selection ->
                ValuesResolver.getArgumentValues(
                    schema.schema.codeRegistry,
                    fieldDefinition(type, selection.fieldName).arguments,
                    selection.arguments,
                    ctx.coercedVariables,
                    ctx.graphQLContext,
                    ctx.locale,
                )
            }

    override fun fieldDirectivesOfSelection(
        type: String,
        selectionName: String
    ): FieldDirectives? =
        findSelection(type) { it.resultKey == selectionName }
            ?.let { FieldDirectivesImpl(it.directives, ctx) }

    private fun collectSelectionSet(
        selectionSet: QueryPlan.SelectionSet,
        currentType: GraphQLCompositeType,
        spreadFragments: Set<String>,
        requestsCurrentType: Boolean = true,
        inheritedConstraints: Constraints = Constraints.Unconstrained,
    ): ProjectedSelectionSet {
        val fields = mutableListOf<FieldSelection>()
        val requestedTypes = mutableSetOf<GraphQLCompositeType>()
        val excluded = selectionSet.conditionallyExcludedCoordinates
            .groupBy({ it.second }, { compositeType(it.first) })
            .mapValuesTo(mutableMapOf()) { (_, types) -> types.toMutableSet() }
        if (requestsCurrentType) requestedTypes += currentType

        fun add(other: ProjectedSelectionSet) {
            fields += other.fields
            requestedTypes += other.requestedTypes
            other.excludedResultKeysByType.forEach { (key, types) ->
                excluded.getOrPut(key) { mutableSetOf() } += types
            }
        }

        for (selection in selectionSet.selections) {
            if (selection.isDroppedByDirectives()) {
                collectDropped(currentType, selection, excluded)
                continue
            }

            when (selection) {
                is QueryPlan.CollectedField ->
                    fields += CollectedFieldSelection(
                        currentType,
                        selection,
                        inheritedConstraints.and(selection.constraints),
                    )
                is QueryPlan.Field ->
                    fields += QueryPlanFieldSelection(
                        currentType,
                        selection,
                        inheritedConstraints.and(selection.constraints),
                    )
                is QueryPlan.InlineFragment -> {
                    val fragmentType = selection.inlineFragment?.typeCondition?.name
                        ?.let(::compositeType)
                        ?: currentType
                    add(
                        collectSelectionSet(
                            selection.selectionSet,
                            fragmentType,
                            spreadFragments,
                            inheritedConstraints = inheritedConstraints,
                        )
                    )
                }
                is QueryPlan.FragmentSpread -> {
                    if (selection.name in spreadFragments) continue
                    val fragment = requireNotNull(ctx.fragments[selection.name]) {
                        "Fragment `${selection.name}` is not defined"
                    }
                    val fragmentType = compositeType(fragment.gjDef.typeCondition.name)
                    add(
                        collectSelectionSet(
                            fragment.selectionSet,
                            fragmentType,
                            spreadFragments + selection.name,
                            inheritedConstraints = inheritedConstraints.and(selection.constraints),
                        )
                    )
                }
            }
        }

        return ProjectedSelectionSet(fields, requestedTypes, excluded)
    }

    private fun collectDropped(
        currentType: GraphQLCompositeType,
        selection: QueryPlan.Selection,
        excluded: MutableMap<String, MutableSet<GraphQLCompositeType>>,
    ) {
        when (selection) {
            is QueryPlan.CollectedField ->
                excluded.getOrPut(selection.responseKey) { mutableSetOf() } += currentType
            is QueryPlan.Field ->
                excluded.getOrPut(selection.resultKey) { mutableSetOf() } += currentType
            is QueryPlan.InlineFragment -> {
                val fragmentType = selection.inlineFragment?.typeCondition?.name
                    ?.let(::compositeType)
                    ?: currentType
                selection.selectionSet.selections.forEach { collectDropped(fragmentType, it, excluded) }
            }
            is QueryPlan.FragmentSpread -> {
                val fragment = ctx.fragments[selection.name] ?: return
                val fragmentType = compositeType(fragment.gjDef.typeCondition.name)
                fragment.selectionSet.selections.forEach { collectDropped(fragmentType, it, excluded) }
            }
        }
    }

    private fun subselections(
        selectionType: GraphQLCompositeType,
        subselectionType: GraphQLCompositeType,
        match: (FieldSelection) -> Boolean,
    ): EngineSelectionSet {
        require(schema.rels.isSpreadable(projectionType, selectionType)) {
            "Selections of type ${selectionType.name} are not spreadable in type ${projectionType.name}"
        }
        val childSelections = fieldSelections
            .filter { match(it) && it.isSelectableOn(selectionType) }
        val childSelectionSets = childSelections.mapNotNull { it.selectionSet }
        val childSelectionSet = QueryPlan.SelectionSet(
            parentType = subselectionType,
            selections = childSelectionSets.flatMap { it.selections },
            enclosingVariableReferences = childSelectionSets.flatMap { it.enclosingVariableReferences }.distinct(),
            conditionallyExcludedCoordinates = childSelectionSets.flatMapTo(mutableSetOf()) {
                it.conditionallyExcludedCoordinates
            },
        )
        return copy(
            projectionType = subselectionType,
            source = childSelectionSet,
            requestsBaseType = childSelections.isNotEmpty(),
        )
    }

    private fun findSelection(
        type: String,
        match: (FieldSelection) -> Boolean,
    ): FieldSelection? {
        val requestedType = compositeType(type)
        return fieldSelections.find { match(it) && it.isSelectableOn(requestedType) }
    }

    private fun FieldSelection.isSelectableOn(type: GraphQLCompositeType): Boolean {
        val relation = schema.rels.relationUnwrapped(typeCondition, type)
        return (relation == GraphQLTypeRelation.Same || relation == GraphQLTypeRelation.WiderThan) &&
            !constraints.solve(ctx.constraintsCtxFor(type)).isDrop
    }

    private fun FieldSelection.isSpreadableTo(type: GraphQLCompositeType): Boolean =
        schema.rels.isSpreadable(typeCondition, type) &&
            !constraints.solve(ctx.constraintsCtxFor(type)).isDrop

    private fun QueryPlan.Selection.isDroppedByDirectives(): Boolean = constraints.clearTypes().solve(ctx.constraintsCtxWithoutTypes).isDrop

    private fun compositeType(name: String): GraphQLCompositeType {
        val type = requireNotNull(schema.schema.getType(name)) {
            "type $name is not defined"
        }
        return requireNotNull(type as? GraphQLCompositeType) {
            "Type $name is not a composite type"
        }
    }

    private fun fieldsContainer(name: String): GraphQLFieldsContainer =
        requireNotNull(compositeType(name) as? GraphQLFieldsContainer) {
            "type $name is not a field container"
        }

    private fun fieldDefinition(selection: FieldSelection): GraphQLFieldDefinition = fieldDefinition(selection.typeCondition.name, selection.fieldName)

    private fun fieldDefinition(
        type: String,
        field: String,
    ): GraphQLFieldDefinition =
        requireNotNull(schema.schema.getFieldDefinition((type to field).gj)) {
            "Field $type.$field is not defined"
        }

    private fun GraphQLFieldDefinition.compositeOutputType(
        type: String,
        field: String,
    ): GraphQLCompositeType =
        requireNotNull(GraphQLTypeUtil.unwrapAll(this.type) as? GraphQLCompositeType) {
            "Field $type.$field does not support subselections"
        }

    private fun toDocument(fragmentName: String = EntryPointFragmentName): Document =
        if (isEmpty()) {
            Document(emptyList())
        } else {
            Document(
                listOf(
                    FragmentDefinition.newFragmentDefinition()
                        .name(fragmentName)
                        .typeCondition(TypeName(type))
                        .selectionSet(toSelectionSet())
                        .build()
                )
            )
        }

    private fun FieldSelection.toAstField(): GJField? {
        val childSelectionSet = selectionSet ?: return field
        val childType = fieldDefinition(this).compositeOutputType(typeCondition.name, fieldName)
        val childAst = create(
            schema = schema,
            typeName = childType.name,
            selectionSet = childSelectionSet,
            fragments = ctx.fragments,
            variables = variables,
            graphQLContext = ctx.graphQLContext,
        ).toSelectionSet()
        if (childAst.selections.isEmpty()) return null
        return field.transform { builder -> builder.selectionSet(childAst) }
    }

    companion object {
        fun create(
            schema: ViaductSchema,
            queryPlan: QueryPlan,
            variables: Map<String, Any?> = emptyMap(),
            graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
        ): EngineSelectionSet {
            val parentType = requireNotNull(GraphQLTypeUtil.unwrapAll(queryPlan.parentType) as? GraphQLCompositeType) {
                "QueryPlan parent type `${queryPlan.parentType}` is not composite"
            }
            return create(
                schema = schema,
                typeName = parentType.name,
                selectionSet = queryPlan.selectionSet,
                fragments = queryPlan.fragments,
                variables = variables,
                graphQLContext = graphQLContext,
            )
        }

        fun create(
            schema: ViaductSchema,
            fieldType: GraphQLOutputType,
            selectionSet: QueryPlan.SelectionSet?,
            fragments: QueryPlan.Fragments,
            variables: Map<String, Any?> = emptyMap(),
            graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
        ): EngineSelectionSet? {
            val compositeType = GraphQLTypeUtil.unwrapAll(fieldType) as? GraphQLCompositeType ?: return null
            return create(
                schema = schema,
                typeName = compositeType.name,
                selectionSet = selectionSet ?: QueryPlan.SelectionSet.empty(compositeType),
                fragments = fragments,
                variables = variables,
                graphQLContext = graphQLContext,
            )
        }

        fun create(
            schema: ViaductSchema,
            typeName: String,
            selectionSet: QueryPlan.SelectionSet,
            fragments: QueryPlan.Fragments,
            variables: Map<String, Any?> = emptyMap(),
            graphQLContext: GraphQLContext = GraphQLContext.getDefault(),
        ): EngineSelectionSet {
            val projectionType = schema.schema.getTypeAs<GraphQLCompositeType>(typeName)
            require(schema.rels.isSpreadable(selectionSet.parentType, projectionType)) {
                "Selections on `${selectionSet.parentType.name}` cannot be projected to `${projectionType.name}`"
            }
            return ExecutionSelectionSet(
                ctx = Ctx(
                    schema = schema,
                    fragments = fragments,
                    variables = variables,
                    graphQLContext = graphQLContext,
                ),
                projectionType = projectionType,
                source = selectionSet,
                requestsBaseType = true,
            )
        }

        fun createForField(
            parameters: ExecutionParameters,
            field: QueryPlan.CollectedField,
        ): EngineSelectionSet? =
            create(
                schema = parameters.engineExecutionContext.activeSchema,
                fieldType = parameters.executionStepInfo.fieldDefinition.type,
                selectionSet = field.selectionSet,
                fragments = parameters.queryPlan.fragments,
                variables = parameters.coercedVariables.toMap(),
                graphQLContext = parameters.executionContext.graphQLContext,
            )

        private interface FieldSelection {
            val typeCondition: GraphQLCompositeType
            val selection: QueryPlan.Selection
            val constraints: Constraints
            val fieldName: String
            val resultKey: String
            val selectionSet: QueryPlan.SelectionSet?
            val arguments: List<Argument>
            val directives: List<Directive>
            val field: GJField

            fun toEngineSelection(): EngineSelection =
                EngineSelection(
                    typeCondition = typeCondition.name,
                    fieldName = fieldName,
                    selectionName = resultKey,
                )
        }

        private data class QueryPlanFieldSelection(
            override val typeCondition: GraphQLCompositeType,
            private val queryPlanField: QueryPlan.Field,
            override val constraints: Constraints,
        ) : FieldSelection {
            override val selection: QueryPlan.Selection get() = queryPlanField
            override val fieldName: String get() = queryPlanField.field.name
            override val resultKey: String get() = queryPlanField.resultKey
            override val selectionSet: QueryPlan.SelectionSet? get() = queryPlanField.selectionSet
            override val arguments: List<Argument> get() = queryPlanField.field.arguments
            override val directives: List<Directive> get() = queryPlanField.field.directives
            override val field: GJField get() = queryPlanField.field
        }

        private data class CollectedFieldSelection(
            override val typeCondition: GraphQLCompositeType,
            private val collectedField: QueryPlan.CollectedField,
            override val constraints: Constraints,
        ) : FieldSelection {
            override val selection: QueryPlan.Selection get() = collectedField
            override val fieldName: String get() = collectedField.fieldName
            override val resultKey: String get() = collectedField.responseKey
            override val selectionSet: QueryPlan.SelectionSet? get() = collectedField.selectionSet
            override val arguments: List<Argument> get() = collectedField.mergedField.arguments
            override val directives: List<Directive> get() = collectedField.mergedField.singleField.directives
            override val field: GJField get() = collectedField.mergedField.singleField
        }

        private class FieldDirectivesImpl(
            private val directives: List<Directive>,
            private val ctx: Ctx,
        ) : FieldDirectives {
            override fun hasDirective(
                name: String,
                args: ((Map<String, Any?>) -> Boolean)?,
            ): Boolean {
                val matching = directives.filter { it.name == name }
                if (matching.isEmpty()) return false
                if (args == null) return true

                val directiveDefinition = ctx.schema.schema.getDirective(name) ?: return false
                return matching.any { directive ->
                    args(
                        ValuesResolver.getArgumentValues(
                            ctx.schema.schema.codeRegistry,
                            directiveDefinition.arguments,
                            directive.arguments,
                            ctx.coercedVariables,
                            ctx.graphQLContext,
                            ctx.locale,
                        )
                    )
                }
            }
        }

        private data class ProjectedSelectionSet(
            val fields: List<FieldSelection>,
            val requestedTypes: Set<GraphQLCompositeType>,
            val excludedResultKeysByType: Map<String, Set<GraphQLCompositeType>>,
        )
    }
}
