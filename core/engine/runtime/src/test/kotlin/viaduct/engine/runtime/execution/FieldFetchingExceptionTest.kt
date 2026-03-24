package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.language.SourceLocation
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.errors.PassthroughException

class FieldFetchingExceptionTest {
    @Test
    fun `prevent recursive wrapping`() {
        val err = RuntimeException()
        val base = assertDoesNotThrow {
            FieldFetchingException.wrapWithPathAndLocation(err, ResultPath.rootPath(), SourceLocation.EMPTY)
        }

        assertThrows<IllegalArgumentException> {
            FieldFetchingException.wrapWithPathAndLocation(base, ResultPath.rootPath(), SourceLocation.EMPTY)
        }
    }

    @Test
    fun `FieldFetchingException is PassthroughException`() {
        val exception = FieldFetchingException.wrapWithPathAndLocation(
            RuntimeException(),
            ResultPath.rootPath(),
            SourceLocation.EMPTY,
        )
        assertIs<PassthroughException>(exception)
    }
}
