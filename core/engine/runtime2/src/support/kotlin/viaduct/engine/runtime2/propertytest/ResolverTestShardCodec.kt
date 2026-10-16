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

const val RESOLVER_TEST_SHARD_FORMAT_VERSION = 1

class ResolverTestShard(
    val sdl: ByteArray,
    val json: ByteArray,
)

object ResolverTestShardCodec {
    fun encode(
        schemaIndex: Int,
        batch: ResolverTestBatch,
    ): ResolverTestShard {
        val document =
            ShardPlansDocument(
                formatVersion = RESOLVER_TEST_SHARD_FORMAT_VERSION,
                schemaIndex = schemaIndex,
                schema =
                    SchemaMetadataDocument(
                        deepFields = batch.schema.deepFields,
                        rootFieldReferenceFamily = batch.schema.rootFieldReferenceFamily,
                        features = batch.schema.features,
                    ),
                registries = batch.registries.map(ArbitraryRegistry::toRegistryDocument),
                queries = batch.queries.map { query -> QueryDocument(query.source, query.permutationEquivalentSource, query.selectionDepth, query.features) },
            )
        return ResolverTestShard(gzip("${batch.schema.sdl}\n".toByteArray()), gzip(shardMapper.writeValueAsBytes(document)))
    }

    fun decode(
        expectedSchemaIndex: Int,
        sdl: ByteArray,
        json: ByteArray,
    ): ResolverTestBatch {
        val tree = shardMapper.readTree(gunzip(json))
        val formatVersion = tree.path("formatVersion").asInt()
        require(formatVersion == RESOLVER_TEST_SHARD_FORMAT_VERSION) {
            "Unsupported resolver test shard formatVersion $formatVersion"
        }
        val document = shardMapper.treeToValue<ShardPlansDocument>(tree)
        require(document.schemaIndex == expectedSchemaIndex) {
            "Resolver test shard holds schema ${document.schemaIndex}; expected $expectedSchemaIndex"
        }
        val schema =
            with(document.schema) {
                arbitrarySchemaFromSdl(gunzip(sdl).decodeToString().removeSuffix("\n"), deepFields, rootFieldReferenceFamily, features)
            }
        return ResolverTestBatch(
            schema = schema,
            registries = document.registries.map { registry -> registry.toRegistry(schema) },
            queries = document.queries.map { query -> ArbitraryQuery(query.source, query.permutationEquivalentSource, query.selectionDepth, query.features) },
        )
    }
}

private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { gzip -> gzip.readBytes() }

private data class ShardPlansDocument(
    val formatVersion: Int,
    val schemaIndex: Int,
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
