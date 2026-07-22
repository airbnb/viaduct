package viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
    input OneofInput @oneOf {
      stringValue: String
      intValue: Int
    }
    extend type Query {
      fromArgumentField(arg: OneofInput!): String @resolver
      intermediary(arg: OneofInput!): String @resolver
      fromVariablesProvider: String @resolver
      fromBuilderTwoKeysOneNull: String @resolver
      fromBuilderSingleNullKey: String @resolver
    }
"""
)
@Suppress("UNNECESSARY_SAFE_CALL")
abstract class TempOneOfViolationContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `oneof violation fails at runtime`() {
        val result = execute("query { fromVariablesProvider }")

        // If we get here, check for errors in the result
        assertTrue(result.errors?.isNotEmpty() == true, "Expected errors but got: ${result.errors}")

        val hasOneofError = result.errors?.any { error ->
            error.message?.contains("Exactly one key must be specified for OneOf type") == true ||
                error.message?.contains("OneOf") == true
        } == true

        assertTrue(hasOneofError, "Expected oneof violation error but got: ${result.errors?.map { it.message }}")
    }

    @Test
    fun `oneof with exactly one field resolves without errors`() {
        val result = execute("""query { fromArgumentField(arg: { stringValue: "hello" }) }""")

        assertTrue(
            result.errors?.isEmpty() != false,
            "Expected no errors but got: ${result.errors?.map { it.message }}"
        )

        assertNotNull(
            result.getData()?.get("fromArgumentField"),
            "Expected fromArgumentField to resolve to a non-null value"
        )
    }

    // The following two cases exercise the eager builder path (Builder.build()), which must reject
    // the same inputs graphql-java rejects at coercion time. They are the parity-critical cases:
    // both the Kotlin and Java tenant APIs bind their own generated Builder to these fields, so a
    // single set of assertions here verifies that both implementations count supplied keys (not
    // non-null values) and reject a null value on the single supplied key. See the concrete
    // subclasses for the language-specific VariablesProvider that constructs the invalid input.

    @Test
    fun `oneof builder rejects two supplied keys even when one value is null`() {
        assertBuilderOneOfViolation("fromBuilderTwoKeysOneNull")
    }

    @Test
    fun `oneof builder rejects a single supplied key with a null value`() {
        assertBuilderOneOfViolation("fromBuilderSingleNullKey")
    }

    private fun assertBuilderOneOfViolation(field: String) {
        val result = execute("query { $field }")

        assertTrue(
            result.errors?.isNotEmpty() == true,
            "Expected a @oneOf builder violation for $field but got: ${result.errors}"
        )

        // Match the eager builder's phrasing specifically — "@oneOf type" (with the '@'), which
        // graphql-java's coercion backstop never emits (it says "OneOf type", no '@'). This ensures
        // the test fails if the builder stops rejecting the input and the violation is only caught
        // downstream by graphql-java, rather than passing vacuously on the backstop's error.
        val hasBuilderOneofError = result.errors?.any { error ->
            error.message?.contains("@oneOf type") == true
        } == true

        assertTrue(
            hasBuilderOneofError,
            "Expected eager builder @oneOf violation for $field but got: ${result.errors?.map { it.message }}"
        )
    }
}
