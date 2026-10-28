@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.propertytest

import graphql.language.AstPrinter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.runtime2.arbitrary.ArbitraryQuery
import viaduct.engine.runtime2.arbitrary.ArbitraryRegistry
import viaduct.engine.runtime2.arbitrary.ArbitrarySchema
import viaduct.engine.runtime2.arbitrary.FieldDefinitionSpec
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_ID_PROPERTY
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_MARKER_PROPERTY
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import viaduct.engine.runtime2.arbitrary.RESOLVER_TEST_SEED_PROPERTY
import viaduct.engine.runtime2.arbitrary.ResolverTestBatch
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.generateResolverTestBatches
import viaduct.engine.runtime2.arbitrary.withResolverProperties
import viaduct.engine.runtime2.resolution.ResolutionBroadStressProfile
import viaduct.utils.string.sha256Hash

class ResolverTestSuiteTest {
    @Test
    fun `shard codec reproduces broad generated batches exactly`(): Unit =
        runBlocking {
            ResolutionBroadStressProfile.entries.forEach { profile ->
                val batch = generateResolverTestBatches(TestCaseCount(1, 2, 2), profile.config, seed = 7L).single()

                val shard = ResolverTestShardCodec.encode("b001", batch)
                val decoded = ResolverTestShardCodec.decode("b001", shard.sdl, shard.json)

                assertEquals(batch.snapshot(), decoded.snapshot(), "profile ${profile.id}")
                assertTrue(ResolverTestShardCodec.encode("b001", decoded).json.contentEquals(shard.json), "profile ${profile.id}")
                assertEquals(ResolverTestShardCodec.ids(batch), ResolverTestShardCodec.ids(decoded), "profile ${profile.id}")
            }
        }

    @Test
    fun `shard codec drops identical registries and queries and keeps the first copies in order`(): Unit =
        runBlocking {
            val batch = generateResolverTestBatches(TestCaseCount(1, 2, 2), ResolutionBroadStressProfile.entries.first().config, seed = 7L).single()
            val repeated =
                batch.copy(
                    registries = listOf(batch.registries[0], batch.registries[1], batch.registries[0]),
                    queries = listOf(batch.queries[1], batch.queries[1], batch.queries[0]),
                )

            val unique = ResolverTestShardCodec.withoutDuplicates(repeated)

            assertEquals(listOf(batch.registries[0], batch.registries[1]).map(ArbitraryRegistry::snapshot), unique.registries.map(ArbitraryRegistry::snapshot))
            assertEquals(listOf(batch.queries[1], batch.queries[0]).map(ArbitraryQuery::snapshot), unique.queries.map(ArbitraryQuery::snapshot))
            val ids = ResolverTestShardCodec.ids(repeated)
            assertEquals(ids.registries[0], ids.registries[2])
            assertEquals(ids.queries[0], ids.queries[1])
        }

    @Test
    fun `materializer keeps every batch that shares a schema and stores the schema once`(): Unit =
        runBlocking {
            val definition = ResolverTestSuiteDefinition.OBJECT_FRAGMENT
            val batch = generateResolverTestBatches(TestCaseCount(1, 2, 2), ResolutionBroadStressProfile.entries.first().config, seed = 7L).single()
            val first = batch.copy(registries = listOf(batch.registries[0]), queries = listOf(batch.queries[0]))
            val second = batch.copy(registries = listOf(batch.registries[1]), queries = listOf(batch.queries[1]))
            val root = Files.createTempDirectory("resolver-test-suite")

            ResolverTestSuiteMaterializer.write(root, definition, listOf(first, second))

            val entries = readResolverTestSuiteArchive(Files.readAllBytes(root.resolve(suiteArchiveResource(definition.id))))
            val manifest = PropertyTestJson.read<ResolverTestSuiteManifest>(entries.getValue(MANIFEST_ENTRY).inputStream())
            val schemaId = ResolverTestShardCodec.ids(batch).schema
            assertEquals(listOf(ResolverTestSuiteSchema(schemaId, ResolverTestShardCodec.encode("b001", batch).sdl.sha256Hash())), manifest.schemas)
            assertEquals(listOf("b001", "b002"), manifest.batches.map(ResolverTestSuiteBatch::id))
            val storedIds = manifest.batches.map { stored -> ResolverTestShardIds(stored.schema, stored.registries, stored.queries) }
            assertEquals(listOf(ResolverTestShardCodec.ids(first), ResolverTestShardCodec.ids(second)), storedIds)
            assertTrue(manifest.batches[0].registries != manifest.batches[1].registries && manifest.batches[0].queries != manifest.batches[1].queries)
            assertEquals(listOf("batch-b001.json.gz", "batch-b002.json.gz", "manifest.json", "schema-$schemaId.graphqls.gz"), entries.keys.sorted())
            val archive = Files.readAllBytes(root.resolve(suiteArchiveResource(definition.id)))
            ResolverTestSuiteMaterializer.write(root, definition, listOf(first, second))
            assertTrue(archive.contentEquals(Files.readAllBytes(root.resolve(suiteArchiveResource(definition.id)))), "rewriting a suite changed its archive bytes")
        }

    @ParameterizedTest
    @EnumSource(ResolverTestSuiteDefinition::class)
    fun `stored suite loads every shard`(definition: ResolverTestSuiteDefinition) {
        val suite = ResolverTestSuite.load(definition)

        val batches = suite.batches.map { batch -> suite.loadShard(batch.id) }

        assertEquals(suite.caseCount, batches.sumOf { batch -> batch.registries.size * batch.queries.size })
        assertEquals(suite.caseCount, suite.testCaseIds().toSet().size)
    }

    @ParameterizedTest
    @EnumSource(ResolverTestSuiteDefinition::class)
    fun `stored suite matches live generation for its seed`(definition: ResolverTestSuiteDefinition): Unit =
        runBlocking {
            val firstSchemaOnly = definition.counts.copy(schemas = 1)
            val batch = ResolverTestShardCodec.withoutDuplicates(generateResolverTestBatches(firstSchemaOnly, definition.config, definition.seed).single())
            val ids = ResolverTestShardCodec.ids(batch)
            val shard = ResolverTestShardCodec.encode("b001", batch)
            val stored = ResolverTestSuite.load(definition).batches.first()

            assertEquals(ResolverTestSuiteBatch("b001", ids.schema, shard.json.sha256Hash(), ids.registries, ids.queries), stored)
            val entries = readResolverTestSuiteArchive(PropertyTestJson.readResourceBytes(suiteArchiveResource(definition.id)))
            assertTrue(entries.getValue(sdlEntry(ids.schema)).contentEquals(shard.sdl), sdlEntry(ids.schema))
            assertTrue(entries.getValue(shardEntry("b001")).contentEquals(shard.json), shardEntry("b001"))
        }

    @Test
    fun `stored suite replays one case by ID without earlier shards`(): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.OBJECT_FRAGMENT)
            val batch = suite.batches[6]
            val selected = ResolverTestCaseId(suite.definition.id, batch.id, batch.registries[1], batch.queries[2])
            val expectedQuery = suite.loadShard(batch.id).queries[2].source
            val visited = mutableListOf<String>()

            val run =
                suite.replay(selected) { _, testCase ->
                    visited += "${testCase.testCaseId} ${testCase.coordinates?.summary()} ${testCase.query.source == expectedQuery}"
                }

            assertEquals(1, run.attemptedCases)
            assertEquals(listOf("$selected profile=object-fragment seed=1 S=7 R=2 Q=3 true"), visited)
            assertEquals(selected.toString(), run.selectedTestCase)
            assertTrue(run.stored && run.singleCase)
        }

    @Test
    fun `configured test case runs only in its suite`(): Unit =
        runBlocking {
            val selected = ResolverTestSuite.load(ResolverTestSuiteDefinition.OBJECT_FRAGMENT).testCaseIds().last()
            val visited = mutableListOf<String?>()

            withResolverProperties(RESOLVER_TEST_CASE_ID_PROPERTY to selected.toString()) {
                val run = checkResolverTestSuite(ResolverTestSuiteDefinition.OBJECT_FRAGMENT) { _, testCase -> visited += testCase.testCaseId }
                val unselected = checkResolverTestSuite(ResolverTestSuiteDefinition.NODE) { _, testCase -> visited += testCase.testCaseId }

                assertEquals(1, run.attemptedCases)
                assertEquals(0, unselected.attemptedCases)
                unselected.requireCoverage(false, "A suite that does not hold the selected case checks no coverage")
            }

            assertEquals(listOf<String?>(selected.toString()), visited)
        }

    @Test
    fun `only the suite that runs the selected case writes the run marker`(): Unit =
        runBlocking {
            val selected = ResolverTestSuite.load(ResolverTestSuiteDefinition.OBJECT_FRAGMENT).testCaseIds().first()
            val marker = Files.createTempDirectory("resolver-test-case").resolve("ran.txt")

            withResolverProperties(RESOLVER_TEST_CASE_ID_PROPERTY to selected.toString(), RESOLVER_TEST_CASE_MARKER_PROPERTY to marker.toString()) {
                checkResolverTestSuite(ResolverTestSuiteDefinition.NODE) { _, _ -> }
                assertFalse(Files.exists(marker))

                checkResolverTestSuite(ResolverTestSuiteDefinition.OBJECT_FRAGMENT) { _, _ -> }
            }

            assertEquals("$selected\n", Files.readString(marker))
        }

    @Test
    fun `configured test case rejects unknown suites and removed cases`(): Unit =
        runBlocking {
            val definition = ResolverTestSuiteDefinition.OBJECT_FRAGMENT
            val existing = ResolverTestSuite.load(definition).testCaseIds().first()
            val failures =
                listOf(
                    existing.copy(suite = "no-such-suite"),
                    existing.copy(batch = "b999"),
                    existing.copy(registry = "000000"),
                    existing.copy(query = "000000"),
                ).map { selected ->
                    withResolverProperties(RESOLVER_TEST_CASE_ID_PROPERTY to selected.toString()) {
                        assertFailsWith<IllegalArgumentException> { checkResolverTestSuite(definition) { _, _ -> } }.message.orEmpty()
                    }
                }

            assertContains(failures[0], "unknown resolver test suite no-such-suite")
            assertContains(failures[1], "holds no batch b999")
            assertContains(failures[2], "holds no registry 000000")
            assertContains(failures[3], "holds no query 000000")
            failures.drop(1).forEach { failure -> assertContains(failure, "removed or never existed") }
        }

    @Test
    fun `stored suite rejects positional case selection and test cases on live runs`(): Unit =
        runBlocking {
            val definition = ResolverTestSuiteDefinition.OBJECT_FRAGMENT
            val positional =
                withResolverProperties(RESOLVER_TEST_CASE_PROPERTY to "1:1:1") {
                    assertFailsWith<IllegalArgumentException> { checkResolverTestSuite(definition) { _, _ -> } }
                }
            val live =
                withResolverProperties(RESOLVER_TEST_SEED_PROPERTY to "1", RESOLVER_TEST_CASE_ID_PROPERTY to ResolverTestSuite.load(definition).testCaseIds().first().toString()) {
                    assertFailsWith<IllegalArgumentException> { checkResolverTestSuite(definition) { _, _ -> } }
                }

            assertContains(positional.message.orEmpty(), RESOLVER_TEST_CASE_ID_PROPERTY)
            assertContains(live.message.orEmpty(), "unset the configured seed and size")
        }

    @Test
    fun `stored case failure names its test case and rerun command`(): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.OBJECT_FRAGMENT)
            val selected = suite.testCaseIds()[1]

            val failure = assertFailsWith<AssertionError> { suite.replay(selected) { _, _ -> throw AssertionError("deliberate failure") } }

            assertContains(failure.message.orEmpty(), "Test case: $selected")
            assertContains(failure.message.orEmpty(), "-PresolverTestCase=$selected")
            assertFalse(failure.message.orEmpty().contains("-PresolverPropertySize"))
        }

    @Test
    fun `test case IDs round-trip and reject malformed values`() {
        val id = ResolverTestCaseId("node", "b004", "9c1e03", "77ab12")

        assertEquals("node/b004/9c1e03/77ab12", id.toString())
        assertEquals(id, ResolverTestCaseId.parse(id.toString()))
        listOf("node/b004/9c1e03", "node/b004/9c1e03/77ab12/extra", "node//9c1e03/77ab12", "1:2:3").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { ResolverTestCaseId.parse(value) }
        }
    }

    @Test
    fun `stored suite fails when its cases miss a required feature`(): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.OBJECT_FRAGMENT)
            var generatedNodeResolvers = 0

            val run =
                suite.replay { _, testCase ->
                    generatedNodeResolvers += testCase.registry.nodeResolverTypes.size
                }

            assertTrue(run.stored)
            assertEquals(suite.caseCount, run.attemptedCases)
            assertEquals(run.expectedCases, run.attemptedCases)
            assertFailsWith<AssertionError> {
                run.requireCoverage(generatedNodeResolvers > 0, "Object-fragment suite disables node resolvers")
            }
        }

    @Test
    fun `shard codec rejects unsupported versions and mismatched batches`() {
        val config = ResolutionBroadStressProfile.entries.first().config
        val batch = runBlocking { generateResolverTestBatches(TestCaseCount(1, 1, 1), config, seed = 3L) }.single()
        val shard = ResolverTestShardCodec.encode("b001", batch)

        val wrongBatch = assertFailsWith<IllegalArgumentException> { ResolverTestShardCodec.decode("b002", shard.sdl, shard.json) }
        assertContains(wrongBatch.message.orEmpty(), "holds batch b001; expected b002")

        val futureVersion = gzip("""{"formatVersion":99,"batchId":"b001"}""")
        val unsupported = assertFailsWith<IllegalArgumentException> { ResolverTestShardCodec.decode("b001", shard.sdl, futureVersion) }
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
