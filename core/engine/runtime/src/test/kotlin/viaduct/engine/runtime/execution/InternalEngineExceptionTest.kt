package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.language.SourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class InternalEngineExceptionTest {
    @Test
    fun `prevent recursive wrapping`() {
        val err = RuntimeException()
        val base = assertDoesNotThrow {
            InternalEngineException.wrapWithPathAndLocation(err, ResultPath.rootPath(), SourceLocation.EMPTY)
        }

        assertThrows<IllegalArgumentException> {
            InternalEngineException.wrapWithPathAndLocation(base, ResultPath.rootPath(), SourceLocation.EMPTY)
        }
    }
}
