package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import graphql.parser.InvalidSyntaxException
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CompletionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.api.internal.EngineSelectionSetProvider
import viaduct.api.reflect.Type as KotlinType
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.FullSchema
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.mocks.createEngineSelectionSetFactory
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.errors.FrameworkException
import viaduct.java.api.internal.InternalContext
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.EngineExecutionContextWrapperImpl

class OperationSelectionsParityTest {
    class OperationResult(val context: InternalContext, val data: EngineObjectData.Sync)

    private class Facades(val operation: String) : AutoCloseable {
        val typeName = if (operation == "query") "ReadRoot" else "WriteRoot"
        val schema = FullSchema(
            SchemaGenerator().makeExecutableSchema(
                SchemaParser().parse(
                    """
                    schema { query: ReadRoot mutation: WriteRoot }
                    type ReadRoot { query: ReadRoot echo(value: String): String other: String }
                    type WriteRoot { echo(value: String): String other: String }
                    """.trimIndent()
                ),
                RuntimeWiring.MOCKED_WIRING,
            )
        )
        val data = mockk<EngineObjectData.Sync>()
        val resolvedSelections = slot<EngineSelectionSet>()
        val engineContext = mockk<EngineExecutionContext> {
            every { activeSchema } returns schema
            every { fullSchema } returns schema
            every { globalIDCodec } returns GlobalIDCodecDefault
            every { engineSelectionSetFactory } returns createEngineSelectionSetFactory(schema)
            coEvery { resolveSelectionSet(capture(resolvedSelections), any()) } returns data
        }
        val knownFragments = CachedDocumentParser.parseDocument(
            """
            fragment External on $typeName { ...Leaf }
            fragment Leaf on $typeName { echo(value: ${'$'}value) }
            fragment Unused on $typeName { ...Missing }
            """.trimIndent()
        ).getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name }
        val kotlin = EngineExecutionContextWrapperImpl(engineContext, knownFragments)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val java = JavaEngineContextDelegate(
            engineContext,
            coroutineScope = scope,
            knownFragments = knownFragments,
            canExecuteMutations = true,
        )
        val type = object : KotlinType<CompositeOutput> {
            override val name = typeName
            override val kcls = CompositeOutput::class
        }

        fun javaOperation(
            document: String,
            variables: Map<String, Any?> = emptyMap(),
        ) = if (operation == "query") {
            java.queryOperation(document, variables, OperationResult::class.java)
        } else {
            java.mutationOperation(document, variables, OperationResult::class.java)
        }

        fun javaSelections(
            selections: String,
            variables: Map<String, Any?> = emptyMap(),
        ) = if (operation == "query") {
            java.query(selections, variables, OperationResult::class.java)
        } else {
            java.mutation(selections, variables, OperationResult::class.java)
        }

        override fun close() = scope.cancel()
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "mutation"])
    fun `operation document forms produce equivalent selections and propagate variables`(operation: String) {
        Facades(operation).use { facades ->
            val fields = "echo(value: \$value) other @include(if: \$includeOther)"
            val documents = listOf(
                fields,
                "{ $fields }",
                "$operation Named(\$value: String, \$includeOther: Boolean!) { $fields }",
                "# comment\n$operation { $fields }",
                "fragment Main on ${facades.typeName} { $fields }",
                "fragment Custom on ${facades.typeName} { $fields }",
                "$operation { ...Local } fragment Local on ${facades.typeName} { $fields }",
            )
            val variables = mapOf("value" to "hello", "includeOther" to false)
            documents.forEach { document ->
                val kotlin = facades.kotlin.selectionsForOperation(facades.type, document, variables) as EngineSelectionSetProvider
                val result = facades.javaOperation(document, variables).join()
                val java = facades.resolvedSelections.captured

                assertEquals(facades.typeName, java.type, document)
                assertEquals(kotlin.engineSelectionSet.type, java.type, document)
                assertEquals(kotlin.engineSelectionSet.printAsFieldSet(), java.printAsFieldSet(), document)
                assertEquals(kotlin.engineSelectionSet.variables, java.variables, document)
                assertEquals(mapOf("value" to "hello"), java.argumentsOfSelection(facades.typeName, "echo"), document)
                assertEquals(listOf("echo"), java.selections().map { it.fieldName }, document)
                assertSame(facades.data, result.data)
                assertSame(facades.schema, result.context.schema)
                assertSame(GlobalIDCodecDefault, result.context.globalIDCodec)
            }
            val options = if (operation == "query") ResolveSelectionSetOptions.DEFAULT else ResolveSelectionSetOptions.MUTATION
            coVerify(exactly = documents.size) { facades.engineContext.resolveSelectionSet(any(), options) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "mutation"])
    fun `reachable external fragments resolve transitively while unreachable fragments stay unused`(operation: String) {
        Facades(operation).use { facades ->
            val document = "$operation { ...External }"
            val variables = mapOf("value" to "from fragment")
            val kotlin = facades.kotlin.selectionsForOperation(facades.type, document, variables) as EngineSelectionSetProvider
            facades.javaOperation(document, variables).join()
            val java = facades.resolvedSelections.captured

            assertEquals(kotlin.engineSelectionSet.printAsFieldSet(), java.printAsFieldSet())
            assertEquals(listOf("echo"), java.selections().map { it.fieldName })
            assertEquals(mapOf("value" to "from fragment"), java.argumentsOfSelection(facades.typeName, "echo"))
        }
    }

    @Test
    fun `local fragments shadow known fragments`() {
        Facades("query").use { facades ->
            val document = "query { ...External } fragment External on ReadRoot { other }"
            val kotlin = facades.kotlin.selectionsForOperation(facades.type, document, emptyMap()) as EngineSelectionSetProvider
            facades.javaOperation(document).join()
            val java = facades.resolvedSelections.captured

            assertEquals(kotlin.engineSelectionSet.printAsFieldSet(), java.printAsFieldSet())
            assertEquals(listOf("other"), java.selections().map { it.fieldName })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "mutation"])
    fun `preparation failures retain each language attribution boundary`(operation: String) {
        Facades(operation).use { facades ->
            val failures = listOf(
                "$operation { echo(" to InvalidSyntaxException::class.java,
                "$operation { ...Missing }" to IllegalArgumentException::class.java,
                "$operation A { echo } $operation B { other }" to IllegalArgumentException::class.java,
                "fragment Main on WrongRoot { echo }" to IllegalArgumentException::class.java,
            )
            failures.forEach { (document, causeType) ->
                val kotlinError = assertThrows<FrameworkException> {
                    facades.kotlin.selectionsForOperation(facades.type, document, emptyMap())
                }
                val future = facades.javaOperation(document)
                val javaError = assertInstanceOf(FrameworkException::class.java, assertThrows<CompletionException> { future.join() }.cause)

                assertTrue(kotlinError.message!!.startsWith("selectionsForOperation ("))
                assertTrue(javaError.message!!.startsWith("$operation ("))
                assertInstanceOf(causeType, kotlinError.cause)
                assertInstanceOf(causeType, javaError.cause)
                assertEquals(kotlinError.cause!!.message, javaError.cause!!.message)
            }
            coVerify(exactly = 0) { facades.engineContext.resolveSelectionSet(any(), any()) }
        }
    }

    @Test
    fun `Java plain selections stay distinct from annotated operations`() {
        Facades("query").use { facades ->
            val fields = "echo(value: \$value)"
            val variables = mapOf("value" to "plain")
            facades.javaSelections(fields, variables).join()
            val plainSelections = facades.resolvedSelections.captured
            assertEquals("ReadRoot", plainSelections.type)
            assertEquals(listOf("echo"), plainSelections.selections().map { it.fieldName })
            assertEquals(mapOf("value" to "plain"), plainSelections.argumentsOfSelection("ReadRoot", "echo"))

            val document = "query { echo }"
            facades.javaSelections(document).join()
            val plainDocument = facades.resolvedSelections.captured
            assertEquals(listOf("query"), plainDocument.selections().map { it.fieldName })
            assertEquals(listOf("echo"), plainDocument.selectionSetForField("ReadRoot", "query").selections().map { it.fieldName })

            facades.javaOperation(document).join()
            assertEquals(listOf("echo"), facades.resolvedSelections.captured.selections().map { it.fieldName })

            assertInstanceOf(FrameworkException::class.java, assertThrows<CompletionException> { facades.javaSelections("...External").join() }.cause)
        }
    }
}
