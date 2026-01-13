package viaduct.engine.runtime.execution

import com.airbnb.viaduct.errors.ViaductException
import graphql.Scalars
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLAppliedDirectiveArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLType
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletionException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantResolverException
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.ResolverErrorBuilder

class ViaductDataFetcherExceptionHandlerTest {
    lateinit var exceptionHandler: ViaductDataFetcherExceptionHandler
    lateinit var reporter: ErrorReporter

    val capturedThrowables = mutableListOf<Throwable>()
    val capturedMetadata = mutableListOf<ErrorReporter.Metadata>()

    @BeforeEach
    fun setUp() {
        reporter = ErrorReporter { exception, _, metadata ->
            capturedThrowables.add(exception)
            capturedMetadata.add(metadata)
        }
        exceptionHandler = ViaductDataFetcherExceptionHandler(reporter, ResolverErrorBuilder.NOOP)
        capturedThrowables.clear()
        capturedMetadata.clear()
    }

    @Test
    fun handleException() {
        val throwable = ViaductException("message")
        val dfe =
            mockk<DataFetchingEnvironment> {
                every { executionStepInfo } returns mockStepInfo
                every { field } returns mockField
                every { parentType } returns mockk<GraphQLType>()
                every { fieldType } returns mockk<GraphQLOutputType>()
                every { operationDefinition } returns mockk {
                    every { name } returns "operationName"
                }
                every { fieldDefinition } returns mockk<GraphQLFieldDefinition> {
                    every { name } returns "fieldName"
                    every { type } returns mockk {
                        every { name } returns "parentType"
                    }
                    every { definition } returns mockk {
                        every { sourceLocation } returns SourceLocation(1, 1)
                    }
                }
            }
        val params =
            DataFetcherExceptionHandlerParameters
                .newExceptionParameters()
                .exception(throwable)
                .dataFetchingEnvironment(dfe)
                .build()
        val result = exceptionHandler.handleException(params).join()

        assertEquals(1, result.errors.size)
        assertEquals("message", capturedThrowables.first().message)
        assertEquals(result.errors[0].extensions["fieldName"], capturedMetadata[0].fieldName)
        assertEquals(result.errors[0].extensions["operationName"], capturedMetadata[0].operationName)
    }

    @Test
    fun handleExceptionWithMetadata() {
        val throwable = ViaductException("message")

        val params = mockParamsWithDirectives(throwable)
        val result = exceptionHandler.handleException(params).join()

        assertEquals(1, result.errors.size)
        assertEquals("message", capturedThrowables.first().message)
        val extensions = result.errors[0].extensions
        assertTrue(extensions.containsKey("fieldName"))
        assertTrue(extensions.containsKey("parentType"))
        assertTrue(extensions.containsKey("localizedMessage"))
        assertFalse(extensions.containsKey("isFrameworkError"))
        assertEquals("Sorry, something went wrong. Please try again later.", extensions["localizedMessage"])

        val metadata = capturedMetadata.first()
        assertEquals("fieldName", metadata.fieldName)
        assertEquals("String", metadata.parentType)
        assertEquals("operationName", metadata.operationName)
        assertEquals(result.errors[0].extensions["fieldName"], capturedMetadata[0].fieldName)
        assertEquals(result.errors[0].extensions["operationName"], capturedMetadata[0].operationName)
    }

    @Test
    fun handleExceptionWithMetadataNullExceptionMessage() {
        val throwable = ViaductException()

        val params = mockParamsWithDirectives(throwable)
        val result = exceptionHandler.handleException(params).join()

        assertEquals(1, result.errors.size)
        assertNull(capturedThrowables.first().message)
        val extensions = result.errors[0].extensions
        assertTrue(extensions.containsKey("fieldName"))
        assertTrue(extensions.containsKey("parentType"))
        assertTrue(extensions.containsKey("localizedMessage"))
        assertFalse(extensions.containsKey("isFrameworkError"))
        assertEquals("Sorry, something went wrong. Please try again later.", extensions["localizedMessage"])

        val metadata = capturedMetadata.first()
        assertEquals("fieldName", metadata.fieldName)
        assertEquals("String", metadata.parentType)
        assertEquals("operationName", metadata.operationName)
        assertEquals(result.errors[0].extensions["fieldName"], capturedMetadata[0].fieldName)
        assertEquals(result.errors[0].extensions["operationName"], capturedMetadata[0].operationName)
    }

    @Test
    fun handleFieldFetchingException() {
        val cause = ViaductException("TEST MESSAGE")
        val err = FieldFetchingException.wrapWithPathAndLocation(cause, ResultPath.rootPath(), SourceLocation.EMPTY)

        val params = mockParamsWithDirectives(err)
        val result = exceptionHandler.handleException(params).join()

        assertEquals(1, result.errors.size)
        assertContains(result.errors.first().message, "TEST MESSAGE")
        assertEquals(result.errors[0].extensions["fieldName"], capturedMetadata[0].fieldName)
        assertEquals(result.errors[0].extensions["operationName"], capturedMetadata[0].operationName)

        assertEquals(cause, capturedThrowables.first())
    }

    @Test
    fun handleTenantResolverException() {
        // Mocking because ViaductTenantResolverException has an internal constructor
        val inner = mockk<ViaductTenantResolverException>(relaxed = true) {
            every { cause } returns RuntimeException("foo")
            every { resolver } returns "Human.name"
            every { message } returns "inner tenant resolver exception message"
        }
        val throwable = mockk<ViaductTenantResolverException>(relaxed = true) {
            every { cause } returns inner
            every { resolver } returns "Starship.pilot"
            every { message } returns "outer tenant resolver exception message"
        }

        val params = mockParamsWithDirectives(throwable)
        val result = exceptionHandler.handleException(params).join()

        assertEquals(1, result.errors.size)
        assertEquals("java.lang.RuntimeException: foo", result.errors.first().message)

        assertEquals(listOf("Starship.pilot", "Human.name"), capturedMetadata.first().resolvers)
        assertEquals(false, capturedMetadata.first().isFrameworkError)

        assertEquals("false", result.errors.first().extensions["isFrameworkError"])
        assertEquals(capturedMetadata[0].fieldName, result.errors[0].extensions["fieldName"])
        assertEquals(capturedMetadata[0].operationName, result.errors[0].extensions["operationName"])
    }

    @Test
    fun handleFrameworkError() {
        val inner = RuntimeException("bar")
        // Mocking because ViaductFrameworkException has an internal constructor
        val throwable = mockk<ViaductFrameworkException>(relaxed = true) {
            every { cause } returns inner
            every { message } returns "foo"
        }

        val params = mockParamsWithDirectives(throwable)
        val result = exceptionHandler.handleException(params).join()

        assertEquals(1, capturedThrowables.size)
        assertTrue(capturedThrowables.first() is ViaductFrameworkException)

        assertEquals(true, capturedMetadata.first().isFrameworkError)

        assertEquals(1, result.errors.size)
        assertEquals("viaduct.api.ViaductFrameworkException: foo", result.errors.first().message)

        assertEquals("true", result.errors.first().extensions["isFrameworkError"])
        assertEquals(capturedMetadata[0].fieldName, result.errors[0].extensions["fieldName"])
        assertEquals(capturedMetadata[0].operationName, result.errors[0].extensions["operationName"])
    }

    @Test
    fun handleArbitraryExceptionNesting() {
        val actualException = IllegalStateException("actual error")

        val tenantResolverException = mockk<ViaductTenantResolverException>(relaxed = true) {
            every { cause } returns actualException
            every { resolver } returns "Test.field"
            every { message } returns "tenant resolver message"
        }

        val completionException = CompletionException(tenantResolverException)

        val fieldFetchingException = FieldFetchingException.wrapWithPathAndLocation(
            completionException,
            ResultPath.rootPath(),
            SourceLocation.EMPTY
        )

        val outerCompletionException = CompletionException(fieldFetchingException)

        val params = mockParamsWithDirectives(outerCompletionException)
        val result = exceptionHandler.handleException(params).join()

        // The actual unwrapped error should be the IllegalStateException
        assertEquals(actualException, capturedThrowables.first())
        assertEquals("java.lang.IllegalStateException: actual error", result.errors.first().message)

        // For metadata, only concurrency exceptions are unwrapped, so we preserve the top-most
        // Viaduct exception (FieldFetchingException), which doesn't have a resolver chain
        assertNull(capturedMetadata.first().resolvers)
    }

    @Test
    fun handleFieldFetchingExceptionWrappingCompletionException() {
        val actualException = RuntimeException("deep error")
        val completionException = CompletionException(actualException)

        val fieldFetchingException = FieldFetchingException.wrapWithPathAndLocation(
            completionException,
            ResultPath.rootPath(),
            SourceLocation.EMPTY
        )

        val params = mockParamsWithDirectives(fieldFetchingException)
        val result = exceptionHandler.handleException(params).join()

        assertEquals(actualException, capturedThrowables.first())
        assertContains(result.errors.first().message, "deep error")
    }

    private fun mockParamsWithDirectives(exception: Throwable): DataFetcherExceptionHandlerParameters {
        val tenantDirective =
            buildDirective(
                "projectInternal",
                buildArgument("name", "projectName")
            )
        val fieldDef =
            mockk<GraphQLFieldDefinition> {
                every { name } returns "fieldName"
                every { getAppliedDirective(eq("projectInternal")) } returns tenantDirective
                every { definition } returns mockk {
                    every { sourceLocation } returns SourceLocation(1, 1, "repo/schema/data/someFile")
                }
            }

        val dfe =
            mockk<DataFetchingEnvironment> {
                every { fieldDefinition } returns fieldDef
                every { executionStepInfo } returns mockStepInfo
                every { field } returns mockField
                every { parentType } returns Scalars.GraphQLString
                every { fieldType } returns Scalars.GraphQLString
                every { operationDefinition } returns mockk {
                    every { name } returns "operationName"
                }
            }

        return DataFetcherExceptionHandlerParameters
            .newExceptionParameters()
            .exception(exception)
            .dataFetchingEnvironment(dfe)
            .build()
    }

    private fun buildDirective(
        directiveName: String,
        argument: GraphQLAppliedDirectiveArgument
    ): GraphQLAppliedDirective =
        GraphQLAppliedDirective
            .newDirective()
            .name(directiveName)
            .argument(argument)
            .build()

    private fun buildArgument(
        name: String,
        value: String
    ) = GraphQLAppliedDirectiveArgument
        .newArgument()
        .type(Scalars.GraphQLString)
        .name(name)
        .valueProgrammatic(value)
        .build()

    companion object {
        val mockStepInfo = mockk<graphql.execution.ExecutionStepInfo> {
            every { path } returns ResultPath.parse("/path/to/result")
        }

        val mockField = mockk<graphql.language.Field> {
            every { sourceLocation } returns SourceLocation.EMPTY
        }
    }
}
