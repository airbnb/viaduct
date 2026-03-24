package viaduct.engine.api.fragment.errors

import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.Field
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class FragmentResolutionErrorTest {
    private data class TestImplementationFragmentFieldResoluitionError(
        override val message: String,
        override val cause: Throwable?,
        override val viaductErrorType: String?,
        override val fatal: Boolean
    ) : IFragmentFieldResolutionError

    @Test
    fun `test self implemented classes `() {
        val throwable = Throwable()
        val fragmentFieldResoluitionError = TestImplementationFragmentFieldResoluitionError(
            message = "",
            cause = throwable,
            viaductErrorType = "viaduct-error",
            fatal = true
        )
        assertEquals("", fragmentFieldResoluitionError.message)
        assertEquals(throwable, fragmentFieldResoluitionError.cause)
        assertEquals("viaduct-error", fragmentFieldResoluitionError.viaductErrorType)
        assertEquals(true, fragmentFieldResoluitionError.fatal)
    }

    @Test
    fun `test field error implementation`() {
        val field = mockk<Field>()
        val fieldPath = mockk<ResultPath>()
        val fieldName = "fooField"
        val throwableMessage = "Error"
        val throwable = Throwable(throwableMessage)
        val fragmentFieldResoluitionError = FragmentFieldResolutionError(
            fieldName = fieldName,
            field = field,
            fieldPath = fieldPath,
            cause = throwable,
        )
        assertContains(fragmentFieldResoluitionError.message, fieldName)
        assertContains(fragmentFieldResoluitionError.message, throwableMessage)
        assertEquals(throwable, fragmentFieldResoluitionError.cause)
    }

    @Test
    fun `test field engine error implementation`() {
        val pathList = listOf("path", "of", "error")
        val graphQLError = mockk<GraphQLError> {
            every { message } returns "fooMessage"
            every { path } returns pathList
        }

        val fragmentFieldResoluitionError = FragmentFieldEngineResolutionError(
            graphQLError
        )

        assertEquals("fooMessage", fragmentFieldResoluitionError.message)
        assertEquals(pathList, fragmentFieldResoluitionError.path)
        assertEquals(pathList.joinToString(separator = "."), fragmentFieldResoluitionError.pathString)
    }

    @Test
    fun `test extension functions for filtering errors`() {
        val graphQLError1 = mockk<GraphQLError> {
            every { message } returns "fooMessage1"
            every { path } returns listOf("", "test1", "testend")
        }

        val graphQLError2 = mockk<GraphQLError> {
            every { message } returns "fooMessage2"
            every { path } returns listOf("", "test1", "teststart")
        }
        val graphQLError3 = mockk<GraphQLError> {
            every { message } returns "fooMessage3"
            every { path } returns listOf("", "test1", "testend")
        }
        val error1 = FragmentFieldEngineResolutionError(graphQLError1)
        val error2 = FragmentFieldEngineResolutionError(graphQLError2)
        val error3 = FragmentFieldEngineResolutionError(graphQLError3)

        val listOfErrors = listOf(
            error1,
            error2,
            error3
        )
        val listForField = listOfErrors.forField("testend")
        assertEquals(2, listForField.size)
        assertContains(listForField, error1)
        assertContains(listForField, error3)
        val listForFieldAndSubSelections = listOfErrors.forFieldAndSubSelections("")
        assertEquals(listOfErrors, listForFieldAndSubSelections)
    }
}
