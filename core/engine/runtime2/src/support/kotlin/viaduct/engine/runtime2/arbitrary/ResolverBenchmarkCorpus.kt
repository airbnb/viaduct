package viaduct.engine.runtime2.arbitrary

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import graphql.language.StringValue
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlin.random.Random
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.testing.TestWorld

/** A fixed schema and executable registry recipe for resolver microbenchmarks. */
class ResolverBenchmarkCorpus private constructor(
    val schema: ArbitrarySchema,
    val registry: ArbitraryRegistry,
    val metrics: Map<String, Long>,
) {
    val schemaSDL: String
        get() = schema.sdl

    fun world(): TestWorld =
        registry.world(
            schemaSDL = schemaSDL,
        )

    fun generateQueries(
        count: Int,
        config: Config,
        seed: Long,
    ): List<ArbitraryQuery> {
        require(count > 0) { "Resolver benchmark query count must be positive" }
        val random = RandomSource(Random(seed), seed)
        val query = schema.query(config)
        return List(count) { query.next(random) }
    }

    companion object {
        fun decode(
            schemaSDL: String,
            registryJson: String,
        ): ResolverBenchmarkCorpus {
            val document: CorpusDocument = corpusMapper.readValue(registryJson)
            require(document.version == CORPUS_VERSION) {
                "Unsupported resolver benchmark corpus version ${document.version}"
            }
            val schema = document.schema.toSchema()
            require(schema.sdl.trim() == schemaSDL.trim()) {
                "Resolver benchmark schema resource does not match registry query model"
            }
            return ResolverBenchmarkCorpus(
                schema = schema,
                registry = document.registry.toRegistry(),
                metrics = document.metrics,
            )
        }

        fun load(
            schemaResource: String,
            registryResource: String,
            classLoader: ClassLoader = ResolverBenchmarkCorpus::class.java.classLoader,
        ): ResolverBenchmarkCorpus {
            val schemaSDL =
                requireNotNull(classLoader.getResourceAsStream(schemaResource)) {
                    "Missing resolver benchmark schema resource $schemaResource"
                }.bufferedReader().use { reader -> reader.readText() }
            val registryJson =
                requireNotNull(classLoader.getResourceAsStream(registryResource)) {
                    "Missing resolver benchmark registry resource $registryResource"
                }.bufferedReader().use { reader -> reader.readText() }
            return decode(schemaSDL, registryJson)
        }
    }
}

/** A versioned, ordered snapshot of the exact query sources used by a resolver benchmark. */
class ResolverBenchmarkQueryCorpus private constructor(
    val generationSeed: Long,
    querySources: List<String>,
) {
    val querySources: List<String> = querySources.toList()

    init {
        require(this.querySources.isNotEmpty()) {
            "Resolver benchmark query corpus must not be empty"
        }
        require(this.querySources.none(String::isBlank)) {
            "Resolver benchmark query corpus must not contain a blank query"
        }
    }

    fun encode(): String =
        corpusMapper.writeValueAsString(
            QueryCorpusDocument(
                version = QUERY_CORPUS_VERSION,
                generationSeed = generationSeed,
                queries = querySources,
            ),
        ) + "\n"

    companion object {
        fun create(
            generationSeed: Long,
            querySources: List<String>,
        ): ResolverBenchmarkQueryCorpus = ResolverBenchmarkQueryCorpus(generationSeed, querySources)

        fun decode(json: String): ResolverBenchmarkQueryCorpus {
            val document: QueryCorpusDocument = corpusMapper.readValue(json)
            require(document.version == QUERY_CORPUS_VERSION) {
                "Unsupported resolver benchmark query corpus version ${document.version}"
            }
            return ResolverBenchmarkQueryCorpus(
                generationSeed = document.generationSeed,
                querySources = document.queries,
            )
        }

        fun load(
            resource: String,
            classLoader: ClassLoader = ResolverBenchmarkQueryCorpus::class.java.classLoader,
        ): ResolverBenchmarkQueryCorpus {
            val json =
                requireNotNull(classLoader.getResourceAsStream(resource)) {
                    "Missing resolver benchmark query resource $resource"
                }.bufferedReader().use { reader -> reader.readText() }
            return decode(json)
        }
    }
}

fun ArbitraryRegistry.encodeResolverBenchmarkCorpus(
    schema: ArbitrarySchema,
    metrics: Map<String, Long> = emptyMap(),
): String {
    val document =
        CorpusDocument(
            version = CORPUS_VERSION,
            schema = schema.toDocument(),
            registry = toRegistryDocument(),
            metrics = metrics.toSortedMap(),
        )
    return corpusMapper.writeValueAsString(document) + "\n"
}

private const val CORPUS_VERSION = 1
private const val QUERY_CORPUS_VERSION = 1

private val corpusMapper =
    JsonMapper
        .builder()
        .addModule(kotlinModule())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

private data class CorpusDocument(
    val version: Int,
    val schema: SchemaDocument,
    val registry: RegistryDocument,
    val metrics: Map<String, Long>,
)

private data class QueryCorpusDocument(
    val version: Int,
    val generationSeed: Long,
    val queries: List<String>,
)

private data class SchemaDocument(
    val sdl: String,
    val objects: List<ObjectDefinitionDocument>,
    val query: ObjectDefinitionDocument,
    val hashType: ObjectDefinitionDocument,
    val interfaces: List<InterfaceDefinitionDocument>,
    val unions: List<UnionDefinitionDocument>,
    val inputObjects: List<InputObjectDefinitionDocument>,
    val deepFields: Map<String, String>,
    val features: SchemaFeatures,
)

private data class ObjectDefinitionDocument(
    val name: String,
    val implementsNode: Boolean,
    val interfaces: Set<String>,
    val fields: List<FieldDefinitionDocument>,
)

private data class InterfaceDefinitionDocument(
    val name: String,
    val members: Set<String>,
    val fields: List<FieldDefinitionDocument>,
)

private data class UnionDefinitionDocument(
    val name: String,
    val members: Set<String>,
)

private data class FieldDefinitionDocument(
    val ownerName: String,
    val name: String,
    val type: OutputTypeDocument,
    val arguments: List<ArgumentDefinitionDocument>,
)

private data class ArgumentDefinitionDocument(
    val name: String,
    val type: InputTypeDocument,
    val hasDefaultValue: Boolean,
)

private data class OutputTypeDocument(
    val namedType: String,
    val nullable: Boolean,
    val list: Boolean,
    val elementNullable: Boolean,
    val nestedElementNullabilities: List<Boolean>,
)

private data class InputObjectDefinitionDocument(
    val name: String,
    val fields: List<InputFieldDefinitionDocument>,
)

private data class InputFieldDefinitionDocument(
    val name: String,
    val type: InputTypeDocument,
)

private data class RegistryDocument(
    val fieldResolvers: List<FieldResolverDocument>,
    val nodeResolvers: List<NodeResolverDocument>,
    val variables: List<VariableProviderDocument>,
    val features: RegistryFeatures,
)

private data class FieldResolverDocument(
    val coordinate: CoordinateDocument,
    val value: ValuePlanDocument,
    val fragment: FragmentPlanDocument,
    val program: ResolverProgramKind,
    val queryFragment: FragmentPlanDocument =
        FragmentPlanDocument("Query", emptyList()),
)

private data class NodeResolverDocument(
    val typeName: String,
    val value: ValuePlanDocument,
)

private data class CoordinateDocument(
    val typeName: String,
    val fieldName: String,
)

private data class FragmentPlanDocument(
    val ownerName: String,
    val selections: List<FragmentSelectionDocument>,
)

private data class FragmentSelectionDocument(
    val fieldName: String,
    val arguments: List<ArgumentDocument>,
    val subselections: List<FragmentSelectionDocument>,
    val typeCondition: String?,
    val alias: String?,
)

private data class ArgumentDocument(
    val name: String,
    val value: InputValuePlanDocument,
)

private data class InputTypeDocument(
    val kind: String,
    val nullable: Boolean,
    val scalar: ScalarKind?,
    val name: String?,
    val element: InputTypeDocument?,
)

private data class InputValuePlanDocument(
    val kind: String,
    val type: InputTypeDocument?,
    val literal: String?,
    val elements: List<InputValuePlanDocument>,
    val fields: List<InputFieldDocument>,
    val variableName: String?,
    val placeholder: InputValuePlanDocument?,
)

private data class InputFieldDocument(
    val name: String,
    val value: InputValuePlanDocument,
)

private data class ValuePlanDocument(
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

private data class OutputFieldDocument(
    val coordinate: CoordinateDocument,
    val value: ValuePlanDocument,
)

private data class VariableProviderDocument(
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

private fun ArbitrarySchema.toDocument(): SchemaDocument =
    SchemaDocument(
        sdl = sdl,
        objects = objects.map(ObjectDefinition::toDocument),
        query = query.toDocument(),
        hashType = hashType.toDocument(),
        interfaces = interfaces.map(InterfaceDefinitionSpec::toDocument),
        unions = unions.map(UnionDefinitionSpec::toDocument),
        inputObjects = inputObjects.map(InputObjectDefinitionSpec::toDocument),
        deepFields = deepFields.toSortedMap(),
        features = features,
    )

private fun SchemaDocument.toSchema(): ArbitrarySchema =
    ArbitrarySchema(
        sdl = sdl,
        objects = objects.map(ObjectDefinitionDocument::toObjectDefinition),
        query = query.toObjectDefinition(),
        hashType = hashType.toObjectDefinition(),
        interfaces = interfaces.map(InterfaceDefinitionDocument::toInterfaceDefinition),
        unions = unions.map(UnionDefinitionDocument::toUnionDefinition),
        inputObjects = inputObjects.map(InputObjectDefinitionDocument::toInputObjectDefinition),
        deepFields = deepFields,
        features = features,
    )

private fun ObjectDefinition.toDocument(): ObjectDefinitionDocument =
    ObjectDefinitionDocument(
        name = name,
        implementsNode = implementsNode,
        interfaces = interfaces,
        fields = fields.map(FieldDefinitionSpec::toDocument),
    )

private fun ObjectDefinitionDocument.toObjectDefinition(): ObjectDefinition =
    ObjectDefinition(
        name = name,
        implementsNode = implementsNode,
        interfaces = interfaces,
        fields = fields.map(FieldDefinitionDocument::toFieldDefinition),
    )

private fun InterfaceDefinitionSpec.toDocument(): InterfaceDefinitionDocument =
    InterfaceDefinitionDocument(
        name = name,
        members = members,
        fields = fields.map(FieldDefinitionSpec::toDocument),
    )

private fun InterfaceDefinitionDocument.toInterfaceDefinition(): InterfaceDefinitionSpec =
    InterfaceDefinitionSpec(
        name = name,
        members = members,
        fields = fields.map(FieldDefinitionDocument::toFieldDefinition),
    )

private fun UnionDefinitionSpec.toDocument(): UnionDefinitionDocument = UnionDefinitionDocument(name, members)

private fun UnionDefinitionDocument.toUnionDefinition(): UnionDefinitionSpec = UnionDefinitionSpec(name, members)

private fun FieldDefinitionSpec.toDocument(): FieldDefinitionDocument =
    FieldDefinitionDocument(
        ownerName = ownerName,
        name = name,
        type = type.toDocument(),
        arguments = arguments.map(ArgumentDefinitionSpec::toDocument),
    )

private fun FieldDefinitionDocument.toFieldDefinition(): FieldDefinitionSpec =
    FieldDefinitionSpec(
        ownerName = ownerName,
        name = name,
        type = type.toOutputType(),
        arguments = arguments.map(ArgumentDefinitionDocument::toArgumentDefinition),
    )

private fun ArgumentDefinitionSpec.toDocument(): ArgumentDefinitionDocument =
    ArgumentDefinitionDocument(
        name = name,
        type = type.toDocument(),
        hasDefaultValue = defaultValue != null,
    )

private fun ArgumentDefinitionDocument.toArgumentDefinition(): ArgumentDefinitionSpec =
    ArgumentDefinitionSpec(
        name = name,
        type = type.toInputTypeSpec(),
        defaultValue =
            if (hasDefaultValue) {
                StringValue.newStringValue("benchmark-default").build()
            } else {
                null
            },
    )

private fun OutputTypeSpec.toDocument(): OutputTypeDocument =
    OutputTypeDocument(
        namedType = namedType,
        nullable = nullable,
        list = list,
        elementNullable = elementNullable,
        nestedElementNullabilities = nestedElementNullabilities,
    )

private fun OutputTypeDocument.toOutputType(): OutputTypeSpec =
    OutputTypeSpec(
        namedType = namedType,
        nullable = nullable,
        list = list,
        elementNullable = elementNullable,
        nestedElementNullabilities = nestedElementNullabilities,
    )

private fun InputObjectDefinitionSpec.toDocument(): InputObjectDefinitionDocument =
    InputObjectDefinitionDocument(
        name = name,
        fields =
            fields.map { field ->
                InputFieldDefinitionDocument(field.name, field.type.toDocument())
            },
    )

private fun InputObjectDefinitionDocument.toInputObjectDefinition(): InputObjectDefinitionSpec =
    InputObjectDefinitionSpec(
        name = name,
        fields =
            fields.map { field ->
                InputFieldDefinitionSpec(field.name, field.type.toInputTypeSpec())
            },
    )

private fun ArbitraryRegistry.toRegistryDocument(): RegistryDocument =
    RegistryDocument(
        fieldResolvers =
            fieldValues
                .keys
                .sortedBy(FieldCoordinate::toString)
                .map { coordinate ->
                    FieldResolverDocument(
                        coordinate = coordinate.toDocument(),
                        value = fieldValues.getValue(coordinate).toDocument(),
                        fragment = objectFragments.getValue(coordinate).toDocument(),
                        program = resolverPrograms.getValue(coordinate),
                        queryFragment = queryFragments.getValue(coordinate).toDocument(),
                    )
                },
        nodeResolvers =
            nodeValues
                .keys
                .sorted()
                .map { typeName ->
                    NodeResolverDocument(
                        typeName = typeName,
                        value = nodeValues.getValue(typeName).toDocument(),
                    )
                },
        variables =
            variableProviders
                .sortedWith(
                    compareBy<VariableProviderPlan>(
                        { provider -> provider.owner.toString() },
                        VariableProviderPlan::variableName,
                    ),
                ).map(VariableProviderPlan::toDocument),
        features = features,
    )

private fun RegistryDocument.toRegistry(): ArbitraryRegistry {
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
        arguments =
            arguments
                .toSortedMap()
                .map { (name, value) -> ArgumentDocument(name, value.toDocument()) },
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

private fun InputTypeSpec.toDocument(): InputTypeDocument =
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

private fun InputTypeDocument.toInputTypeSpec(): InputTypeSpec =
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
                fields =
                    fields
                        .toSortedMap()
                        .map { (name, value) -> InputFieldDocument(name, value.toDocument()) },
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
        "variable" -> VariableInputPlan(requireNotNull(variableName))
        "error" -> ErrorInputPlan(requireNotNull(placeholder).toInputValuePlan())
        else -> error("Unknown input value plan kind $kind")
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
                    fields
                        .entries
                        .sortedBy { (coordinate, _) -> coordinate.toString() }
                        .map { (coordinate, value) ->
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
