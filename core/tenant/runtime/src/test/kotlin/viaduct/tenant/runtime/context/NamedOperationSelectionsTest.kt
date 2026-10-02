@file:OptIn(ExperimentalApi::class, InternalApi::class)

package viaduct.tenant.runtime.context

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockMutationFieldExecutionContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.MockResolverExecutionContext
import viaduct.api.mocks.MockType
import viaduct.api.mocks.PrebakedResults
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSetFactory
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetImpl

private const val QUERY_OPERATION = """
query FindResult(${'$'}id: ID!, ${'$'}value: String!, ${'$'}showLabel: Boolean!) {
  find(id: ${'$'}id, value: ${'$'}value) { ...ResultFields }
}
"""

private const val MUTATION_OPERATION = """
mutation RecordResult(${'$'}id: ID!, ${'$'}value: String!, ${'$'}showLabel: Boolean!) {
  record(id: ${'$'}id, value: ${'$'}value) { ...ResultFields }
}
"""

private const val RESULT_FRAGMENT = """
fragment ResultFields on Result {
  id
  label @include(if: ${'$'}showLabel)
}
"""

class NamedOperationSelectionsTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { find(id: ID!, value: String!): Result }
        extend type Mutation { record(id: ID!, value: String!): Result }
        type Result implements Node { id: ID!, label: String }
        """
    )
    private val queryType = MockType("Query", Query::class)
    private val mutationType = MockType("Mutation", Mutation::class)
    private val nodeType = MockType.mkNodeObject("Result")
    private val internalContext = MockInternalContext(
        schema,
        reflectionLoader = MockReflectionLoader(queryType, mutationType, nodeType),
    )
    private val selectionSetFactory = SelectionSetFactoryImpl(createEngineSelectionSetFactory(schema), GlobalIDCodecDefault)
    private val queryResults = CapturingResults<Query>(QueryResult)
    private val mutationResults = CapturingResults<Mutation>(MutationResult)
    private val variables = mapOf("id" to GlobalID(nodeType, "123"), "value" to "hello", "showLabel" to false)

    @Test
    fun `mock query retains named fragments and normalizes GlobalID variables`() =
        runTest {
            assertSame(QueryResult, queryContext().query(FindResult, variables))

            assertSelections(queryResults.selections, "Query", "find")
        }

    @Test
    fun `mock mutation retains named fragments and normalizes GlobalID variables`() =
        runTest {
            assertSame(MutationResult, mutationContext().mutation(RecordResult, variables))

            assertSelections(mutationResults.selections, "Mutation", "record")
        }

    @Test
    fun `mock query fails clearly without a selection factory`() =
        runTest {
            val error = assertThrows<UnsupportedOperationException> {
                queryContext(null).query(FindResult, variables)
            }

            assertTrue(error.message.orEmpty().contains("selectionSetFactory"))
        }

    private fun queryContext(factory: SelectionSetFactory? = selectionSetFactory) = MockResolverExecutionContext<Query>(internalContext, queryResults, factory)

    private fun mutationContext() =
        MockMutationFieldExecutionContext<Query, Mutation, Arguments.NoArguments, NodeObject>(
            queryValue = QueryResult,
            arguments = Arguments.NoArguments,
            requestContext = null,
            selectionsValue = SelectionSet.empty(nodeType),
            internalContext = internalContext,
            mutationResults = mutationResults,
            selectionSetFactory = selectionSetFactory,
        )

    private fun assertSelections(
        selections: SelectionSet<*>,
        rootType: String,
        field: String
    ) {
        val engineSelections = (selections as SelectionSetImpl<*>).engineSelectionSet
        assertEquals(rootType, engineSelections.type)
        assertEquals(
            mapOf("id" to GlobalIDCodecDefault.serialize("Result", "123"), "value" to "hello"),
            engineSelections.argumentsOfSelection(rootType, field),
        )
        val resultSelections = engineSelections.selectionSetForField(rootType, field)
        assertTrue(resultSelections.containsField("Result", "id"))
        assertFalse(resultSelections.containsField("Result", "label"))
    }

    private class CapturingResults<T : CompositeOutput>(private val result: T) : PrebakedResults<T> {
        lateinit var selections: SelectionSet<T>

        override fun get(selections: SelectionSet<T>): T {
            this.selections = selections
            return result
        }
    }

    private object QueryResult : Query

    private object MutationResult : Mutation

    @GraphQLOperation(QUERY_OPERATION + RESULT_FRAGMENT)
    private object FindResult : QueryFromAnnotation()

    @GraphQLOperation(MUTATION_OPERATION + RESULT_FRAGMENT)
    private object RecordResult : MutationFromAnnotation()
}
