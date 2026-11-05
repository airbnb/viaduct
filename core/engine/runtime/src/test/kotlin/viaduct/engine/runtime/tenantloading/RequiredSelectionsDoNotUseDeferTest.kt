package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry

class RequiredSelectionsDoNotUseDeferTest {
    private val validator = RequiredSelectionsDoNotUseDefer()

    @Test
    fun `rejects @defer in resolver required selection sets`() {
        val registry = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntryForType("Query", "User" to "name", "... on Query @defer { __typename }")
            .build()

        val error = assertThrows<IllegalArgumentException> {
            validator.validate(RequiredSelectionsValidationCtx("User", "name", registry))
        }

        assertTrue(error.message!!.contains("User.name"), error.message)
        assertTrue(error.message!!.contains("@defer is not allowed in required selection sets."), error.message)
        assertTrue(error.message!!.contains("@defer in fragment 'Main' at"), error.message)
    }

    @Test
    fun `rejects @defer in field checker required selection sets`() {
        val registry = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("User" to "name", "__typename")
            .fieldCheckerEntry("User" to "name", "... on User @defer { __typename }")
            .build()

        val error = assertThrows<IllegalArgumentException> {
            validator.validate(RequiredSelectionsValidationCtx("User", "name", registry))
        }

        assertTrue(error.message!!.contains("User.name"), error.message)
    }

    @Test
    fun `rejects @defer in type checker required selection sets`() {
        val registry = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry("User", "... on User @defer { __typename }")
            .build()

        val error = assertThrows<IllegalArgumentException> {
            validator.validate(RequiredSelectionsValidationCtx("User", null, registry))
        }

        assertTrue(error.message!!.contains("Required selections for User must resolve synchronously"), error.message)
    }

    @Test
    fun `accepts required selection sets without @defer`() {
        val registry = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("User" to "name", "__typename")
            .fieldCheckerEntry("User" to "name", "__typename")
            .typeCheckerEntry("User", "__typename")
            .build()

        assertDoesNotThrow {
            validator.validate(RequiredSelectionsValidationCtx("User", "name", registry))
            validator.validate(RequiredSelectionsValidationCtx("User", null, registry))
            validator.validate(RequiredSelectionsValidationCtx("User", "missing", registry))
        }
    }
}
