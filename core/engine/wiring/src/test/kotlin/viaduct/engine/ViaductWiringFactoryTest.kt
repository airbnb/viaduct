package viaduct.engine

import graphql.TypeResolutionEnvironment
import graphql.execution.DataFetcherResult
import graphql.language.Field
import graphql.language.InterfaceTypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.UnionWiringEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.observability.ResolverOutputContext
import viaduct.engine.runtime.observability.ResolverOutputMissingFieldException
import viaduct.service.api.spi.ErrorReporter

class ViaductWiringFactoryTest {
    @Test
    fun `test is enabled`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        assertTrue(subject.providesTypeResolver(mockk<UnionWiringEnvironment>()))
        assertTrue(subject.providesTypeResolver(mockk<InterfaceWiringEnvironment>()))
        assertNotNull(ViaductWiringFactory.buildRuntimeWiring(DefaultCoroutineInterop))
    }

    @Test
    fun `test default data fetcher fetches properties`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val defaultDataFetcher = subject.getDefaultDataFetcher(mockk<FieldWiringEnvironment>())

        val dataFetchingEnvironment = mockk<DataFetchingEnvironment> {
            every { field } returns mockk<Field> {
                every { name } returns "foo"
            }
            every { fieldType } returns mockk<GraphQLOutputType>()
            every { getSource<Any>() } returns mapOf("foo" to "bar")
        }
        assertEquals("bar", defaultDataFetcher.get(dataFetchingEnvironment))
    }

    @Test
    fun `default data fetcher reports a missing synchronous field and returns null`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val defaultDataFetcher = dataFetcher()
        val source = syncSource(isPresent = false)
        val requestContext = Any()

        val result = defaultDataFetcher.get(
            dataFetchingEnvironment(source, resolverOutputContext(reportedErrors), requestContext)
        )

        assertNull(result)
        assertEquals(1, reportedErrors.size)
        val reportedError = reportedErrors.single()
        assertEquals(
            "Resolver output did not contain requested field `Result.foo`",
            reportedError.message,
        )
        assertEquals("foo", reportedError.metadata.fieldName)
        assertEquals("Result", reportedError.metadata.parentType)
        assertEquals(false, reportedError.metadata.isFrameworkError)
        assertNull(reportedError.metadata.resolvers)
        assertSame(requestContext, reportedError.metadata.requestContext)
        assertTrue(reportedError.exception is ResolverOutputMissingFieldException)
    }

    @Test
    fun `default data fetcher does not report an explicitly null synchronous field`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val defaultDataFetcher = dataFetcher()
        val source = syncSource(isPresent = true)

        val result = defaultDataFetcher.get(
            dataFetchingEnvironment(source, resolverOutputContext(reportedErrors))
        )

        assertNull(result)
        assertTrue(reportedErrors.isEmpty())
    }

    @Test
    fun `default data fetcher returns a field error for a missing synchronous field when enabled`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val source = syncSource(isPresent = false)

        val result = dataFetcher().get(
            dataFetchingEnvironment(
                source,
                resolverOutputContext(
                    reportedErrors,
                    missingFieldErrorsEnabled = true,
                ),
            )
        ) as DataFetcherResult<*>

        assertNull(result.data)
        assertEquals(1, result.errors.size)
        assertEquals(
            "Resolver output did not contain requested field `Result.foo`",
            result.errors.single().message,
        )
        assertEquals(
            ResolverOutputMissingFieldException.GRAPHQL_ERROR_CODE,
            result.errors.single().extensions["code"],
        )
        assertEquals(1, reportedErrors.size)
    }

    @Test
    fun `default data fetcher skips presence lookup for a non-null synchronous field`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val defaultDataFetcher = dataFetcher()
        val source = syncSource(isPresent = false, value = "value")

        val result = defaultDataFetcher.get(
            dataFetchingEnvironment(source, resolverOutputContext(reportedErrors))
        )

        assertEquals("value", result)
        assertTrue(reportedErrors.isEmpty())
        verify(exactly = 0) { source.isPresent("foo") }
    }

    @Test
    fun `default data fetcher skips presence lookup without resolver output context`() {
        val defaultDataFetcher = dataFetcher()
        val source = syncSource(isPresent = false)

        val result = defaultDataFetcher.get(dataFetchingEnvironment(source, CompositeLocalContext.empty))

        assertNull(result)
        verify(exactly = 0) { source.isPresent("foo") }
    }

    @Test
    fun `default data fetcher reports a missing asynchronous field and returns null`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val source = asyncSource(selections = emptyList())

        val result = fetchAsync(source, resolverOutputContext(reportedErrors))

        assertNull(result)
        assertEquals(1, reportedErrors.size)
        assertNull(reportedErrors.single().metadata.resolvers)
    }

    @Test
    fun `default data fetcher does not report an explicitly null asynchronous field`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val source = asyncSource(selections = listOf("foo"))

        val result = fetchAsync(source, resolverOutputContext(reportedErrors))

        assertNull(result)
        assertTrue(reportedErrors.isEmpty())
    }

    @Test
    fun `synchronous presence lookup failure does not change the null field value`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val source = mockk<EngineObjectData.Sync> {
            every { type } returns resultType
            every { getOrNull("foo") } returns null
            every { isPresent("foo") } throws IllegalStateException("presence lookup failed")
        }

        val result = dataFetcher().get(
            dataFetchingEnvironment(source, resolverOutputContext(reportedErrors))
        )

        assertNull(result)
        assertTrue(reportedErrors.isEmpty())
    }

    @Test
    fun `asynchronous presence lookup failure does not change the null field value`() {
        val reportedErrors = mutableListOf<ReportedError>()
        val source = mockk<EngineObjectData> {
            every { type } returns resultType
            coEvery { fetchOrNull("foo") } returns null
            coEvery { fetchSelections() } throws IllegalStateException("presence lookup failed")
        }

        val result = fetchAsync(source, resolverOutputContext(reportedErrors))

        assertNull(result)
        assertTrue(reportedErrors.isEmpty())
    }

    @Test
    fun `default data fetcher returns null when missing field reporter throws`() {
        val defaultDataFetcher =
            ViaductWiringFactory(DefaultCoroutineInterop)
                .getDefaultDataFetcher(mockk<FieldWiringEnvironment>())
        val source = syncSource(isPresent = false)
        val context = resolverOutputContext(
            ErrorReporter { _, _, _ -> error("reporting failed") }
        )

        val result = defaultDataFetcher.get(dataFetchingEnvironment(source, context))

        assertNull(result)
    }

    @Test
    fun `default data fetcher returns a field error when enabled and reporter throws`() {
        val source = syncSource(isPresent = false)
        val context = resolverOutputContext(
            errorReporter = ErrorReporter { _, _, _ -> error("reporting failed") },
            missingFieldErrorsEnabled = true,
        )

        val result = dataFetcher().get(
            dataFetchingEnvironment(source, context)
        ) as DataFetcherResult<*>

        assertNull(result.data)
        assertEquals(1, result.errors.size)
        assertEquals(
            "Resolver output did not contain requested field `Result.foo`",
            result.errors.single().message,
        )
        assertEquals(
            ResolverOutputMissingFieldException.GRAPHQL_ERROR_CODE,
            result.errors.single().extensions["code"],
        )
    }

    @Test
    fun `type resolvers for interfaces return ResolvedEngineObjectData object types`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val expectedObjectType = mockk<GraphQLObjectType>()
        val interfaceResolver = subject.getTypeResolver(mockk<InterfaceWiringEnvironment>())
        val actualObjectType = interfaceResolver.getType(
            mockk<TypeResolutionEnvironment> {
                every { getObject<Any>() } returns mockk<ResolvedEngineObjectData> {
                    every { type } returns expectedObjectType
                }
            }
        )

        assertEquals(expectedObjectType, actualObjectType)
    }

    @Test
    fun `type resolvers for unions return ResolvedEngineObjectData object types`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val expectedObjectType = mockk<GraphQLObjectType>()
        val interfaceResolver = subject.getTypeResolver(mockk<UnionWiringEnvironment>())
        val actualObjectType = interfaceResolver.getType(
            mockk<TypeResolutionEnvironment> {
                every { getObject<Any>() } returns mockk<ResolvedEngineObjectData> {
                    every { type } returns expectedObjectType
                }
            }
        )

        assertEquals(expectedObjectType, actualObjectType)
    }

    @Test
    fun `type resolvers that do not return EngineObjectData throw`() {
        val subject = ViaductWiringFactory(DefaultCoroutineInterop)
        val interfaceResolver = subject.getTypeResolver(
            mockk<InterfaceWiringEnvironment> {
                every { interfaceTypeDefinition } returns mockk<InterfaceTypeDefinition> {
                    every { name } returns "InterfaceName"
                }
            }
        )

        assertThrows<IllegalStateException> {
            interfaceResolver.getType(
                mockk<TypeResolutionEnvironment> {
                    every { getObject<Any>() } returns mockk<Any>()
                }
            )
        }

        val unionResolver = subject.getTypeResolver(
            mockk<UnionWiringEnvironment> {
                every { unionTypeDefinition } returns mockk<UnionTypeDefinition> {
                    every { name } returns "UnionName"
                }
            }
        )

        assertThrows<IllegalStateException> {
            unionResolver.getType(
                mockk<TypeResolutionEnvironment> {
                    every { getObject<Any>() } returns mockk<Any>()
                }
            )
        }
    }

    private fun dataFetcher() =
        ViaductWiringFactory(DefaultCoroutineInterop)
            .getDefaultDataFetcher(mockk<FieldWiringEnvironment>())

    private fun syncSource(
        isPresent: Boolean,
        value: Any? = null,
    ) = mockk<EngineObjectData.Sync> {
        every { type } returns resultType
        every { getOrNull("foo") } returns value
        every { isPresent("foo") } returns isPresent
    }

    private fun asyncSource(selections: List<String>) =
        mockk<EngineObjectData> {
            every { type } returns resultType
            coEvery { fetchOrNull("foo") } returns null
            coEvery { fetchSelections() } returns selections
        }

    private fun fetchAsync(
        source: EngineObjectData,
        localContext: CompositeLocalContext,
    ): Any? {
        val future =
            DefaultCoroutineInterop.enterThreadLocalCoroutineContext(EmptyCoroutineContext) {
                dataFetcher().get(dataFetchingEnvironment(source, localContext)) as CompletionStage<*>
            }.thenCompose { it }
        return future.toCompletableFuture().get(5, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    private fun dataFetchingEnvironment(
        source: EngineObjectData,
        localContext: CompositeLocalContext,
        requestContext: Any = Any(),
    ) = mockk<DataFetchingEnvironment>(relaxed = true) {
        every { field } returns mockk<Field>(relaxed = true) {
            every { name } returns "foo"
        }
        every { getSource<Any?>() } returns source
        every { getLocalContext<CompositeLocalContext?>() } returns localContext
        every { getContext<Any>() } returns requestContext
    }

    private fun resolverOutputContext(
        reportedErrors: MutableList<ReportedError>,
        missingFieldErrorsEnabled: Boolean = false,
    ) = resolverOutputContext(
        errorReporter = ErrorReporter { exception, errorMessage, metadata ->
            reportedErrors += ReportedError(exception, errorMessage, metadata)
        },
        missingFieldErrorsEnabled = missingFieldErrorsEnabled,
    )

    private fun resolverOutputContext(
        errorReporter: ErrorReporter,
        missingFieldErrorsEnabled: Boolean = false,
    ) = CompositeLocalContext.withContexts(
        ResolverOutputContext(
            errorReporter = errorReporter,
            missingFieldErrorsEnabled = missingFieldErrorsEnabled,
        )
    )

    private data class ReportedError(
        val exception: Throwable,
        val message: String,
        val metadata: ErrorReporter.Metadata,
    )

    private companion object {
        val resultType: GraphQLObjectType = mockk {
            every { name } returns "Result"
        }
    }
}
