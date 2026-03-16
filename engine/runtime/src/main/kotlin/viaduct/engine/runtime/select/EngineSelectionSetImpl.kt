package viaduct.engine.runtime.select

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.Selection
import graphql.language.SelectionSet
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentSource
import viaduct.engine.api.fragment.FragmentVariables
import viaduct.engine.api.gj
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.graphql.utils.GraphQLTypeRelation

data class EngineSelectionSetContext(
    val variables: Map<String, Any?>,
    val fragmentDefinitions: Map<String, FragmentDefinition>,
    val schema: ViaductSchema,
    val gjContext: GraphQLContext,
    val locale: Locale
) {
    val coercedVariables: CoercedVariables = CoercedVariables.of(variables)
    val constraintsCtx: Constraints.Ctx = Constraints.Ctx(coercedVariables, null)
}

/** A FieldSelection combines a GraphQL Field selection with a type condition */
data class FieldSelection(
    val field: Field,
    val typeCondition: GraphQLCompositeType,
    val constraints: Constraints = Constraints.Unconstrained
) {
    override fun toString(): String =
        buildString {
            if (field.alias != null) {
                append(field.alias + ":")
            }
            append(typeCondition.name)
            append(".")
            append(field.name)
        }
}

/**
 * EngineSelectionSetImpl provides an untyped interface for SelectionSet manipulation. It is intended for direct
 * use by the Viaduct engine or indirect use by tenants via a [SelectionSetImpl].
 *
 * It is differentiated from the graphql-java SelectionSet class by being specialized for
 * Viaduct use-cases, including:
 * - @skip or @include directives are applied eagerly
 *
 * - operations that involve projecting from an interface into an implementation, or a union into a
 *   member, will inherit selected fields from the parent type.
 */
data class EngineSelectionSetImpl(
    /** The GraphQL type described by this selection set */
    val def: GraphQLCompositeType,
    /**
     * [EngineSelectionSet] models its selections as a flattened list of fields and type conditions.
     * This list describes the direct selections on the current type, nested selections that have
     * a type condition, and fragment spreads. These selections do not include field subselections.
     *
     * @see FieldSelection
     */
    val selections: List<FieldSelection>,
    /** the explicit types requested, @see [requestsType] */
    val requestedTypes: Set<GraphQLCompositeType>,
    /** active Constraints for statically pruning unreachable selections at construction time */
    val constraints: Constraints,
    /** contextual data for this selection set */
    val ctx: EngineSelectionSetContext
) : EngineSelectionSet {
    override val type: String get() = def.name

    override fun selections(): List<EngineSelection> = selections.map { it.toEngineSelection() }

    override fun traversableSelections(): List<EngineSelection> {
        val type = compositeType(type)
        return selections.mapNotNull { sel ->
            // a selection can be reprojected by widening and then narrowing to a different type.
            // Reject any selections that do not have spreadable type conditions
            if (!ctx.schema.rels.isSpreadable(sel.typeCondition, type)) {
                return@mapNotNull null
            }

            // subselections are not supported on non-composite types
            val selectionType = GraphQLTypeUtil.unwrapAll(fieldDefinition(sel).type)
            if (selectionType !is GraphQLCompositeType) {
                return@mapNotNull null
            }
            sel.toEngineSelection()
        }
    }

    override fun toSelectionSet(): SelectionSet {
        val newSelections =
            selections
                .groupBy(keySelector = { it.typeCondition }, valueTransform = { it.field })
                .map { (type, fields) ->
                    InlineFragment(TypeName(type.name), SelectionSet(fields))
                }.mapNotNull { asEagerlyInlined(it, constraints) }

        return SelectionSet(newSelections)
    }

    override fun addVariables(variables: Map<String, Any?>): EngineSelectionSet {
        this.ctx.variables.forEach { (k, _) ->
            require(!variables.containsKey(k)) {
                "cannot rebind variable with key $k"
            }
        }
        val newCtx = this.ctx.copy(variables = this.ctx.variables + variables)
        val newSelections = selections.filter { sel ->
            !sel.constraints.solve(newCtx.constraintsCtx).isDrop
        }
        return this.copy(
            ctx = newCtx,
            selections = newSelections,
            constraints = constraints
        )
    }

    override fun toFragment(): Fragment {
        return Fragment(
            FragmentSource.create(toDocument()),
            FragmentVariables.fromMap(ctx.variables)
        )
    }

    override fun toNodelikeSelectionSet(
        nodeFieldName: String,
        arguments: List<Argument>
    ): EngineSelectionSet {
        val isNode = this.type == "Node"
        val implementsNode = (this.def as? GraphQLImplementingType)?.interfaces?.any { it.name == "Node" } == true
        require(isNode || implementsNode) {
            "Cannot call toNodelikeSelectionSet for a type that does not implement Node: ${this.def.name}"
        }

        val selections =
            toSelectionSet().let { ss ->
                if (ss.selections.isNotEmpty()) {
                    val field = Field(nodeFieldName, arguments, ss)
                    val fieldSelection = FieldSelection(field, ctx.schema.schema.queryType)
                    listOf(fieldSelection)
                } else {
                    emptyList()
                }
            }

        return this.copy(
            def = ctx.schema.schema.queryType,
            selections = selections,
            constraints = Constraints.Unconstrained
        )
    }

    override fun containsField(
        type: String,
        field: String
    ): Boolean = findSelection(type) { it.name == field } != null

    override fun containsSelection(
        type: String,
        selectionName: String
    ): Boolean = findSelection(type) { it.resultKey == selectionName } != null

    private fun findSelection(
        type: String,
        match: (Field) -> Boolean
    ): FieldSelection? {
        val u = compositeType(type)
        return selections.find { (f, t) ->
            if (!match(f)) return@find false
            val rel = ctx.schema.rels.relationUnwrapped(t, u)
            rel == GraphQLTypeRelation.Same || rel == GraphQLTypeRelation.WiderThan
        }
    }

    override fun resolveSelection(
        type: String,
        selectionName: String
    ): EngineSelection =
        findSelection(type) { it.resultKey == selectionName }
            ?.let { it.toEngineSelection() }
            ?: throw IllegalArgumentException("No selection found for selectionName `$selectionName`")

    /**
     * Return a new EngineSelectionSetImpl that incorporates the provided graphql-java
     * [graphql.language.SelectionSet].
     *
     * The provided SelectionSet must be schematically valid for this
     * EngineSelectionSetImpl's [def].
     */
    internal operator fun plus(selectionSet: GJSelectionSet): EngineSelectionSetImpl = withTypedSelections(def, selectionSet)

    /** Recursively extract the [FieldSelection]s that apply to the provided type. */
    private fun withTypedSelections(
        type: GraphQLCompositeType,
        selectionSet: GJSelectionSet,
        parentConstraints: Constraints = this.constraints,
        spreadFragments: Set<String> = emptySet()
    ): EngineSelectionSetImpl =
        selectionSet.selections
            .fold(this) { acc, sel ->
                val childConstraints = parentConstraints.descend(sel)
                if (childConstraints.solve(ctx.constraintsCtx).isDrop) return@fold acc

                when (sel) {
                    is Field ->
                        acc.copy(
                            selections = acc.selections + FieldSelection(sel, type, childConstraints)
                        )

                    is InlineFragment -> {
                        val u =
                            if (sel.typeCondition == null) {
                                type
                            } else {
                                compositeType(sel.typeCondition.name)
                            }
                        acc.withTypedSelections(u, sel.selectionSet, childConstraints, spreadFragments)
                    }

                    is FragmentSpread -> {
                        require(sel.name !in spreadFragments) {
                            "Cyclic fragment spreads detected"
                        }

                        val frag = getFragmentDefinition(sel.name)
                        val u = compositeType(frag.typeCondition.name)
                        acc.withTypedSelections(
                            u,
                            frag.selectionSet,
                            childConstraints,
                            spreadFragments + sel.name
                        )
                    }

                    else -> throw IllegalArgumentException("Unsupported Selection type: $sel")
                }
            }
            .let { rss -> rss.copy(requestedTypes = rss.requestedTypes + type) }

    override fun requestsType(type: String): Boolean {
        val u = compositeType(type)
        val found =
            requestedTypes.find {
                val rel = ctx.schema.rels.relationUnwrapped(it, u)
                rel == GraphQLTypeRelation.Same || rel == GraphQLTypeRelation.NarrowerThan
            }
        return found != null
    }

    override fun selectionSetForField(
        type: String,
        field: String
    ): EngineSelectionSetImpl {
        val coord = (type to field).gj
        val subselectionType =
            ctx.schema.schema.getFieldDefinition(coord)?.let {
                // field type may have NonNull/List wrappers
                GraphQLTypeUtil.unwrapAll(it.type) as? GraphQLCompositeType
                    ?: throw IllegalArgumentException("Field $type.$field does not support subselections")
            } ?: throw IllegalArgumentException("Field $type.$field is not defined")

        return buildSubselections(compositeType(type), subselectionType) { it.name == field }
    }

    override fun selectionSetForSelection(
        type: String,
        selectionName: String
    ): EngineSelectionSetImpl {
        val selectionType = fieldsContainer(type)
        val subselectionType =
            resolveSelection(type, selectionName).let { sel ->
                val fieldName = sel.fieldName
                val coord = (type to fieldName).gj
                ctx.schema.schema.getFieldDefinition(coord)?.let {
                    // field type may have NonNull/List wrappers
                    GraphQLTypeUtil.unwrapAll(it.type) as? GraphQLCompositeType
                        ?: throw IllegalArgumentException("Field $type.$fieldName does not support subselections")
                } ?: throw IllegalArgumentException("Field $type.$fieldName is not defined")
            }

        return buildSubselections(selectionType, subselectionType) { it.resultKey == selectionName }
    }

    private fun buildSubselections(
        selectionType: GraphQLCompositeType,
        subselectionType: GraphQLCompositeType,
        match: (Field) -> Boolean
    ): EngineSelectionSetImpl {
        if (!ctx.schema.rels.isSpreadable(this.def, selectionType)) {
            throw IllegalArgumentException("Selections of type ${selectionType.name} are not spreadable in type ${this.def.name}")
        }

        val newSelections =
            selections
                .filter { (f, ftc) ->
                    if (!match(f)) return@filter false
                    val rel = ctx.schema.rels.relationUnwrapped(ftc, selectionType)
                    rel == GraphQLTypeRelation.WiderThan || rel == GraphQLTypeRelation.Same
                }
                .mapNotNull { it.field.selectionSet }

        val base = EngineSelectionSetImpl(
            def = subselectionType,
            selections = emptyList(),
            requestedTypes = emptySet(),
            constraints = Constraints.Unconstrained.narrowToImpls(subselectionType, ctx.schema),
            ctx = ctx
        )
        return newSelections.fold(base) { acc, ss -> acc + ss }
    }

    override fun selectionSetForType(type: String): EngineSelectionSet {
        val u = compositeType(type)

        if (u == this.def) return this

        if (!ctx.schema.rels.isSpreadable(this.def, u)) {
            throw IllegalArgumentException("Selections of type $type are not spreadable in type ${this.def.name}")
        }

        val newConstraints = constraints.narrowTypes(ctx.schema.rels.possibleObjectTypes(u))
        val filteredSelections = selections.filter { ctx.schema.rels.isSpreadable(it.typeCondition, u) }
        val filteredRequestedTypes = requestedTypes.filter { ctx.schema.rels.isSpreadable(it, u) }.toSet()

        if (u is GraphQLObjectType) {
            val sourceImpl = copy(
                def = u,
                selections = filteredSelections,
                requestedTypes = filteredRequestedTypes,
                constraints = newConstraints
            )
            return ProjectedEngineSelectionSet.from(u, filteredSelections, ctx, sourceImpl)
        }

        return copy(
            def = u,
            selections = filteredSelections,
            requestedTypes = filteredRequestedTypes,
            constraints = newConstraints
        )
    }

    /**
     * Compute child [Constraints] when descending into a selection node.
     *
     * - [Field]: inherit directive constraints from the parent, clear type constraints (field
     *   subselections have no relation to the parent type condition)
     * - [InlineFragment]: narrow type constraints to the fragment's type condition; add directive constraints
     * - [FragmentSpread]: same as InlineFragment, resolved via fragment definition lookup
     */
    private fun Constraints.descend(sel: Selection<*>): Constraints =
        when (sel) {
            is Field ->
                withDirectives(sel.directives).clearTypes()

            is InlineFragment -> {
                val typeCondition = if (sel.typeCondition == null) {
                    def
                } else {
                    compositeType(sel.typeCondition.name)
                }
                withDirectives(sel.directives).narrowToImpls(typeCondition, ctx.schema)
            }

            is FragmentSpread -> {
                val frag = getFragmentDefinition(sel.name)
                withDirectives(sel.directives).narrowToImpls(
                    compositeType(frag.typeCondition.name),
                    ctx.schema
                )
            }

            else -> throw IllegalArgumentException("Unsupported Selection type: $sel")
        }

    override fun isEmpty(): Boolean = selections.isEmpty()

    override fun isTransitivelyEmpty(): Boolean {
        if (isEmpty()) return true

        return selections
            .groupBy { it.field.name }
            .all { (fname, cfs) ->
                val u = cfs.first().typeCondition
                if (u is GraphQLFieldsContainer) {
                    val coord = (u.name to fname).gj
                    val field = ctx.schema.schema.getFieldDefinition(coord)
                    if (GraphQLTypeUtil.unwrapAll(field.type) is GraphQLCompositeType) {
                        selectionSetForField(u.name, fname).isTransitivelyEmpty()
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
    }

    override fun printAsFieldSet(): String =
        toSelectionSet().selections.joinToString("\n") {
            AstPrinter.printAstCompact(it)
        }

    override fun argumentsOfSelection(
        type: String,
        selectionName: String
    ): Map<String, Any?>? =
        findSelection(type) { it.resultKey == selectionName }
            ?.let { sel ->
                ValuesResolver.getArgumentValues(
                    ctx.schema.schema.codeRegistry,
                    fieldDefinition(sel).arguments,
                    sel.field.arguments,
                    ctx.coercedVariables,
                    ctx.gjContext,
                    Locale.getDefault()
                )
            }

    private fun compositeType(name: String): GraphQLCompositeType =
        (ctx.schema.schema.getType(name) ?: throw IllegalArgumentException("type $name is not defined"))
            as? GraphQLCompositeType ?: throw IllegalArgumentException("Type $name is not a composite type")

    private fun fieldsContainer(name: String): GraphQLFieldsContainer =
        requireNotNull(compositeType(name) as? GraphQLFieldsContainer) {
            "type $name is not a field container"
        }

    private fun fieldDefinition(sel: FieldSelection): GraphQLFieldDefinition {
        val coord = (sel.typeCondition.name to sel.field.name).gj
        return ctx.schema.schema.getFieldDefinition(coord)
    }

    private fun asEagerlyInlined(
        selection: Selection<*>,
        constraints: Constraints
    ): Selection<*>? {
        // At serialization time, selections have already been validated and pruned during
        // construction via withTypedSelections. We only need directive constraints here —
        // applying type narrowing (via descend) is incorrect because the type constraints
        // reflect the root type, not the subfield types, and would incorrectly prune valid
        // inline fragments (e.g., `... on Foo` inside a Query-rooted ESS after toNodelikeSelectionSet).
        val directives = when (selection) {
            is Field -> selection.directives
            is InlineFragment -> selection.directives
            is FragmentSpread -> selection.directives
            else -> throw IllegalArgumentException("Unsupported Selection type: $selection")
        }
        val child = constraints.withDirectives(directives).clearTypes()
        if (child.solve(ctx.constraintsCtx).isDrop) return null

        return when (val sel = selection) {
            is Field -> {
                if (sel.selectionSet == null) {
                    sel
                } else {
                    asEagerlyInlined(sel.selectionSet, child)?.let { ss ->
                        sel.transform { it.selectionSet(ss) }
                    }
                }
            }

            is InlineFragment ->
                asEagerlyInlined(sel.selectionSet, child)?.let { ss ->
                    sel.transform { it.selectionSet(ss) }
                }

            is FragmentSpread -> {
                val frag = getFragmentDefinition(sel.name)
                asEagerlyInlined(frag.selectionSet, child)?.let { ss ->
                    InlineFragment.newInlineFragment()
                        .typeCondition(frag.typeCondition)
                        .selectionSet(ss)
                        .build()
                }
            }

            else -> throw IllegalArgumentException("Unsupported Selection type: $sel")
        }
    }

    private fun asEagerlyInlined(
        ss: GJSelectionSet,
        constraints: Constraints
    ): GJSelectionSet? =
        ss.selections.mapNotNull { asEagerlyInlined(it, constraints) }
            .takeIf { it.isNotEmpty() }
            ?.let(::GJSelectionSet)

    private fun getFragmentDefinition(name: String): FragmentDefinition =
        requireNotNull(ctx.fragmentDefinitions[name]) {
            "Missing fragment definition: $name"
        }

    private fun FieldSelection.toEngineSelection(): EngineSelection =
        EngineSelection(
            typeCondition = typeCondition.name,
            fieldName = field.name,
            selectionName = field.resultKey
        )

    companion object {
        private val emptyGraphQLContext = GraphQLContext.getDefault()

        fun create(
            parsedSelections: ParsedSelections,
            variables: Map<String, Any?>,
            schema: ViaductSchema,
            graphQLContext: GraphQLContext = emptyGraphQLContext
        ): EngineSelectionSetImpl {
            val typeName = parsedSelections.typeName
            val type =
                schema.schema.getType(typeName) as? GraphQLCompositeType
                    ?: throw IllegalArgumentException(
                        "Expected $typeName to map to a GraphQLCompositeType, but instead found ${schema.schema.getType(typeName)}"
                    )

            val base =
                EngineSelectionSetImpl(
                    def = type,
                    requestedTypes = emptySet(),
                    selections = emptyList(),
                    constraints = Constraints.Unconstrained.narrowToImpls(type, schema),
                    ctx = EngineSelectionSetContext(
                        variables = variables,
                        fragmentDefinitions = parsedSelections.fragmentMap,
                        schema = schema,
                        gjContext = graphQLContext,
                        locale = Locale.getDefault()
                    )
                )

            return base.withTypedSelections(type, parsedSelections.selections)
        }
    }
}
