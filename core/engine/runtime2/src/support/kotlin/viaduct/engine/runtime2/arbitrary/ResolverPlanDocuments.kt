package viaduct.engine.runtime2.arbitrary

import viaduct.engine.runtime2.model.registry.ProviderFragment

internal data class RegistryDocument(
    val fieldResolvers: List<FieldResolverDocument>,
    val nodeResolvers: List<NodeResolverDocument>,
    val variables: List<VariableProviderDocument>,
    val features: RegistryFeatures,
)

internal data class FieldResolverDocument(
    val coordinate: CoordinateDocument,
    val value: ValuePlanDocument,
    val fragment: FragmentPlanDocument,
    val program: ResolverProgramKind,
    val queryFragment: FragmentPlanDocument =
        FragmentPlanDocument("Query", emptyList()),
)

internal data class NodeResolverDocument(
    val typeName: String,
    val value: ValuePlanDocument,
)

internal data class CoordinateDocument(
    val typeName: String,
    val fieldName: String,
)

internal data class FragmentPlanDocument(
    val ownerName: String,
    val selections: List<FragmentSelectionDocument>,
)

internal data class FragmentSelectionDocument(
    val fieldName: String,
    val arguments: List<ArgumentDocument>,
    val subselections: List<FragmentSelectionDocument>,
    val typeCondition: String?,
    val alias: String?,
)

internal data class ArgumentDocument(
    val name: String,
    val value: InputValuePlanDocument,
)

internal data class InputTypeDocument(
    val kind: String,
    val nullable: Boolean,
    val scalar: ScalarKind?,
    val name: String?,
    val element: InputTypeDocument?,
)

internal data class InputValuePlanDocument(
    val kind: String,
    val type: InputTypeDocument?,
    val literal: String?,
    val elements: List<InputValuePlanDocument>,
    val fields: List<InputFieldDocument>,
    val variableName: String?,
    val placeholder: InputValuePlanDocument?,
    val variableTarget: VariableTargetDocument? = null,
)

/** A scalar target when [elementNullabilities] is empty, otherwise a scalar list target. */
internal data class VariableTargetDocument(
    val scalar: ScalarKind,
    val nullable: Boolean,
    val elementNullabilities: List<Boolean> = emptyList(),
)

internal data class InputFieldDocument(
    val name: String,
    val value: InputValuePlanDocument,
)

internal data class ValuePlanDocument(
    val kind: String,
    val scalar: ScalarKind?,
    val literal: String?,
    val elements: List<ValuePlanDocument>,
    val typeName: String?,
    val fields: List<OutputFieldDocument>,
    val salt: Int?,
    val targetPath: List<CoordinateDocument> = emptyList(),
    val targetArguments: Map<String, Any?> = emptyMap(),
)

internal data class OutputFieldDocument(
    val coordinate: CoordinateDocument,
    val value: ValuePlanDocument,
)

internal data class VariableProviderDocument(
    val kind: String,
    val owner: CoordinateDocument,
    val variableName: String,
    val argumentName: String?,
    val selection: FragmentSelectionDocument?,
    val nestedInput: Boolean,
    val listValue: Boolean,
    val nullable: Boolean,
    val abstractPath: Boolean?,
    val useDepth: Int?,
    val topLevelUseField: CoordinateDocument?,
    val literalConvergence: Boolean,
    val argumentPath: List<String> = emptyList(),
    val nullableTraversal: Boolean = false,
    val providerScalar: ScalarKind? = null,
    val providerElementNullabilities: List<Boolean> = emptyList(),
    val argumentSensitive: Boolean = false,
)

internal fun ArbitraryRegistry.toRegistryDocument(): RegistryDocument =
    RegistryDocument(
        fieldResolvers =
            fieldValues
                .map { (coordinate, value) ->
                    FieldResolverDocument(
                        coordinate = coordinate.toDocument(),
                        value = value.toDocument(),
                        fragment = objectFragments.getValue(coordinate).toDocument(),
                        program = resolverPrograms.getValue(coordinate),
                        queryFragment = queryFragments.getValue(coordinate).toDocument(),
                    )
                },
        nodeResolvers =
            nodeValues
                .map { (typeName, value) ->
                    NodeResolverDocument(
                        typeName = typeName,
                        value = value.toDocument(),
                    )
                },
        variables =
            variableProviders.map(VariableProviderPlan::toDocument),
        features = features,
    )

internal fun RegistryDocument.toRegistry(schema: ArbitrarySchema): ArbitraryRegistry {
    val fieldValues: Map<FieldCoordinate, ValuePlan> =
        fieldResolvers.associate { resolver ->
            resolver.coordinate.toCoordinate() to resolver.value.toValuePlan()
        }
    val objectFragments: Map<FieldCoordinate, FragmentPlan> =
        fieldResolvers.associate { resolver ->
            resolver.coordinate.toCoordinate() to resolver.fragment.toFragmentPlan()
        }
    val queryFragments: Map<FieldCoordinate, FragmentPlan> =
        fieldResolvers.associate { resolver ->
            resolver.coordinate.toCoordinate() to resolver.queryFragment.toFragmentPlan()
        }
    val resolverPrograms: Map<FieldCoordinate, ResolverProgramKind> =
        fieldResolvers.associate { resolver ->
            resolver.coordinate.toCoordinate() to resolver.program
        }
    val nodeValues: Map<String, ValuePlan> =
        nodeResolvers.associate { resolver ->
            resolver.typeName to resolver.value.toValuePlan()
        }
    val variableProviders: List<VariableProviderPlan> =
        variables.map(VariableProviderDocument::toVariableProviderPlan)
    val outputSelectionSets: Map<String, Set<String>> =
        buildMap {
            fieldValues.forEach { (coordinate, value) ->
                put(coordinate.toString(), value.selectedPaths())
            }
            nodeValues.forEach { (typeName, value) ->
                put(typeName, value.selectedPaths())
            }
        }
    return ArbitraryRegistry(
        fieldResolverCoordinates = fieldValues.keys,
        nodeResolverTypes = nodeValues.keys,
        outputSelectionSets = outputSelectionSets,
        objectFragmentSources =
            objectFragments.mapValues { (_, fragment) -> fragment.source() },
        queryFragmentSources =
            queryFragments.mapValues { (_, fragment) -> fragment.source() },
        variableProviderSources =
            variableProviders
                .filterIsInstance<FromFieldVariableProviderPlan>()
                .associate { provider -> provider.variableName to provider.source() },
        fieldValues = fieldValues,
        nodeValues = nodeValues,
        objectFragments = objectFragments,
        queryFragments = queryFragments,
        variableProviders = variableProviders,
        resolverPrograms = resolverPrograms,
        parentDemandOwnerFields = schema.parentDemandOwnerFields(objectFragments),
        schemaRootFieldReferenceFamily = schema.rootFieldReferenceFamily,
        features = features,
    )
}

private fun FieldCoordinate.toDocument(): CoordinateDocument = CoordinateDocument(typeName, fieldName)

private fun CoordinateDocument.toCoordinate(): FieldCoordinate = FieldCoordinate(typeName, fieldName)

private fun FragmentPlan.toDocument(): FragmentPlanDocument =
    FragmentPlanDocument(
        ownerName = ownerName,
        selections = selections.map(FragmentSelectionPlan::toDocument),
    )

private fun FragmentPlanDocument.toFragmentPlan(): FragmentPlan =
    FragmentPlan(
        ownerName = ownerName,
        selections = selections.map(FragmentSelectionDocument::toFragmentSelectionPlan),
    )

private fun FragmentSelectionPlan.toDocument(): FragmentSelectionDocument =
    FragmentSelectionDocument(
        fieldName = fieldName,
        arguments = arguments.map { (name, value) -> ArgumentDocument(name, value.toDocument()) },
        subselections = subselections.map(FragmentSelectionPlan::toDocument),
        typeCondition = typeCondition,
        alias = alias,
    )

private fun FragmentSelectionDocument.toFragmentSelectionPlan(): FragmentSelectionPlan =
    FragmentSelectionPlan(
        fieldName = fieldName,
        arguments = arguments.associate { argument -> argument.name to argument.value.toInputValuePlan() },
        subselections = subselections.map(FragmentSelectionDocument::toFragmentSelectionPlan),
        typeCondition = typeCondition,
        alias = alias,
    )

internal fun InputTypeSpec.toDocument(): InputTypeDocument =
    when (this) {
        is ScalarInputTypeSpec ->
            InputTypeDocument(
                kind = "scalar",
                nullable = nullable,
                scalar = scalar,
                name = null,
                element = null,
            )
        is ListInputTypeSpec ->
            InputTypeDocument(
                kind = "list",
                nullable = nullable,
                scalar = null,
                name = null,
                element = element.toDocument(),
            )
        is InputObjectInputTypeSpec ->
            InputTypeDocument(
                kind = "object",
                nullable = nullable,
                scalar = null,
                name = name,
                element = null,
            )
    }

internal fun InputTypeDocument.toInputTypeSpec(): InputTypeSpec =
    when (kind) {
        "scalar" -> ScalarInputTypeSpec(requireNotNull(scalar), nullable)
        "list" -> ListInputTypeSpec(requireNotNull(element).toInputTypeSpec(), nullable)
        "object" -> InputObjectInputTypeSpec(requireNotNull(name), nullable)
        else -> error("Unknown input type plan kind $kind")
    }

private fun InputValuePlan.toDocument(): InputValuePlanDocument =
    when (this) {
        is InputLiteralPlan ->
            InputValuePlanDocument(
                kind = "literal",
                type = type.toDocument(),
                literal = scalarLiteral(type.scalar, value),
                elements = emptyList(),
                fields = emptyList(),
                variableName = null,
                placeholder = null,
            )
        is ListInputPlan ->
            InputValuePlanDocument(
                kind = "list",
                type = type.toDocument(),
                literal = null,
                elements = elements.map(InputValuePlan::toDocument),
                fields = emptyList(),
                variableName = null,
                placeholder = null,
            )
        is ObjectInputPlan ->
            InputValuePlanDocument(
                kind = "object",
                type = type.toDocument(),
                literal = null,
                elements = emptyList(),
                fields = fields.map { (name, value) -> InputFieldDocument(name, value.toDocument()) },
                variableName = null,
                placeholder = null,
            )
        is NullInputPlan ->
            InputValuePlanDocument(
                kind = "null",
                type = type.toDocument(),
                literal = null,
                elements = emptyList(),
                fields = emptyList(),
                variableName = null,
                placeholder = null,
            )
        is VariableInputPlan ->
            InputValuePlanDocument(
                kind = "variable",
                type = null,
                literal = null,
                elements = emptyList(),
                fields = emptyList(),
                variableName = variableName,
                placeholder = null,
                variableTarget = target?.toDocument(),
            )
        is ErrorInputPlan ->
            InputValuePlanDocument(
                kind = "error",
                type = null,
                literal = null,
                elements = emptyList(),
                fields = emptyList(),
                variableName = null,
                placeholder = placeholder.toDocument(),
            )
    }

private fun InputValuePlanDocument.toInputValuePlan(): InputValuePlan =
    when (kind) {
        "literal" -> {
            val scalarType = requireNotNull(type).toInputTypeSpec()
            require(scalarType is ScalarInputTypeSpec)
            InputLiteralPlan(
                type = scalarType,
                value = parseScalarLiteral(scalarType.scalar, requireNotNull(literal)),
            )
        }
        "list" -> {
            val listType = requireNotNull(type).toInputTypeSpec()
            require(listType is ListInputTypeSpec)
            ListInputPlan(
                type = listType,
                elements = elements.map(InputValuePlanDocument::toInputValuePlan),
            )
        }
        "object" -> {
            val objectType = requireNotNull(type).toInputTypeSpec()
            require(objectType is InputObjectInputTypeSpec)
            ObjectInputPlan(
                type = objectType,
                fields = fields.associate { field -> field.name to field.value.toInputValuePlan() },
            )
        }
        "null" -> NullInputPlan(requireNotNull(type).toInputTypeSpec())
        "variable" -> VariableInputPlan(requireNotNull(variableName), variableTarget?.toVariableTarget())
        "error" -> ErrorInputPlan(requireNotNull(placeholder).toInputValuePlan())
        else -> error("Unknown input value plan kind $kind")
    }

private fun VariableTarget.toDocument(): VariableTargetDocument =
    when (this) {
        is ScalarVariableTarget -> VariableTargetDocument(scalar, nullable)
        is ListVariableTarget -> VariableTargetDocument(scalar, nullable, elementNullabilities)
    }

private fun VariableTargetDocument.toVariableTarget(): VariableTarget =
    if (elementNullabilities.isEmpty()) {
        ScalarVariableTarget(scalar, nullable)
    } else {
        ListVariableTarget(scalar, nullable, elementNullabilities)
    }

private fun ValuePlan.toDocument(): ValuePlanDocument =
    when (this) {
        NullPlan -> terminalValueDocument("null")
        ErrorPlan -> terminalValueDocument("error")
        InputIdPlan -> terminalValueDocument("input-id")
        is ScalarPlan ->
            ValuePlanDocument(
                kind = "scalar",
                scalar = scalar,
                literal = scalarLiteral(scalar, value),
                elements = emptyList(),
                typeName = null,
                fields = emptyList(),
                salt = null,
            )
        is EnumPlan ->
            ValuePlanDocument(
                kind = "enum",
                scalar = null,
                literal = value,
                elements = emptyList(),
                typeName = null,
                fields = emptyList(),
                salt = null,
            )
        is RootFieldReferencePlan ->
            ValuePlanDocument(
                kind = "root-field-reference",
                scalar = null,
                literal = null,
                elements = emptyList(),
                typeName = null,
                fields = emptyList(),
                salt = null,
                targetPath = target.path.map(FieldCoordinate::toDocument),
                targetArguments = target.arguments,
            )
        is ListPlan ->
            ValuePlanDocument(
                kind = "list",
                scalar = null,
                literal = null,
                elements = elements.map(ValuePlan::toDocument),
                typeName = null,
                fields = emptyList(),
                salt = null,
            )
        is ObjectPlan ->
            ValuePlanDocument(
                kind = "object",
                scalar = null,
                literal = null,
                elements = emptyList(),
                typeName = typeName,
                fields =
                    fields.map { (coordinate, value) ->
                        OutputFieldDocument(coordinate.toDocument(), value.toDocument())
                    },
                salt = null,
            )
        is GeneratedHashPlan ->
            ValuePlanDocument(
                kind = "generated-hash",
                scalar = null,
                literal = null,
                elements = emptyList(),
                typeName = null,
                fields = emptyList(),
                salt = salt,
            )
    }

private fun terminalValueDocument(kind: String): ValuePlanDocument =
    ValuePlanDocument(
        kind = kind,
        scalar = null,
        literal = null,
        elements = emptyList(),
        typeName = null,
        fields = emptyList(),
        salt = null,
    )

private fun ValuePlanDocument.toValuePlan(): ValuePlan =
    when (kind) {
        "null" -> NullPlan
        "error" -> ErrorPlan
        "input-id" -> InputIdPlan
        "scalar" ->
            ScalarPlan(
                scalar = requireNotNull(scalar),
                value = parseScalarLiteral(scalar, requireNotNull(literal)),
            )
        "enum" -> EnumPlan(requireNotNull(literal))
        "root-field-reference" ->
            RootFieldReferencePlan(
                RootFieldReferenceTargetSpec(
                    path = targetPath.map(CoordinateDocument::toCoordinate),
                    arguments = targetArguments,
                ),
            )
        "list" -> ListPlan(elements.map(ValuePlanDocument::toValuePlan))
        "object" ->
            ObjectPlan(
                typeName = requireNotNull(typeName),
                fields =
                    fields.associate { field ->
                        field.coordinate.toCoordinate() to field.value.toValuePlan()
                    },
            )
        "generated-hash" -> GeneratedHashPlan(requireNotNull(salt))
        else -> error("Unknown output value plan kind $kind")
    }

private fun VariableProviderPlan.toDocument(): VariableProviderDocument =
    when (this) {
        is FromArgumentVariableProviderPlan ->
            VariableProviderDocument(
                kind = "from-argument",
                owner = owner.toDocument(),
                variableName = variableName,
                argumentName = argumentName,
                selection = null,
                nestedInput = nestedInput,
                listValue = listValue,
                nullable = nullable,
                abstractPath = null,
                useDepth = null,
                topLevelUseField = null,
                literalConvergence = literalConvergence,
                argumentPath = argumentPath,
                nullableTraversal = nullableTraversal,
            )
        is FromProviderVariableProviderPlan ->
            VariableProviderDocument(
                kind = "from-provider",
                owner = owner.toDocument(),
                variableName = variableName,
                argumentName = null,
                selection = null,
                nestedInput = nestedInput,
                listValue = listValue,
                nullable = nullable,
                abstractPath = null,
                useDepth = null,
                topLevelUseField = null,
                literalConvergence = literalConvergence,
                providerScalar =
                    when (val providerTarget = target) {
                        is ScalarVariableTarget -> providerTarget.scalar
                        is ListVariableTarget -> providerTarget.scalar
                    },
                providerElementNullabilities =
                    (target as? ListVariableTarget)?.elementNullabilities.orEmpty(),
                argumentSensitive = argumentSensitive,
            )
        is FromFieldVariableProviderPlan ->
            VariableProviderDocument(
                kind =
                    when (providerFragment) {
                        ProviderFragment.OBJECT -> "from-object-field"
                        ProviderFragment.QUERY -> "from-query-field"
                    },
                owner = owner.toDocument(),
                variableName = variableName,
                argumentName = null,
                selection = selection.toDocument(),
                nestedInput = nestedInput,
                listValue = listValue,
                nullable = nullable,
                abstractPath = abstractPath,
                useDepth = useDepth,
                topLevelUseField = topLevelUseField.toDocument(),
                literalConvergence = literalConvergence,
            )
    }

private fun VariableProviderDocument.toVariableProviderPlan(): VariableProviderPlan =
    when (kind) {
        "from-argument" ->
            FromArgumentVariableProviderPlan(
                owner = owner.toCoordinate(),
                variableName = variableName,
                argumentName = requireNotNull(argumentName),
                inputPath = argumentPath.drop(1),
                nullableTraversal = nullableTraversal,
                nestedInput = nestedInput,
                listValue = listValue,
                nullable = nullable,
                literalConvergence = literalConvergence,
            )
        "from-object-field",
        "from-query-field",
        ->
            FromFieldVariableProviderPlan(
                owner = owner.toCoordinate(),
                variableName = variableName,
                providerFragment =
                    if (kind == "from-object-field") {
                        ProviderFragment.OBJECT
                    } else {
                        ProviderFragment.QUERY
                    },
                selection = requireNotNull(selection).toFragmentSelectionPlan(),
                nestedInput = nestedInput,
                listValue = listValue,
                nullable = nullable,
                abstractPath = requireNotNull(abstractPath),
                useDepth = requireNotNull(useDepth),
                topLevelUseField = requireNotNull(topLevelUseField).toCoordinate(),
                literalConvergence = literalConvergence,
            )
        "from-provider" ->
            FromProviderVariableProviderPlan(
                owner = owner.toCoordinate(),
                variableName = variableName,
                target =
                    if (listValue) {
                        ListVariableTarget(
                            scalar = requireNotNull(providerScalar),
                            nullable = nullable,
                            elementNullabilities = providerElementNullabilities,
                        )
                    } else {
                        ScalarVariableTarget(
                            scalar = requireNotNull(providerScalar),
                            nullable = nullable,
                        )
                    },
                nestedInput = nestedInput,
                argumentSensitive = argumentSensitive,
            )
        else -> error("Unknown variable provider kind $kind")
    }

private fun scalarLiteral(
    scalar: ScalarKind,
    value: Any,
): String =
    when (scalar) {
        ScalarKind.BOOLEAN -> (value as Boolean).toString()
        ScalarKind.FLOAT -> (value as Number).toDouble().toString()
        ScalarKind.INT -> (value as Number).toInt().toString()
        ScalarKind.ID, ScalarKind.STRING -> value as String
    }

private fun parseScalarLiteral(
    scalar: ScalarKind,
    literal: String,
): Any =
    when (scalar) {
        ScalarKind.BOOLEAN -> literal.toBooleanStrict()
        ScalarKind.FLOAT -> literal.toDouble()
        ScalarKind.INT -> literal.toInt()
        ScalarKind.ID, ScalarKind.STRING -> literal
    }
