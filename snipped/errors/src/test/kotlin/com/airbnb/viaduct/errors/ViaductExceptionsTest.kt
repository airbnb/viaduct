package com.airbnb.viaduct.errors

import graphql.ErrorType
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ViaductExceptionsTest {
    companion object {
        val EXECUTION_PATH = ResultPath.parse("/path/to/field")
    }

    lateinit var dataFetchingEnvironmentMock: DataFetchingEnvironment

    lateinit var executionStepInfoMock: ExecutionStepInfo

    lateinit var fieldMock: Field

    @BeforeEach
    fun setUp() {
        dataFetchingEnvironmentMock = mockk<DataFetchingEnvironment>()
        executionStepInfoMock = mockk<ExecutionStepInfo>()
        fieldMock = mockk<Field>()
        every {
            dataFetchingEnvironmentMock.field
        } returns fieldMock
        every { dataFetchingEnvironmentMock.executionStepInfo } returns executionStepInfoMock
        every { executionStepInfoMock.path } returns EXECUTION_PATH
        every { fieldMock.name } returns "testField"
        every { fieldMock.sourceLocation } returns SourceLocation(6, 10)
    }

    @Test
    fun testViaductClientInputException() {
        val testErrorMessage = "Bad input"
        val testException = ViaductClientInputException(testErrorMessage)

        assertEquals(testErrorMessage, testException.message)
        assertEquals(ViaductErrorType.ClientInput, testException.getErrorType())
    }

    @Test
    fun testViaductPermissionDeniedException() {
        val testErrorMessage = "Permission denied"
        val testException = ViaductPermissionDeniedException(testErrorMessage)

        assertEquals(testErrorMessage, testException.message)
        assertEquals(ViaductErrorType.PermissionDenied, testException.getErrorType())
        assertEquals(
            testErrorMessage,
            testException.toGraphQLError(dataFetchingEnvironmentMock).extensions["localizedMessage"]
        )
        assertEquals(
            false,
            testException.toGraphQLError(dataFetchingEnvironmentMock)
                .extensions[ViaductException.ERROR_EXTENSIONS_KEY_POLICY_CHECK_FAILED_AT_PARENT_OBJECT]
        )
    }

    @Test
    fun testViaductExceptionToGraphQLError() {
        val testErrorMessage = "An error occurred"
        val testException = ViaductException(testErrorMessage)

        val graphQLError = testException.toGraphQLError(dataFetchingEnvironmentMock)

        assertTrue(graphQLError.message.contains(testErrorMessage))
        assertEquals(ErrorType.DataFetchingException, graphQLError.errorType)
        assertEquals(ViaductErrorType.Internal.value, graphQLError.extensions["errorType"])
        assertEquals(ViaductErrorType.Internal.fatal, graphQLError.extensions["fatal"])
        assertEquals(DEFAULT_LOCALIZED_MESSAGE_MAP["localizedMessage"], graphQLError.extensions["localizedMessage"])
        assertEquals(
            SourceLocation(6, 10),
            graphQLError.locations.first()
        )
        assertEquals<List<*>>(
            EXECUTION_PATH.toList(),
            graphQLError.path
        )
    }

    @Test
    fun testViaductExceptionToGraphQLErrorWithExtensions() {
        val testErrorMessage = "An error occurred with extensions"
        val testException = ViaductException(testErrorMessage)

        val graphQLError = testException.toGraphQLError(mapOf("test" to "anotherValue"))

        assertTrue(graphQLError.message.contains(testErrorMessage))
        assertEquals(ErrorType.DataFetchingException, graphQLError.errorType)
        assertEquals(ViaductErrorType.Internal.value, graphQLError.extensions["errorType"])
        assertEquals(ViaductErrorType.Internal.fatal, graphQLError.extensions["fatal"])
        assertEquals("anotherValue", graphQLError.extensions["test"])
    }
}
