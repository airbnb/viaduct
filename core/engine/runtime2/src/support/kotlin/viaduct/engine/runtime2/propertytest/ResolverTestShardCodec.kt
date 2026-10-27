package viaduct.engine.runtime2.propertytest

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.treeToValue
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import viaduct.engine.runtime2.arbitrary.ArbitraryQuery
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.QueryFeatures
import viaduct.engine.runtime2.arbitrary.RegistryDocument
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.RootFieldReferenceFamily
import viaduct.engine.runtime2.arbitrary.SchemaFeatures
import viaduct.engine.runtime2.arbitrary.arbitrarySchemaFromSdl
import viaduct.engine.runtime2.arbitrary.toRegistry
import viaduct.engine.runtime2.arbitrary.toRegistryDocument
import viaduct.utils.string.sha256Hash

const val RESOLVER_TEST_SHARD_FORMAT_VERSION = 2

class ResolverTestShard(
    val sdl: ByteArray,
    val json: ByteArray,
)

/** Content IDs of a schema and of its registries and queries, in shard order. */
data class ResolverTestShardIds(
    val schema: String,
    val registries: List<String>,
    val queries: List<String>,
)

object ResolverTestShardCodec {
    fun encode(
        batchId: String,
        batch: ResolverTestBatch,
    ): ResolverTestShard {
        val document =
            ShardPlansDocument(
                formatVersion = RESOLVER_TEST_SHARD_FORMAT_VERSION,
                batchId = batchId,
                schema =
                    SchemaMetadataDocument(
                        deepFields = batch.schema.deepFields,
                        rootFieldReferenceFamily = batch.schema.rootFieldReferenceFamily,
                        features = batch.schema.features,
                    ),
                registries = batch.registries.map(ArbitraryRegistry::toRegistryDocument),
                queries = batch.queries.map(ArbitraryQuery::toQueryDocument),
            )
        return ResolverTestShard(gzip("${batch.schema.sdl}\n".toByteArray()), gzip(shardMapper.writeValueAsBytes(document)))
    }

    fun decode(
        expectedBatchId: String,
        sdl: ByteArray,
        json: ByteArray,
    ): ResolverTestBatch {
        val tree = shardMapper.readTree(gunzip(json))
        val formatVersion = tree.path("formatVersion").asInt()
        require(formatVersion == RESOLVER_TEST_SHARD_FORMAT_VERSION) {
            "Unsupported resolver test shard formatVersion $formatVersion"
        }
        val document = shardMapper.treeToValue<ShardPlansDocument>(tree)
        require(document.batchId == expectedBatchId) {
            "Resolver test shard holds batch ${document.batchId}; expected $expectedBatchId"
        }
        val schema =
            with(document.schema) {
                arbitrarySchemaFromSdl(gunzip(sdl).decodeToString().removeSuffix("\n"), deepFields, rootFieldReferenceFamily, features)
            }
        return ResolverTestBatch(
            schema = schema,
            registries = document.registries.map { registry -> registry.toRegistry(schema) },
            queries = document.queries.map(QueryDocument::toArbitraryQuery),
        )
    }

    fun ids(batch: ResolverTestBatch): ResolverTestShardIds =
        ResolverTestShardIds(
            schema = schemaSha256(batch).take(SCHEMA_ID_LENGTH),
            registries = batch.registries.map { registry -> registrySha256(registry).take(CASE_PART_ID_LENGTH) },
            queries = batch.queries.map { query -> querySha256(query).take(CASE_PART_ID_LENGTH) },
        )

    /** Keeps the first of each identical registry and query; identical content is the same case. */
    fun withoutDuplicates(batch: ResolverTestBatch): ResolverTestBatch =
        batch.copy(
            registries = batch.registries.distinctBy(::registrySha256),
            queries = batch.queries.distinctBy(::querySha256),
        )

    private fun schemaSha256(batch: ResolverTestBatch): String = batch.schema.sdl.sha256Hash()

    private fun registrySha256(registry: ArbitraryRegistry): String = shardMapper.writeValueAsBytes(registry.toRegistryDocument()).sha256Hash()

    private fun querySha256(query: ArbitraryQuery): String = shardMapper.writeValueAsBytes(query.toQueryDocument()).sha256Hash()
}

private const val SCHEMA_ID_LENGTH = 8
private const val CASE_PART_ID_LENGTH = 6

private fun ArbitraryQuery.toQueryDocument(): QueryDocument = QueryDocument(source, permutationEquivalentSource, selectionDepth, features)

private fun QueryDocument.toArbitraryQuery(): ArbitraryQuery = ArbitraryQuery(source, permutationEquivalentSource, selectionDepth, features)

private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { gzip -> gzip.readBytes() }

private data class ShardPlansDocument(
    val formatVersion: Int,
    val batchId: String,
    val schema: SchemaMetadataDocument,
    val registries: List<RegistryDocument>,
    val queries: List<QueryDocument>,
)

private data class SchemaMetadataDocument(
    val deepFields: Map<String, String>,
    val rootFieldReferenceFamily: RootFieldReferenceFamily?,
    val features: SchemaFeatures,
)

private data class QueryDocument(
    val source: String,
    val permutationEquivalentSource: String,
    val selectionDepth: Int,
    val features: QueryFeatures,
)

// Unlike PropertyTestJson output, shards are minified and keep insertion order, which replay depends on.
private val shardMapper: JsonMapper =
    PropertyTestJson.mapper
        .rebuild()
        .addModule(
            SimpleModule().apply {
                @Suppress("UNCHECKED_CAST")
                addAbstractTypeMapping(Set::class.java, LinkedHashSet::class.java as Class<out Set<*>>)
            },
        ).disable(SerializationFeature.INDENT_OUTPUT, SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()
