package viaduct.engine.api

import graphql.language.Field
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class TemporaryBypassAccessCheckTest {
    private val mockField = mockk<Field> {
        every { hasDirective(any()) } returns false
    }
    private val mockBypassField = mockk<Field> {
        every { hasDirective("bypassPolicyCheck") } returns true
    }

    @Test
    fun neverBypassIndividualField() {
        assertFalse(TemporaryBypassAccessCheck.Default.shouldBypassCheck(mockField, false))
        assertFalse(TemporaryBypassAccessCheck.Default.shouldBypassCheck(mockBypassField, false))
    }

    @Test
    fun bypassViaFlag() {
        assertTrue(TemporaryBypassAccessCheck.Default.shouldBypassCheck(mockField, true))
        assertTrue(TemporaryBypassAccessCheck.Default.shouldBypassCheck(mockField, true))
    }
}
