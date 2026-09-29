package semantics.arbitrary

import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.BooleanValue
import graphql.language.Field
import graphql.language.FloatValue
import graphql.language.FragmentDefinition
import graphql.language.InlineFragment
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.Selection
import graphql.language.SelectionSet
import graphql.language.StringValue
import graphql.language.TypeName
import graphql.language.Value
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import java.math.BigDecimal
import java.math.BigInteger

class ArbitraryQuery internal constructor(
    val source: String,
    val permutationEquivalentSource: String,
    val selectionDepth: Int,
    val features: QueryFeatures,
) {
    override fun toString(): String = source
}

data class QueryFeatures(
    val hasAliases: Boolean,
    val hasDuplicateSelections: Boolean,
    val hasDistinctArgumentSelections: Boolean,
    val hasExactKeyAliasConvergence: Boolean,
    val hasAbstractInlineFragmentBranches: Boolean,
    val hasMultipleAbstractInlineFragmentBranches: Boolean,
    val hasAbstractImplementationDefaultSelection: Boolean,
    val exactKeyAliasSourceFields: Set<FieldCoordinate>,
    val distinctArgumentSourceFields: Set<FieldCoordinate>,
)

fun ArbitrarySchema.query(config: Config = Config.default): Arb<ArbitraryQuery> {
    val generatedSchema = this
    return arbitrary { random ->
        QueryGenerator(generatedSchema, config, random).generate()
    }
}

private class QueryGenerator(
    private val schema: ArbitrarySchema,
    private val config: Config,
    private val random: RandomSource,
) {
    private val features = MutableQueryFeatures()
    private var nextAlias = 0

    fun generate(): ArbitraryQuery = generate(attempt = 1)

    private fun generate(attempt: Int): ArbitraryQuery {
        val selectionSet = selectionSet("Query", depth = 0)
        val fragment = fragment(selectionSet)
        val permutationEquivalentFragment = fragment(selectionSet.permuted())
        val query =
            ArbitraryQuery(
                source = AstPrinter.printAst(fragment).trim(),
                permutationEquivalentSource =
                    AstPrinter.printAst(permutationEquivalentFragment).trim(),
                selectionDepth = selectionSet.maximumFieldDepth(),
                features = features.snapshot(),
            )
        if (
            query.source.length <= MAX_GENERATED_QUERY_CHARACTERS &&
            query.permutationEquivalentSource.length <= MAX_GENERATED_QUERY_CHARACTERS
        ) {
            return query
        }
        require(attempt < MAX_GENERATION_ATTEMPTS) {
            "Could not generate a bounded GraphQL query in $MAX_GENERATION_ATTEMPTS attempts"
        }
        val retryConfig =
            if (attempt == MAX_GENERATION_ATTEMPTS - 1) {
                config.withBoundedQueryFallback()
            } else {
                config
            }
        return QueryGenerator(schema, retryConfig, random).generate(attempt + 1)
    }

    private fun fragment(selectionSet: SelectionSet): FragmentDefinition =
        FragmentDefinition
            .newFragmentDefinition()
            .name("Generated")
            .typeCondition(TypeName("Query"))
            .selectionSet(selectionSet)
            .build()

    private fun selectionSet(
        typeName: String,
        depth: Int,
    ): SelectionSet {
        if (depth >= config[MaxSelectionDepth] - 1) {
            return SelectionSet.newSelectionSet()
                .selection(Field.newField("__typename").build())
                .build()
        }
        if (typeName == GENERATED_HASH_TYPE) {
            return SelectionSet
                .newSelectionSet()
                .selection(Field.newField(GENERATED_HASH_FIELD).build())
                .build()
        }
        val objectType = schema.allObjects.singleOrNull { it.name == typeName }
        val candidates =
            schema.fieldsOn(typeName).filterNot { field ->
                field.isGeneratedHashField() || field.isParentField
            } +
                syntheticFields(typeName, objectType?.implementsNode == true)

        val rootOverride = config[RootQueryFieldCount]
        val count =
            if (
                config[RootFieldReferencesEnabled] &&
                (
                    typeName == GENERATED_ROOT_REFERENCE_CONSUMER ||
                        typeName.startsWith("GeneratedRootReferencePair") ||
                        typeName.startsWith("GeneratedRootReferenceQuad")
                )
            ) {
                candidates.size
            } else if (typeName == "Query" && rootOverride != 0..0) {
                Arb.int(rootOverride).next(random).coerceIn(1, candidates.size)
            } else {
                val configured = config[NestedQueryFieldCount]
                val maximum = minOf(configured.last, candidates.size)
                val minimum = minOf(configured.first, maximum)
                Arb.int(minimum..maximum).next(random)
            }
        val requiredField =
            schema.deepFields[typeName]
                ?.takeIf { depth < config[MinimumSelectionDepth] }
                ?.let { fieldName -> candidates.single { it.name == fieldName } }
        val requiredRootReferenceFields =
            when {
                !config[RootFieldReferencesEnabled] -> emptyList()
                typeName == "Query" ->
                    listOf(
                        candidates.single { field ->
                            field.name == GENERATED_ROOT_REFERENCE_CONSUMER_FIELD
                        },
                    ) +
                        listOfNotNull(
                            schema.rootFieldReferenceFamily
                                ?.nodeConsumerCoordinate
                                ?.let { coordinate ->
                                    candidates.single { field -> field.name == coordinate.fieldName }
                                },
                        )
                typeName == GENERATED_ROOT_REFERENCE_NODE ->
                    candidates.filter { field ->
                        field.name == "id" ||
                            field.name == GENERATED_ROOT_REFERENCE_EXTENSION_FIELD
                    }
                else -> emptyList()
            }
        val requiredFields = (listOfNotNull(requiredField) + requiredRootReferenceFields).distinct()
        val remainingCandidates =
            candidates
                .filterNot { it in requiredFields }
                .shuffled(random)
                .let { fields ->
                    if (
                        typeName != "Query" &&
                        config[NestedQueryScalarFieldWeight] > 0.0 &&
                        chance(config[NestedQueryScalarFieldWeight])
                    ) {
                        fields.sortedBy { field ->
                            schema.isComposite(field.type.namedType)
                        }
                    } else {
                        fields
                    }
                }
        val selectedFields =
            requiredFields +
                remainingCandidates
                    .take(
                        (count - requiredFields.size).coerceAtLeast(0),
                    )
        val directSelections =
            selectedFields
                .flatMap { field ->
                    if (field.hasImplementationOnlyDefault()) {
                        features.hasAbstractImplementationDefaultSelection = true
                    }
                    val arguments = field.arguments.map(::argument)
                    val selection = fieldSelection(field, depth, arguments = arguments)
                    buildList {
                        add(selection)
                        if (chance(config[DuplicateSelectionWeight])) {
                            add(selection)
                            features.hasDuplicateSelections = true
                        }
                        if (
                            field.arguments.isNotEmpty() &&
                            chance(config[DuplicateSelectionWeight])
                        ) {
                            add(
                                fieldSelection(
                                    field = field,
                                    depth = depth,
                                    arguments = arguments.distinctFrom(field),
                                    forceAlias = true,
                                ),
                            )
                            features.hasDistinctArgumentSelections = true
                            features.distinctArgumentSourceFields +=
                                field.possibleSourceCoordinates()
                        }
                        if (
                            schema.isComposite(field.type.namedType) &&
                            field.type.namedType != GENERATED_HASH_TYPE &&
                            (
                                schema.fieldsOn(field.type.namedType).isNotEmpty() ||
                                    config[QueryFragmentsEnabled]
                            ) &&
                            chance(config[DuplicateSelectionWeight])
                        ) {
                            addAll(exactKeyAliasConvergence(field, depth, arguments))
                            features.hasExactKeyAliasConvergence = true
                            features.exactKeyAliasSourceFields +=
                                field.possibleSourceCoordinates()
                        }
                    }
                }
        val possibleObjects =
            if (objectType == null) schema.possibleObjects(typeName) else emptyList()
        val fragmentSelections =
            if (
                config[QueryFragmentsEnabled] &&
                possibleObjects.isNotEmpty() &&
                (
                    typeName == GENERATED_ROOT_REFERENCE_INTERFACE ||
                        typeName == GENERATED_ROOT_REFERENCE_UNION ||
                        chance(0.75)
                )
            ) {
                val maximumBranches = minOf(3, possibleObjects.size)
                val branchCount =
                    if (maximumBranches > 1) {
                        Arb.int(2..maximumBranches).next(random)
                    } else {
                        1
                    }
                features.hasAbstractInlineFragmentBranches = true
                if (branchCount > 1) {
                    features.hasMultipleAbstractInlineFragmentBranches = true
                }
                possibleObjects
                    .shuffled(random)
                    .take(branchCount)
                    .map { concrete ->
                        InlineFragment
                            .newInlineFragment()
                            .typeCondition(TypeName(concrete.name))
                            .selectionSet(selectionSet(concrete.name, depth + 1))
                            .build()
                    }
            } else {
                emptyList()
            }
        val generatedHashSelection =
            objectType
                ?.takeUnless { it.name == GENERATED_ROOT_REFERENCE_NODE }
                ?.fields
                ?.singleOrNull(FieldDefinitionSpec::isGeneratedHashField)
                ?.let {
                    Field
                        .newField(GENERATED_HASH_FIELD)
                        .selectionSet(
                            SelectionSet
                                .newSelectionSet()
                                .selection(Field.newField(GENERATED_HASH_FIELD).build())
                                .build(),
                        ).build()
                }
        return SelectionSet
            .newSelectionSet()
            .selections(directSelections + fragmentSelections + listOfNotNull(generatedHashSelection))
            .build()
    }

    private fun fieldSelection(
        field: FieldDefinitionSpec,
        depth: Int,
        arguments: List<Argument> = field.arguments.map(::argument),
        forceAlias: Boolean = false,
    ): Selection<*> {
        val alias =
            if (forceAlias || chance(config[AliasWeight])) {
                "alias${nextAlias++}"
            } else {
                null
            }
        if (alias != null) {
            features.hasAliases = true
        }
        val builder =
            Field
                .newField(field.name)
                .alias(alias)
                .arguments(arguments)
        val objectType =
            field.type.namedType.takeIf(schema::isComposite)
        if (objectType != null) {
            builder.selectionSet(selectionSet(objectType, depth + 1))
        }
        val selection: Selection<*> = builder.build()
        return if (
            config[QueryFragmentsEnabled] &&
            field.ownerName != "Query" &&
            chance(0.3)
        ) {
            InlineFragment
                .newInlineFragment()
                .typeCondition(TypeName(field.ownerName))
                .selectionSet(
                    SelectionSet.newSelectionSet().selection(selection).build(),
                ).build()
        } else {
            selection
        }
    }

    private fun List<Argument>.distinctFrom(field: FieldDefinitionSpec): List<Argument> {
        require(size == field.arguments.size && isNotEmpty())
        val changedIndex = indices.first()
        return mapIndexed { index, argument ->
            if (index != changedIndex) {
                argument
            } else {
                Argument
                    .newArgument()
                    .name(argument.name)
                    .value(
                        distinctInputValue(
                            field.arguments[index].type,
                            argument.value,
                        ),
                    ).build()
            }
        }
    }

    private fun distinctInputValue(
        type: InputTypeSpec,
        current: Value<*>,
    ): Value<*> =
        when (current) {
            is NullValue -> inputValue(type, allowNull = false)
            is BooleanValue ->
                BooleanValue.newBooleanValue(!current.isValue).build()
            is IntValue ->
                IntValue.newIntValue(current.value + BigInteger.ONE).build()
            is FloatValue ->
                FloatValue.newFloatValue(current.value + BigDecimal.ONE).build()
            is StringValue ->
                StringValue.newStringValue(current.value + "-distinct").build()
            is ArrayValue -> {
                require(type is ListInputTypeSpec)
                if (current.values.isEmpty()) {
                    ArrayValue
                        .newArrayValue()
                        .value(inputValue(type.element, allowNull = false))
                        .build()
                } else {
                    ArrayValue.newArrayValue().values(emptyList()).build()
                }
            }
            is ObjectValue -> {
                require(type is InputObjectInputTypeSpec)
                val definition =
                    schema.inputObjects.single { candidate -> candidate.name == type.name }
                val changed = definition.fields.first()
                ObjectValue
                    .newObjectValue()
                    .objectFields(
                        current.objectFields.map { field ->
                            if (field.name == changed.name) {
                                ObjectField
                                    .newObjectField()
                                    .name(field.name)
                                    .value(distinctInputValue(changed.type, field.value))
                                    .build()
                            } else {
                                field
                            }
                        },
                    ).build()
            }
            else -> error("Unsupported generated input value $current")
        }

    private fun exactKeyAliasConvergence(
        field: FieldDefinitionSpec,
        depth: Int,
        arguments: List<Argument>,
    ): List<Field> {
        val typename = Field.newField("__typename").build()
        val directDemand =
            schema
                .fieldsOn(field.type.namedType)
                .filterNot(FieldDefinitionSpec::isGeneratedHashField)
                .shuffled(random)
                .firstOrNull()
                ?.let { demandedField ->
                    fieldSelection(
                        field = demandedField,
                        depth = depth + 1,
                        forceAlias = false,
                    )
                }
        val additionalDemand =
            directDemand
                ?: run {
                    require(config[QueryFragmentsEnabled])
                    val concrete =
                        schema
                            .possibleObjects(field.type.namedType)
                            .shuffled(random)
                            .first()
                    val demandedField =
                        schema
                            .fieldsOn(concrete.name)
                            .filterNot(FieldDefinitionSpec::isGeneratedHashField)
                            .shuffled(random)
                            .first()
                    InlineFragment
                        .newInlineFragment()
                        .typeCondition(TypeName(concrete.name))
                        .selectionSet(
                            SelectionSet
                                .newSelectionSet()
                                .selection(
                                    fieldSelection(
                                        field = demandedField,
                                        depth = depth + 1,
                                        forceAlias = false,
                                    ),
                                ).build(),
                        ).build()
                }

        fun alias(selectionSet: SelectionSet): Field {
            features.hasAliases = true
            return Field
                .newField(field.name)
                .alias("alias${nextAlias++}")
                .arguments(arguments)
                .selectionSet(selectionSet)
                .build()
        }

        return listOf(
            alias(SelectionSet.newSelectionSet().selection(typename).build()),
            alias(
                SelectionSet
                    .newSelectionSet()
                    .selections(listOf(typename, additionalDemand))
                    .build(),
            ),
        )
    }

    private fun FieldDefinitionSpec.possibleSourceCoordinates(): Set<FieldCoordinate> =
        if (schema.allObjects.any { objectType -> objectType.name == ownerName }) {
            setOf(coordinate)
        } else {
            schema
                .possibleObjects(ownerName)
                .mapTo(linkedSetOf()) { possibleType ->
                    FieldCoordinate(possibleType.name, name)
                }
        }

    private fun FieldDefinitionSpec.hasImplementationOnlyDefault(): Boolean {
        val interfaceType =
            schema.interfaces.singleOrNull { candidate -> candidate.name == ownerName }
                ?: return false
        val abstractArguments = arguments.mapTo(linkedSetOf(), ArgumentDefinitionSpec::name)
        return interfaceType.members.any { memberName ->
            schema
                .objectNamed(memberName)
                .fields
                .singleOrNull { field -> field.name == name }
                ?.arguments
                .orEmpty()
                .any { argument ->
                    argument.name !in abstractArguments && argument.defaultValue != null
                }
        }
    }

    private fun argument(definition: ArgumentDefinitionSpec): Argument {
        return Argument
            .newArgument()
            .name(definition.name)
            .value(inputValue(definition.type))
            .build()
    }

    private fun inputValue(
        type: InputTypeSpec,
        objectPath: Set<String> = emptySet(),
        allowNull: Boolean = true,
    ): Value<*> {
        if (type is ListInputTypeSpec && type.element.reachesAny(objectPath)) {
            return ArrayValue.newArrayValue().values(emptyList()).build()
        }
        if (
            type is InputObjectInputTypeSpec &&
            type.nullable &&
            type.reachesAny(objectPath)
        ) {
            return NullValue.newNullValue().build()
        }
        if (allowNull && type.nullable && chance(config[NullValueWeight])) {
            return NullValue.newNullValue().build()
        }
        return when (type) {
            is ScalarInputTypeSpec -> scalarValue(type.scalar)
            is ListInputTypeSpec -> {
                val size = Arb.int(config[ListValueSize]).next(random)
                ArrayValue
                    .newArrayValue()
                    .values(List(size) { inputValue(type.element, objectPath) })
                    .build()
            }
            is InputObjectInputTypeSpec -> {
                if (type.name in objectPath) {
                    require(type.nullable) {
                        "Recursive input-object edge ${type.name} must be nullable"
                    }
                    NullValue.newNullValue().build()
                } else {
                    val definition =
                        schema.inputObjects.single { candidate -> candidate.name == type.name }
                    ObjectValue
                        .newObjectValue()
                        .objectFields(
                            definition.fields.map { field ->
                                ObjectField
                                    .newObjectField()
                                    .name(field.name)
                                    .value(inputValue(field.type, objectPath + type.name))
                                    .build()
                            },
                        ).build()
                }
            }
        }
    }

    private fun InputTypeSpec.reachesAny(
        targets: Set<String>,
        visited: Set<String> = emptySet(),
    ): Boolean =
        when (this) {
            is ScalarInputTypeSpec -> false
            is ListInputTypeSpec -> element.reachesAny(targets, visited)
            is InputObjectInputTypeSpec -> {
                name in targets ||
                    (
                        name !in visited &&
                            schema.inputObjects
                                .single { it.name == name }
                                .fields
                                .any { field -> field.type.reachesAny(targets, visited + name) }
                    )
            }
        }

    private fun scalarValue(scalar: ScalarKind): Value<*> {
        val salt = Arb.int(config[InputScalarValueRange]).next(random)
        return when (scalar) {
            ScalarKind.BOOLEAN -> BooleanValue.newBooleanValue(salt % 2 == 0).build()
            ScalarKind.FLOAT -> FloatValue.newFloatValue(BigDecimal("$salt.5")).build()
            ScalarKind.ID -> StringValue.newStringValue("id-$salt").build()
            ScalarKind.INT -> IntValue.newIntValue(BigInteger.valueOf(salt.toLong())).build()
            ScalarKind.STRING -> StringValue.newStringValue("value-$salt").build()
        }
    }

    private fun syntheticFields(
        typeName: String,
        implementsNode: Boolean,
    ): List<FieldDefinitionSpec> =
        buildList {
            add(
                FieldDefinitionSpec(
                    ownerName = typeName,
                    name = "__typename",
                    type =
                        OutputTypeSpec(
                            namedType = "String",
                            nullable = false,
                            list = false,
                            elementNullable = false,
                        ),
                    arguments = emptyList(),
                ),
            )
            if (implementsNode) {
                add(
                    FieldDefinitionSpec(
                        ownerName = typeName,
                        name = "id",
                        type =
                            OutputTypeSpec(
                                namedType = "ID",
                                nullable = false,
                                list = false,
                                elementNullable = false,
                            ),
                        arguments = emptyList(),
                    ),
                )
            }
        }

    private fun <T> List<T>.shuffled(random: RandomSource): List<T> {
        val remaining = toMutableList()
        val result = mutableListOf<T>()
        while (remaining.isNotEmpty()) {
            result += remaining.removeAt(Arb.int(0 until remaining.size).next(random))
        }
        return result
    }

    private fun chance(weight: Double): Boolean = Arb.double(0.0, 1.0).next(random) < weight

    private fun SelectionSet.permuted(): SelectionSet =
        SelectionSet
            .newSelectionSet()
            .selections(
                selections
                    .map { selection ->
                        when (selection) {
                            is Field -> {
                                val builder =
                                    Field
                                        .newField(selection.name)
                                        .alias(
                                            selection.alias?.let { alias ->
                                                "permuted_$alias"
                                            },
                                        ).arguments(
                                            selection.arguments
                                                .map { argument ->
                                                    Argument
                                                        .newArgument()
                                                        .name(argument.name)
                                                        .value(argument.value.permuted())
                                                        .build()
                                                }.reversed(),
                                        )
                                selection.selectionSet?.let { child ->
                                    builder.selectionSet(child.permuted())
                                }
                                builder.build()
                            }
                            is InlineFragment ->
                                InlineFragment
                                    .newInlineFragment()
                                    .typeCondition(selection.typeCondition)
                                    .selectionSet(selection.selectionSet.permuted())
                                    .build()
                            else -> selection
                        }
                    }.shuffled(random),
            ).build()

    private fun Value<*>.permuted(): Value<*> =
        when (this) {
            is ArrayValue ->
                ArrayValue
                    .newArrayValue()
                    .values(values.map { value -> value.permuted() })
                    .build()
            is ObjectValue ->
                ObjectValue
                    .newObjectValue()
                    .objectFields(
                        objectFields
                            .map { field ->
                                ObjectField
                                    .newObjectField()
                                    .name(field.name)
                                    .value(field.value.permuted())
                                    .build()
                            }.reversed(),
                    ).build()
            else -> this
        }

    private fun SelectionSet.maximumFieldDepth(): Int =
        selections.maxOfOrNull { selection ->
            when (selection) {
                is Field -> 1 + (selection.selectionSet?.maximumFieldDepth() ?: 0)
                is InlineFragment -> selection.selectionSet.maximumFieldDepth()
                else -> 0
            }
        } ?: 0

    private class MutableQueryFeatures {
        var hasAliases: Boolean = false
        var hasDuplicateSelections: Boolean = false
        var hasDistinctArgumentSelections: Boolean = false
        var hasExactKeyAliasConvergence: Boolean = false
        var hasAbstractInlineFragmentBranches: Boolean = false
        var hasMultipleAbstractInlineFragmentBranches: Boolean = false
        var hasAbstractImplementationDefaultSelection: Boolean = false
        val exactKeyAliasSourceFields = linkedSetOf<FieldCoordinate>()
        val distinctArgumentSourceFields = linkedSetOf<FieldCoordinate>()

        fun snapshot(): QueryFeatures =
            QueryFeatures(
                hasAliases = hasAliases,
                hasDuplicateSelections = hasDuplicateSelections,
                hasDistinctArgumentSelections = hasDistinctArgumentSelections,
                hasExactKeyAliasConvergence = hasExactKeyAliasConvergence,
                hasAbstractInlineFragmentBranches = hasAbstractInlineFragmentBranches,
                hasMultipleAbstractInlineFragmentBranches =
                hasMultipleAbstractInlineFragmentBranches,
                hasAbstractImplementationDefaultSelection =
                hasAbstractImplementationDefaultSelection,
                exactKeyAliasSourceFields = exactKeyAliasSourceFields.toSet(),
                distinctArgumentSourceFields = distinctArgumentSourceFields.toSet(),
            )
    }

    private companion object {
        const val MAX_GENERATED_QUERY_CHARACTERS = 12_000
        const val MAX_GENERATION_ATTEMPTS = 100
    }
}

// Retains the required deep path while guaranteeing low branching on the final attempt.
private fun Config.withBoundedQueryFallback(): Config =
    this +
        (RootQueryFieldCount to 1..1) +
        (NestedQueryFieldCount to 1..1) +
        (DuplicateSelectionWeight to 0.0) +
        (QueryFragmentsEnabled to false)
