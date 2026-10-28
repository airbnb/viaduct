package viaduct.engine.runtime2.arbitrary

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import graphql.language.StringValue
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import viaduct.engine.runtime2.model.testing.TestWorld

/** A fixed schema and executable registry recipe for resolver microbenchmarks. */
class ResolverBenchmarkCorpus private constructor(
    val schema: ArbitrarySchema,
    val registry: ArbitraryRegistry,
    val metrics: Map<String, Long>,
) {
    val schemaSDL: String
        get() = schema.sdl

    fun world(
        fieldCheckerMode: GeneratedFieldCheckerMode = GeneratedFieldCheckerMode.NONE,
        typeCheckerMode: GeneratedTypeCheckerMode = GeneratedTypeCheckerMode.NONE,
    ): TestWorld =
        registry.world(
            schemaSDL = schemaSDL,
            fieldCheckerMode = fieldCheckerMode,
            typeCheckerMode = typeCheckerMode,
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
            val schema = document.schema.toSchema(document.schema.sdl ?: schemaSDL)
            require(schema.sdl.trim() == schemaSDL.trim()) {
                "Resolver benchmark schema resource does not match registry query model"
            }
            return ResolverBenchmarkCorpus(
                schema = schema,
                registry = document.registry.toRegistry(schema),
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
                }.use { stream ->
                    val input = if (registryResource.endsWith(".gz")) GZIPInputStream(stream) else stream
                    input.bufferedReader().use { reader -> reader.readText() }
                }
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
    return corpusMapper
        .writer()
        .without(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(document) + "\n"
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
    val sdl: String? = null,
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

private fun SchemaDocument.toSchema(sdl: String): ArbitrarySchema =
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
        // Corpora written before OutputTypeSpec required it can carry elementNullable on non-list types.
        elementNullable = list && elementNullable,
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
