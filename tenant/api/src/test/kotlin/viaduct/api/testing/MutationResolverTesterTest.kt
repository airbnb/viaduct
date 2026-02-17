@file:Suppress("ForbiddenImport")

package viaduct.api.testing

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.api.ResolverBase
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest

@OptIn(VisibleForTest::class, InternalApi::class)
class MutationResolverTesterTest {
    companion object {
        private const val TEST_SCHEMA_SDL = """
            type Query {
                test: String
            }

            type Mutation {
                createTest(input: CreateTestInput!): CreateTestPayload!
            }

            input CreateTestInput {
                name: String!
            }

            type CreateTestPayload {
                test: TestObject
            }

            type TestObject {
                id: ID!
                name: String!
            }
        """

        private const val ERROR_MISSING_ARGUMENTS = "arguments must be set"
    }

    private val tester = MutationResolverTester.create<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>(
        ResolverTester.TesterConfig(schemaSDL = TEST_SCHEMA_SDL)
    )

    private val noArgsTester = MutationResolverTester.create<MockMutationQuery, MockMutation, Arguments.NoArguments, MockMutationPayload>(
        ResolverTester.TesterConfig(schemaSDL = TEST_SCHEMA_SDL)
    )

    @Test
    fun `test - simple mutation resolver`() =
        runBlocking {
            val result = tester.test(SimpleMutationResolver()) {
                arguments = MockMutationArguments("test")
            }

            assertEquals("mutation-result", result.result)
        }

    @Test
    fun `test - argument-aware mutation resolver`() =
        runBlocking {
            val result = tester.test(ArgumentAwareMutationResolver()) {
                arguments = MockMutationArguments("foo")
            }

            assertEquals("created-foo", result.result)
        }

    @Test
    fun `test - missing arguments throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.test(SimpleMutationResolver()) {
                    // arguments not set
                }
            }
        }

        assertThat(exception.message).contains(ERROR_MISSING_ARGUMENTS)
    }

    @Test
    fun `test - tester has valid context`() {
        assertNotNull(tester.context)
        assertNotNull(tester.config)
        assertEquals(TEST_SCHEMA_SDL, tester.config.schemaSDL)
    }

    @Test
    fun `test - with NoArguments`() =
        runBlocking {
            val result = noArgsTester.test(NoArgsMutationResolver()) {
                arguments = Arguments.NoArguments
            }

            assertEquals("no-args-mutation", result.result)
        }

    @Test
    fun `test - with queryValue`() =
        runBlocking {
            val result = tester.test(QueryAwareMutationResolver()) {
                arguments = MockMutationArguments("test")
                queryValue = MockMutationQuery
            }

            assertEquals("query=MockMutationQuery", result.result)
        }

    @Test
    fun `test - with context query values`() =
        runBlocking {
            val result = tester.test(ContextQueryMutationResolver()) {
                arguments = MockMutationArguments("test")
                contextQueryValues = listOf(MockMutationQuery)
            }

            assertEquals("has-context-queries", result.result)
        }

    @Test
    fun `test - multiple arguments variations`() =
        runBlocking {
            val results = listOf("alice", "bob", "charlie").map { name ->
                tester.test(ArgumentAwareMutationResolver()) {
                    arguments = MockMutationArguments(name)
                }
            }

            assertEquals("created-alice", results[0].result)
            assertEquals("created-bob", results[1].result)
            assertEquals("created-charlie", results[2].result)
        }

    @Test
    fun `test - exception propagation`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                tester.test(ExceptionThrowingMutationResolver()) {
                    arguments = MockMutationArguments("test")
                }
            }
        }

        val cause = when (exception) {
            is java.lang.reflect.InvocationTargetException -> exception.targetException
            else -> exception
        }

        assertThat(cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(cause.message).isEqualTo("Test exception from resolver")
    }

    @Test
    fun `test - with null requestContext`() =
        runBlocking {
            val result = tester.test(RequestContextAwareMutationResolver()) {
                arguments = MockMutationArguments("test")
                requestContext = null
            }

            assertEquals("no-context", result.result)
        }

    @Test
    fun `test - with non-null requestContext`() =
        runBlocking {
            val result = tester.test(RequestContextAwareMutationResolver()) {
                arguments = MockMutationArguments("test")
                requestContext = "test-context"
            }

            assertEquals("has-context", result.result)
        }
}

internal object MockMutationQuery : Query

internal object MockMutation : Mutation

internal class MockMutationArguments(val name: String) : Arguments

internal class MockMutationPayload(val result: String) : CompositeOutput

@OptIn(InternalApi::class)
internal class SimpleMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        return MockMutationPayload("mutation-result")
    }
}

@OptIn(InternalApi::class)
internal class ArgumentAwareMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        val name = ctx.arguments.name
        return MockMutationPayload("created-$name")
    }
}

@OptIn(InternalApi::class)
internal class NoArgsMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, Arguments.NoArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, Arguments.NoArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        return MockMutationPayload("no-args-mutation")
    }
}

@OptIn(InternalApi::class)
internal class QueryAwareMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        val queryVal = ctx.queryValue
        return MockMutationPayload("query=${queryVal::class.simpleName}")
    }
}

@OptIn(InternalApi::class)
internal class ContextQueryMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        return MockMutationPayload("has-context-queries")
    }
}

@OptIn(InternalApi::class)
internal class ExceptionThrowingMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        throw IllegalStateException("Test exception from resolver")
    }
}

@OptIn(InternalApi::class)
internal class RequestContextAwareMutationResolver : ResolverBase<MockMutationPayload> {
    class Context(val inner: MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload>) :
        MutationFieldExecutionContext<MockMutationQuery, MockMutation, MockMutationArguments, MockMutationPayload> by inner

    suspend fun resolve(ctx: Context): MockMutationPayload {
        val hasContext = ctx.requestContext != null
        return MockMutationPayload(if (hasContext) "has-context" else "no-context")
    }
}
