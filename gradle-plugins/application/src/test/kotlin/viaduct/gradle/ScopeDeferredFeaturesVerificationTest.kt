package viaduct.gradle

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.validation.ValidationRule
import viaduct.graphql.schema.validation.rules.DefaultSchemaValidator

/**
 * B.15 / B.16 deferred verification.
 * B.15 (scope-module parity) and B.16 (:private scope aliasing)
 * are explicitly DEFERRED in v1. These tests assert that:
 *  (a) No new ValidationRule subclasses for B.15 / B.16 were introduced.
 *  (b) No ViaductRuleSetProvider SPI exists.
 * The :private aliasing behaviour preserved in core/tenant/codegen is covered by
 * ScopeDeferredVerificationTest in that module.
 */
class ScopeDeferredFeaturesVerificationTest {

    // ── No B.15 / B.16 ValidationRule subclasses ───────────────────

    @Test
    fun `no ScopeModuleParityRule ValidationRule subclass exists in v1`() {
        val candidates = listOf(
            "viaduct.graphql.schema.validation.rules.ScopeModuleParityRule",
            "viaduct.graphql.scopes.rules.ScopeModuleParityRule",
            "viaduct.graphql.schema.validation.rules.R14001Rule",
        )
        for (className in candidates) {
            val result = runCatching { Class.forName(className) }
            assertTrue(result.isFailure,
                "B.15 ScopeModuleParity rule must NOT be implemented in v1 — found class: $className")
        }
    }

    @Test
    fun `no PrivateScopeAliasingRule ValidationRule subclass exists in v1`() {
        val candidates = listOf(
            "viaduct.graphql.schema.validation.rules.PrivateScopeAliasingRule",
            "viaduct.graphql.scopes.rules.PrivateScopeAliasingRule",
            "viaduct.graphql.schema.validation.rules.R14002Rule",
        )
        for (className in candidates) {
            val result = runCatching { Class.forName(className) }
            assertTrue(result.isFailure,
                "B.16 PrivateScopeAliasing rule must NOT be implemented in v1 — found class: $className")
        }
    }

    @Test
    fun `DefaultSchemaValidator contains only the expected 13 rules and no scope-parity or private-alias rules`() {
        val validator = DefaultSchemaValidator.create()

        // Retrieve the registered rules via reflection to check for unexpected additions.
        val phasesField = validator.javaClass.getDeclaredField("phases")
        phasesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val phases = phasesField.get(validator) as List<List<ValidationRule>>

        val allRuleClassNames = phases.flatten().map { it.javaClass.simpleName }

        // B.15 / B.16 rule names must NOT appear in the validator.
        val forbiddenPatterns = listOf(
            "scopeparity", "scopemodule", "privatescope", "privatescopealias",
            "r14001", "r14002",
        )
        for (ruleName in allRuleClassNames) {
            val lower = ruleName.lowercase()
            for (pattern in forbiddenPatterns) {
                assertFalse(lower.contains(pattern),
                    "Unexpected B.15/B.16 rule found in DefaultSchemaValidator: $ruleName (matched pattern '$pattern')")
            }
        }

        // Confirm the total rule count matches the documented 13 rules.
        assertTrue(allRuleClassNames.size == 13,
            "DefaultSchemaValidator must have exactly 13 rules (B.15/B.16 are deferred). Found ${allRuleClassNames.size}: $allRuleClassNames")
    }

    // ── No ViaductRuleSetProvider SPI ──────────────────────────────

    @Test
    fun `ViaductRuleSetProvider SPI does not exist in v1`() {
        val candidates = listOf(
            "viaduct.graphql.schema.validation.ViaductRuleSetProvider",
            "viaduct.graphql.scopes.ViaductRuleSetProvider",
            "viaduct.graphql.schema.ViaductRuleSetProvider",
        )
        for (className in candidates) {
            val result = runCatching { Class.forName(className) }
            assertTrue(result.isFailure,
                "ViaductRuleSetProvider must NOT exist in v1 — found class: $className")
        }
    }

    @Test
    fun `no ViaductRuleSetProvider ServiceLoader configuration`() {
        // Assert no META-INF/services entry for a hypothetical ViaductRuleSetProvider.
        val candidates = listOf(
            "viaduct.graphql.schema.validation.ViaductRuleSetProvider",
            "viaduct.graphql.scopes.ViaductRuleSetProvider",
        )
        for (className in candidates) {
            val resourcePath = "META-INF/services/$className"
            val resource = Thread.currentThread().contextClassLoader.getResource(resourcePath)
            assertTrue(resource == null,
                "ViaductRuleSetProvider ServiceLoader config must not exist in v1: $resourcePath")
        }
    }
}
