package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Glass-box round-trip tests for directive ordering with default-valued arguments.
 *
 * These tests verify that:
 * 1. Directives are encoded in topological order (dependencies first)
 * 2. Applied directive arguments with default values are correctly reconstructed
 *
 * The key insight is that if we have directives where the dependency ordering is
 * the REVERSE of alphabetical ordering, and those directives have default-valued
 * arguments, then the only way decoding can work correctly is if we encode in
 * topological order. Otherwise, when decoding an applied directive, we wouldn't
 * have access to the directive definition to fill in the defaults.
 */
class DirectiveOrderingRoundTripTest {
    /**
     * Test with directives where dependency order is reverse of alphabetical.
     *
     * @aaa depends on @bbb (has @bbb applied on its argument)
     * @bbb depends on @ccc (has @ccc applied on its argument)
     * @ccc has no dependencies
     *
     * Alphabetical order: aaa, bbb, ccc
     * Required encoding order: ccc, bbb, aaa (dependencies first)
     *
     * Each directive has a default-valued argument, so the encoder can omit
     * arguments that match the default. This means the decoder MUST have access
     * to the directive definition to fill in the defaults.
     */
    @Test
    fun `round trip with reverse-alphabetical directive dependencies and defaults`() {
        assertRoundTrip(
            """
            # @ccc has no dependencies - must be encoded first
            directive @ccc(reason: String = "default_ccc") on ARGUMENT_DEFINITION

            # @bbb depends on @ccc - must be encoded after @ccc
            directive @bbb(
                reason: String = "default_bbb"
                metadata: String @ccc  # Uses @ccc with default arg value
            ) on ARGUMENT_DEFINITION

            # @aaa depends on @bbb - must be encoded after @bbb
            directive @aaa(
                reason: String = "default_aaa"
                metadata: String @bbb  # Uses @bbb with default arg value
            ) on FIELD_DEFINITION

            type Query {
                # Apply @aaa with default value - encoder should omit the arg
                field: String @aaa
            }
            """.trimIndent()
        )
    }

    /**
     * Similar test but with explicit non-default values to ensure both paths work.
     */
    @Test
    fun `round trip with reverse-alphabetical dependencies and explicit values`() {
        assertRoundTrip(
            """
            directive @ccc(reason: String = "default_ccc") on ARGUMENT_DEFINITION

            directive @bbb(
                reason: String = "default_bbb"
                metadata: String @ccc(reason: "explicit_ccc")
            ) on ARGUMENT_DEFINITION

            directive @aaa(
                reason: String = "default_aaa"
                metadata: String @bbb(reason: "explicit_bbb")
            ) on FIELD_DEFINITION

            type Query {
                field: String @aaa(reason: "explicit_aaa")
            }
            """.trimIndent()
        )
    }

    /**
     * Test with a diamond dependency pattern.
     *
     * @top depends on @left and @right
     * @left and @right both depend on @bottom
     *
     * All have default-valued arguments.
     */
    @Test
    fun `round trip with diamond dependency and defaults`() {
        assertRoundTrip(
            """
            directive @bottom(value: Int = 0) on ARGUMENT_DEFINITION

            directive @left(
                value: Int = 1
                dep: String @bottom
            ) on ARGUMENT_DEFINITION

            directive @right(
                value: Int = 2
                dep: String @bottom
            ) on ARGUMENT_DEFINITION

            directive @top(
                value: Int = 3
                leftDep: String @left
                rightDep: String @right
            ) on FIELD_DEFINITION

            type Query {
                field: String @top
            }
            """.trimIndent()
        )
    }

    /**
     * Test that applied directives on type definitions work correctly.
     *
     * Since all directive definitions are encoded before type definitions,
     * applied directives on types should always have access to their definitions.
     */
    @Test
    fun `round trip with applied directives on types using defaults`() {
        assertRoundTrip(
            """
            directive @typeDirective(
                reason: String = "default_reason"
                priority: Int = 0
            ) on OBJECT | FIELD_DEFINITION | ARGUMENT_DEFINITION

            type Query {
                # Uses default for both args
                field(arg: String @typeDirective): String @typeDirective
            }

            # Uses explicit values
            type Mutation @typeDirective(reason: "explicit", priority: 10) {
                action: Boolean
            }
            """.trimIndent()
        )
    }

    /**
     * Test with nullable arguments that have no default.
     * These should get NullValue when not specified.
     */
    @Test
    fun `round trip with nullable args and no defaults`() {
        assertRoundTrip(
            """
            directive @withNullable(
                required: String!
                optional: String
            ) on FIELD_DEFINITION

            type Query {
                # Only required arg specified, optional should become null
                field1: String @withNullable(required: "value")
                # Both specified
                field2: String @withNullable(required: "value", optional: "opt")
            }
            """.trimIndent()
        )
    }

    /**
     * Test complex scenario with multiple levels of nesting and mixed defaults.
     */
    @Test
    fun `round trip with complex nested directive dependencies`() {
        assertRoundTrip(
            """
            # Level 0 - no dependencies
            directive @level0(v: Int = 0) on ARGUMENT_DEFINITION

            # Level 1 - depends on level0
            directive @level1a(v: Int = 1, dep: String @level0) on ARGUMENT_DEFINITION
            directive @level1b(v: Int = 2, dep: String @level0) on ARGUMENT_DEFINITION

            # Level 2 - depends on level1
            directive @level2(
                v: Int = 3
                a: String @level1a
                b: String @level1b
            ) on FIELD_DEFINITION

            type Query {
                # Mix of default and explicit values
                field: String @level2(v: 100)
            }
            """.trimIndent()
        )
    }
}
