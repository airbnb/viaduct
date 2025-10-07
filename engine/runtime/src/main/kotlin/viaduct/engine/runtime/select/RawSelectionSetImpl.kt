package viaduct.engine.runtime.select

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ValuesResolver
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.DirectivesContainer
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
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RawSelection
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentSource
import viaduct.engine.api.fragment.FragmentVariables
import viaduct.engine.api.gj
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.ensureOneDirective
import viaduct.graphql.utils.rawValue

data class RawSelectionSetContext(
    val variables: Map<String, Any?>,
    val fragmentDefinitions: Map<String, FragmentDefinition>,
    val schema: ViaductSchema,
    val gjContext: GraphQLContext,
    val locale: Locale
) {
    val coercedVariables by lazy { CoercedVariables.of(variables) }
}

/** A FieldSelection combines a GraphQL Field selection with a type condition */
data class FieldSelection(val field: Field, val typeCondition: GraphQLCompositeType) {
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
 * RawSelectionSet provides an untyped interface for SelectionSet manipulation. It is intended for direct
 * use by the Viaduct engine or indirect use by tenants via a [SelectionSetImpl].
 *
 * It is differentiated from the graphql-java SelectionSet class by being specialized for
 * Viaduct use-cases, including:
 * - @skip or @include directives are applied eagerly
 *
 * - operations that involve projecting from an interface into an implementation, or a union into a
 *   member, will inherit selected fields from the parent type.
 */
data class RawSelectionSetImpl(
    /** The GraphQL type described by this selection set */
    val def: GraphQLCompositeType,
    /**
     * [RawSelectionSet] models its selections as a flattened list of fields and type conditions.
     * This list describes the direct selections on the current type, nested selections that have
     * a type condition, and fragment spreads. These selections do not include field subselections.
     *
     * @see FieldSelection
     */
    val selections: List<FieldSelection>,
    /** the explicit types requested, @see [requestsType] */
    val requestedTypes: Set<GraphQLCompositeType>,
    /** contextual data for this selection set */
    val ctx: RawSelectionSetContext
) : RawSelectionSet {
    override val type: String get() = def.name

    override fun selections(): List<RawSelection> = selections.map { it.toRawSelection() }

    override fun traversableSelections(): List<RawSelection> {
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
            sel.toRawSelection()
        }
    }

    override fun toSelectionSet(): SelectionSet {
        val newSelections =
            selections
                .groupBy(keySelector = { it.typeCondition }, valueTransform = { it.field })
                .map { (type, fields) ->
                    InlineFragment(TypeName(type.name), SelectionSet(fields))
                }.mapNotNull(::asEagerlyInlined)

        return SelectionSet(newSelections)
    }

    override fun addVariables(variables: Map<String, Any?>): RawSelectionSet {
        this.ctx.variables.forEach { (k, _) ->
            require(!variables.containsKey(k)) {
                "cannot rebind variable with key $k"
            }
        }
        return this.copy(
            ctx = this.ctx.copy(variables = this.ctx.variables + variables)
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
    ): RawSelectionSet {
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

        return this.copy(def = ctx.schema.schema.queryType, selections = selections)
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
    ): RawSelection =
        findSelection(type) { it.resultKey == selectionName }
            ?.let { it.toRawSelection() }
            ?: throw IllegalArgumentException("No selection found for selectionName `$selectionName`")

    /**
     * Return a new RawSelectionSetImpl that incorporates the provided graphql-java
     * [graphql.language.SelectionSet].
     *
     * The provided SelectionSet must be schematically valid for this
     * RawSelectionSetImpl's [def].
     */
    internal operator fun plus(selectionSet: GJSelectionSet): RawSelectionSetImpl = withTypedSelections(def, selectionSet)

    /** Recursively extract the [FieldSelection]s that apply to the provided type. */
    private fun withTypedSelections(
        type: GraphQLCompositeType,
        selectionSet: GJSelectionSet,
        spreadFragments: Set<String> = emptySet()
    ): RawSelectionSetImpl =
        selectionSet.selections
            .filter(::applyConditionalDirectives)
            .fold(this) { acc, sel ->
                when (sel) {
                    is Field ->
                        acc.copy(
                            selections = acc.selections + FieldSelection(sel, type)
                        )

                    is InlineFragment -> {
                        val u =
                            if (sel.typeCondition == null) {
                                this.def
                            } else {
                                compositeType(sel.typeCondition.name)
                            }
                        acc.withTypedSelections(u, sel.selectionSet)
                    }

                    is FragmentSpread -> {
                        if (sel.name in spreadFragments) {
                            throw IllegalArgumentException("Cyclic fragment spreads detected")
                        }

                        val frag = getFragmentDefinition(sel.name)
                        val u = compositeType(frag.typeCondition.name)
                        acc.withTypedSelections(
                            u,
                            frag.selectionSet,
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
    ): RawSelectionSetImpl {
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
    ): RawSelectionSetImpl {
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
    ): RawSelectionSetImpl {
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

        val base = RawSelectionSetImpl(def = subselectionType, selections = emptyList(), requestedTypes = emptySet(), ctx)
        return newSelections.fold(base) { acc, ss -> acc + ss }
    }

    override fun selectionSetForType(type: String): RawSelectionSetImpl {
        val u = compositeType(type)

        if (u == this.def) return this

        if (!ctx.schema.rels.isSpreadable(this.def, u)) {
            throw IllegalArgumentException("Selections of type $type are not spreadable in type ${this.def.name}")
        }

        return copy(
            def = u,
            selections = selections.filter { ctx.schema.rels.isSpreadable(it.typeCondition, u) },
            requestedTypes = requestedTypes.filter { ctx.schema.rels.isSpreadable(it, u) }.toSet()
        )
    }

    /**
     * Apply any applicable @skip/@include directives, returning false if the selection should be dropped.
     *
     * Selections with directives that depend on variable references will be dropped only if the required
     * variable value is present. Selections whose inclusion depends on variable references that are not
     * present will be kept.
     */
    private fun applyConditionalDirectives(selection: Selection<*>): Boolean {
        when (selection) {
            is DirectivesContainer<*> -> {
                val directivesByName = selection.directivesByName

                val skip =
                    directivesByName["skip"].ensureOneDirective()
                        ?.argumentsByName
                        ?.get("if")
                        ?.value
                        ?.rawValue(ctx.variables)

                if (skip == true) return false

                val include =
                    directivesByName["include"].ensureOneDirective()
                        ?.argumentsByName
                        ?.get("if")
                        ?.value
                        ?.rawValue(ctx.variables)

                if (include == false) return false

                return true
            }

            else -> return true
        }
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
                    if (field.type is GraphQLCompositeType) {
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

    private fun asEagerlyInlined(selection: Selection<*>): Selection<*>? =
        when (val sel = selection) {
            is Field -> {
                if (sel.selectionSet == null) {
                    sel
                } else {
                    asEagerlyInlined(sel.selectionSet)?.let { ss ->
                        sel.transform { it.selectionSet(ss) }
                    }
                }
            }

            is InlineFragment ->
                if (sel.selectionSet.selections.isEmpty()) {
                    null
                } else {
                    asEagerlyInlined(sel.selectionSet)?.let { ss ->
                        sel.transform { it.selectionSet(ss) }
                    }
                }

            is FragmentSpread -> {
                val frag = getFragmentDefinition(sel.name)
                if (frag.selectionSet.selections.isEmpty()) {
                    null
                } else {
                    asEagerlyInlined(frag.selectionSet)?.let { ss ->
                        InlineFragment.newInlineFragment()
                            .typeCondition(frag.typeCondition)
                            .selectionSet(ss)
                            .build()
                    }
                }
            }

            else -> throw IllegalArgumentException("Unsupported Selection type: $sel")
        }

    private fun asEagerlyInlined(ss: GJSelectionSet): GJSelectionSet? =
        ss.selections.mapNotNull(::asEagerlyInlined)
            .filter(::applyConditionalDirectives)
            .takeIf { it.isNotEmpty() }
            ?.let(::GJSelectionSet)

    private fun getFragmentDefinition(name: String): FragmentDefinition =
        requireNotNull(ctx.fragmentDefinitions[name]) {
            "Missing fragment definition: $name"
        }

    private fun FieldSelection.toRawSelection(): RawSelection =
        RawSelection(
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
        ): RawSelectionSetImpl {
            val typeName = parsedSelections.typeName
            val type =
                schema.schema.getType(typeName) as? GraphQLCompositeType
                    ?: throw IllegalArgumentException(
                        "Expected $typeName to map to a GraphQLCompositeType, but instead found ${schema.schema.getType(typeName)}"
                    )

            val base =
                RawSelectionSetImpl(
                    def = type,
                    requestedTypes = emptySet(),
                    selections = emptyList(),
                    ctx = RawSelectionSetContext(
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
