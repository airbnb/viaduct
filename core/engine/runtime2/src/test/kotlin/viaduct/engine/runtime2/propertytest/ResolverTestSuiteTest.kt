@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import graphql.language.AstPrinter
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.runtime2.arbitrary.ArbitraryQuery
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.ArbitrarySchema
import viaduct.engine.runtime2.arbitrary.FieldDefinitionSpec
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.ResolverTestCaseCoordinate
import viaduct.engine.runtime2.arbitrary.ResolverTestExecution
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.generateResolverTestBatches
import viaduct.engine.runtime2.resolution.ResolutionBroadStressProfile

class ResolverTestSuiteTest {
    @Test
    fun `shard codec reproduces broad generated batches exactly`(): Unit =
        runBlocking {
            ResolutionBroadStressProfile.entries.forEach { profile ->
                val batch = generateResolverTestBatches(TestCaseCount(1, 2, 2), profile.config, seed = 7L).single()

                val shard = ResolverTestShardCodec.encode(1, batch)
                val decoded = ResolverTestShardCodec.decode(1, shard.sdl, shard.json)

                assertEquals(batch.snapshot(), decoded.snapshot(), "profile ${profile.id}")
                assertTrue(ResolverTestShardCodec.encode(1, decoded).json.contentEquals(shard.json), "profile ${profile.id}")
            }
        }

    @ParameterizedTest
    @EnumSource(ResolverTestSuiteDefinition::class)
    fun `stored suite loads every shard`(definition: ResolverTestSuiteDefinition) {
        val suite = ResolverTestSuite.load(definition)

        val batches = (1..definition.counts.schemas).map(suite::loadShard)

        assertEquals(
            definition.counts.run { schemas * registriesPerSchema * queriesPerSchema },
            batches.sumOf { batch -> batch.registries.size * batch.queries.size },
        )
    }

    @ParameterizedTest
    @EnumSource(ResolverTestSuiteDefinition::class)
    fun `stored suite matches live generation for its seed`(definition: ResolverTestSuiteDefinition): Unit =
        runBlocking {
            val firstSchemaOnly = definition.counts.copy(schemas = 1)
            val shard = ResolverTestShardCodec.encode(1, generateResolverTestBatches(firstSchemaOnly, definition.config, definition.seed).single())

            assertTrue(PropertyTestJson.readResourceBytes(sdlResource(definition.id, 1)).contentEquals(shard.sdl), sdlResource(definition.id, 1))
            assertTrue(PropertyTestJson.readResourceBytes(shardResource(definition.id, 1)).contentEquals(shard.json), shardResource(definition.id, 1))
        }

    @Test
    fun `stored suite replays one case without earlier shards`(): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.OBJECT_FRAGMENT)
            val selected = ResolverTestCaseCoordinate(schemaIndex = 7, registryIndex = 2, queryIndex = 3)
            val expectedQuery = suite.loadShard(7).queries[2].source
            val visited = mutableListOf<String>()

            val run =
                suite.replay(ResolverTestExecution(suite.definition.counts, selected)) { _, testCase ->
                    visited += "${testCase.coordinates?.summary()} ${testCase.query.source == expectedQuery}"
                }

            assertEquals(1, run.attemptedCases)
            assertEquals(listOf("profile=object-fragment seed=1 S=7 R=2 Q=3 true"), visited)
        }

    @Test
    fun `stored object-fragment suite loads every shard`() {
        val definition = ResolverTestSuiteDefinition.OBJECT_FRAGMENT
        val suite = ResolverTestSuite.load(definition)

        val batches = (1..definition.counts.schemas).map(suite::loadShard)

        assertEquals(
            definition.counts.run { schemas * registriesPerSchema * queriesPerSchema },
            batches.sumOf { batch -> batch.registries.size * batch.queries.size },
        )
    }

    @Test
    fun `stored object-fragment suite matches live generation for its seed`(): Unit =
        runBlocking {
            val definition = ResolverTestSuiteDefinition.OBJECT_FRAGMENT
            val firstSchemaOnly = definition.counts.copy(schemas = 1)
            val shard = ResolverTestShardCodec.encode(1, generateResolverTestBatches(firstSchemaOnly, definition.config, definition.seed).single())

            assertTrue(PropertyTestJson.readResourceBytes(sdlResource(definition.id, 1)).contentEquals(shard.sdl), sdlResource(definition.id, 1))
            assertTrue(PropertyTestJson.readResourceBytes(shardResource(definition.id, 1)).contentEquals(shard.json), shardResource(definition.id, 1))
        }

    @Test
    fun `shard codec rejects unsupported versions and mismatched schemas`() {
        val config = ResolutionBroadStressProfile.entries.first().config
        val batch = runBlocking { generateResolverTestBatches(TestCaseCount(1, 1, 1), config, seed = 3L) }.single()
        val shard = ResolverTestShardCodec.encode(1, batch)

        val wrongSchema = assertFailsWith<IllegalArgumentException> { ResolverTestShardCodec.decode(2, shard.sdl, shard.json) }
        assertTrue(wrongSchema.message!!.contains("expected 2"))

        val futureVersion = gzip("""{"formatVersion":99,"schemaIndex":1}""")
        val unsupported = assertFailsWith<IllegalArgumentException> { ResolverTestShardCodec.decode(1, shard.sdl, futureVersion) }
        assertEquals("Unsupported resolver test shard formatVersion 99", unsupported.message)
    }
}

private fun gzip(json: String): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        GZIPOutputStream(bytes).use { gzip -> gzip.write(json.toByteArray()) }
        bytes.toByteArray()
    }

private fun ResolverTestBatch.snapshot(): List<Any?> = listOf(schema.snapshot(), registries.map(ArbitraryRegistry::snapshot), queries.map(ArbitraryQuery::snapshot))

// graphql-java Values lack structural equality, so argument defaults are compared as printed literals.
private fun ArbitrarySchema.snapshot(): List<Any?> =
    listOf(
        sdl,
        (objects + query + hashType).map { definition -> definition.copy(fields = emptyList()) to definition.fields.map(FieldDefinitionSpec::snapshot) },
        interfaces.map { definition -> listOf(definition.name, definition.members.toList(), definition.fields.map(FieldDefinitionSpec::snapshot)) },
        unions.map { union -> union.name to union.members.toList() },
        enums,
        inputObjects,
        deepFields.toList(),
        rootFieldReferenceFamily,
        features,
    )

private fun FieldDefinitionSpec.snapshot(): List<Any?> =
    listOf(
        copy(arguments = arguments.map { argument -> argument.copy(defaultValue = null) }),
        arguments.map { argument -> argument.defaultValue?.let(AstPrinter::printAst) },
    )

private fun ArbitraryRegistry.snapshot(): List<Any?> =
    listOf(
        fieldResolverCoordinates.toList(),
        nodeResolverTypes.toList(),
        outputSelectionSets.toList(),
        objectFragmentSources.toList(),
        queryFragmentSources.toList(),
        variableProviderSources.toList(),
        fieldValues.toList(),
        nodeValues.toList(),
        objectFragments.toList(),
        queryFragments.toList(),
        variableProviders,
        resolverPrograms.toList(),
        parentDemandOwnerFields.toList(),
        schemaRootFieldReferenceFamily,
        features,
    )

private fun ArbitraryQuery.snapshot(): List<Any?> = listOf(source, permutationEquivalentSource, selectionDepth, features)
