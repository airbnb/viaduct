@file:Suppress("ForbiddenImport")

package viaduct.api.testing

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.ResolverBase
import viaduct.api.context.FieldExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.InternalApi
import viaduct.apiannotations.VisibleForTest

@OptIn(VisibleForTest::class, InternalApi::class)
class FieldResolverTesterTest {
    companion object {
        private const val TEST_SCHEMA_SDL = """
            type Query {
                test: String
            }

            type TestObject {
                id: ID!
                name: String!
                value(prefix: String): String!
            }
        """

        private const val ERROR_MISSING_OBJECT_VALUE = "objectValue must be set"
        private const val ERROR_MISSING_ARGUMENTS = "arguments must be set"
        private const val ERROR_EMPTY_OBJECT_VALUES = "objectValues must not be empty"
        private const val ERROR_MISMATCHED_SIZE = "same size"
    }

    private val tester = FieldResolverTester.create<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>(
        ResolverTester.TesterConfig(schemaSDL = TEST_SCHEMA_SDL)
    )

    private val noArgsTester = FieldResolverTester.create<MockFieldObject, MockFieldQuery, Arguments.NoArguments, MockFieldOutput>(
        ResolverTester.TesterConfig(schemaSDL = TEST_SCHEMA_SDL)
    )

    @Test
    fun `test - simple field resolver`() =
        runBlocking {
            val result = tester.test(SimpleFieldResolver()) {
                objectValue = MockFieldObject
                arguments = MockFieldArguments
            }

            assertEquals("test-result", result.value)
        }

    @Test
    fun `test - context-aware field resolver`() =
        runBlocking {
            val result = tester.test(ContextAwareFieldResolver()) {
                objectValue = MockFieldObject
                arguments = MockFieldArguments
            }

            assertEquals("obj=MockFieldObject,arg=MockFieldArguments", result.value)
        }

    @Test
    fun `test - missing objectValue throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.test(SimpleFieldResolver()) {
                    // objectValue not set
                    arguments = MockFieldArguments
                }
            }
        }

        assertThat(exception.message).contains(ERROR_MISSING_OBJECT_VALUE)
    }

    @Test
    fun `test - missing arguments throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.test(SimpleFieldResolver()) {
                    objectValue = MockFieldObject
                    // arguments not set
                }
            }
        }

        assertThat(exception.message).contains(ERROR_MISSING_ARGUMENTS)
    }

    @Test
    fun `testBatch - batch resolver with multiple objects`() =
        runBlocking {
            val results = tester.testBatch(BatchFieldResolver()) {
                objectValues = listOf(MockFieldObject, MockFieldObject, MockFieldObject)
            }

            assertEquals(3, results.size)
            assertEquals("batch-0", results[0].get().value)
            assertEquals("batch-1", results[1].get().value)
            assertEquals("batch-2", results[2].get().value)
        }

    @Test
    fun `testBatch - empty objectValues throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.testBatch(BatchFieldResolver()) {
                    objectValues = emptyList()
                }
            }
        }

        assertThat(exception.message).contains(ERROR_EMPTY_OBJECT_VALUES)
    }

    @Test
    fun `testBatch - mismatched objectValues and queryValues throws exception`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tester.testBatch(BatchFieldResolver()) {
                    objectValues = listOf(MockFieldObject, MockFieldObject)
                    queryValues = listOf(MockFieldQuery) // Only 1 query value for 2 objects
                }
            }
        }

        assertThat(exception.message).contains(ERROR_MISMATCHED_SIZE)
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
            val result = noArgsTester.test(NoArgsFieldResolver()) {
                objectValue = MockFieldObject
                arguments = Arguments.NoArguments
            }

            assertEquals("no-args", result.value)
        }

    @Test
    fun `test - with SelectionSet`() =
        runBlocking {
            val result1 = tester.test(SelectionAwareResolver()) {
                objectValue = MockFieldObject
                arguments = MockFieldArguments
            }
            assertEquals("no-selections", result1.value)
        }

    @Test
    fun `test - exception propagation`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                tester.test(ExceptionThrowingResolver()) {
                    objectValue = MockFieldObject
                    arguments = MockFieldArguments
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
            val result = tester.test(RequestContextAwareResolver()) {
                objectValue = MockFieldObject
                arguments = MockFieldArguments
                requestContext = null
            }

            assertEquals("no-context", result.value)
        }

    @Test
    fun `test - with non-null requestContext`() =
        runBlocking {
            val result = tester.test(RequestContextAwareResolver()) {
                objectValue = MockFieldObject
                arguments = MockFieldArguments
                requestContext = "test-context"
            }

            assertEquals("has-context", result.value)
        }
}

internal object MockFieldObject : Object

internal object MockFieldQuery : Query

internal object MockFieldArguments : Arguments

internal class MockFieldOutput(val value: String) : CompositeOutput

@OptIn(InternalApi::class)
internal class SimpleFieldResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        return MockFieldOutput("test-result")
    }
}

@OptIn(InternalApi::class)
internal class ContextAwareFieldResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        val objVal = ctx.objectValue
        val argVal = ctx.arguments
        return MockFieldOutput("obj=${objVal::class.simpleName},arg=${argVal::class.simpleName}")
    }
}

@OptIn(InternalApi::class)
internal class BatchFieldResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        return MockFieldOutput("single")
    }

    suspend fun batchResolve(contexts: List<Context>): List<FieldValue<MockFieldOutput>> {
        return contexts.mapIndexed { index, _ ->
            FieldValue.ofValue(MockFieldOutput("batch-$index"))
        }
    }
}

@OptIn(InternalApi::class)
internal class NoArgsFieldResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, Arguments.NoArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, Arguments.NoArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        return MockFieldOutput("no-args")
    }
}

@OptIn(InternalApi::class)
internal class SelectionAwareResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        return MockFieldOutput("no-selections")
    }
}

@OptIn(InternalApi::class)
internal class ExceptionThrowingResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        throw IllegalStateException("Test exception from resolver")
    }
}

@OptIn(InternalApi::class)
internal class RequestContextAwareResolver : ResolverBase<MockFieldOutput> {
    class Context(val inner: FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput>) :
        FieldExecutionContext<MockFieldObject, MockFieldQuery, MockFieldArguments, MockFieldOutput> by inner

    suspend fun resolve(ctx: Context): MockFieldOutput {
        val hasContext = ctx.requestContext != null
        return MockFieldOutput(if (hasContext) "has-context" else "no-context")
    }
}
