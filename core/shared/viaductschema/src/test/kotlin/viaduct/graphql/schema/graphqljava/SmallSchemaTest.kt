package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.graphqljava.extensions.TypeDefinitionRegistryOptions
import viaduct.graphql.schema.graphqljava.extensions.toRegistry
import viaduct.graphql.schema.graphqljava.extensions.toRegistryWithoutExtensionTypeDefinitions
import viaduct.graphql.schema.test.SchemaDiff

/**
 * Tests for GJSchema and GJSchemaRaw using small, inline schemas.
 * These tests cover various corner cases without requiring the large 5k schema resource.
 */
class SmallSchemaTest {
    companion object {
        /**
         * Unified test schema containing all elements needed for the various small schema tests:
         * - Built-in directives (include, skip, defer, experimental_disableErrorPropagation)
         * - Custom directives with various argument patterns (defaults, nullable, non-nullable)
         * - Directive with input type argument
         * - Basic types using all built-in scalars (Boolean, Float, ID, Int, String)
         * - Interface hierarchy (ParentInterface, Interface1, Interface2)
         * - Type extensions (extend type, extend interface, extend input, extend union)
         * - Enum type
         * - Input types with defaults
         * - Union type
         * - Types with applied directives (all args provided, no args provided)
         */
        private val testSchemaString =
            """
            # Built-in directives
            directive @include(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @skip(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @defer(if: Boolean! = true, label: String) on FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @experimental_disableErrorPropagation on QUERY | MUTATION | SUBSCRIPTION

            # Custom directives
            directive @d1(a1: Int = 1) repeatable on OBJECT
            directive @d2(a2: String!) on FIELD_DEFINITION

            # Directive with scalar arguments testing different default value patterns:
            # - nullableNoDefault: String (nullable, no default)
            # - nullableWithNonNullDefault: String = "hello" (nullable, non-null default)
            # - nullableWithNullDefault: String = null (nullable, explicit null default)
            # - nonNullableWithDefault: String! = "required" (non-nullable, has default)
            directive @scalarArgs(
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "hello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "required"
            ) on OBJECT

            # Input type with fields following the same pattern as @scalarArgs
            input InputWithDefaults {
                nullableNoDefault: String
                nullableWithNonNullDefault: String = "inputHello"
                nullableWithNullDefault: String = null
                nonNullableWithDefault: String! = "inputRequired"
            }

            # Directive with an input type argument to test recursive population
            directive @inputArg(arg: InputWithDefaults) on OBJECT

            # Query type with fields using all built-in scalars
            type Query {
                b: Boolean
                f: Float
                id: ID
                i: Int
                s: String
            }

            # Basic type with field argument default
            type Test @d1 {
                hello(a1: String = "world"): String
            }

            # Interface hierarchy
            interface ParentInterface {
                parentField: String
            }
            extend interface ParentInterface {
                parentFieldExtended: String
            }

            interface Interface1 implements ParentInterface {
                field2: String @d2(a2: "argValue")
                parentField: String
                parentFieldExtended: String
            }
            extend interface Interface1 {
                field4: String
            }

            interface Interface2 {
                field3: Enum1
            }

            # Type implementing multiple interfaces
            type Test2 implements Interface1 & Interface2 & ParentInterface {
                field2: String
                field3: Enum1
                field4: String
                field5: [String!]!
                nested: [[[String]!]]
                parentField: String
                parentFieldExtended: String
            }

            # Type with repeatable directive
            type Test3 @d1 @d1 {
                field5(in: Input1!): String
            }

            # Enum type
            enum Enum1 {
                A
                B
            }

            # Type extension
            type Test1 {
                hello: String
            }
            extend type Test1 {
                field4: Int
            }

            # Input type with extension
            input Input1 {
                field1: Int
            }
            extend input Input1 {
                field2: String
            }

            # Union type with extension
            union Union1 = Test1 | Test2
            extend union Union1 = Test3

            # Test types for @scalarArgs directive:
            # - AllArgsProvided: all arguments explicitly provided
            # - NoArgsProvided: no arguments provided (uses defaults/null)
            type ScalarArgsAllProvided @scalarArgs(
                nullableNoDefault: "explicit1"
                nullableWithNonNullDefault: "explicit2"
                nullableWithNullDefault: "explicit3"
                nonNullableWithDefault: "explicit4"
            ) {
                stub: String
            }

            type ScalarArgsNoneProvided @scalarArgs {
                stub: String
            }

            # Test types for @inputArg directive:
            # - InputArgAllProvided: input with all fields explicitly provided
            # - InputArgNoneProvided: input with no fields provided (uses defaults/null)
            type InputArgAllProvided @inputArg(arg: {
                nullableNoDefault: "inputExplicit1"
                nullableWithNonNullDefault: "inputExplicit2"
                nullableWithNullDefault: "inputExplicit3"
                nonNullableWithDefault: "inputExplicit4"
            }) {
                stub: String
            }

            type InputArgNoneProvided @inputArg(arg: {}) {
                stub: String
            }
            """.trimIndent()
    }

    @Test
    fun `GJSchema and GJSchemaRaw produce identical schemas from registry`() {
        val registry = SchemaParser().parse(testSchemaString)
        val gjSchema = gjSchemaFromRegistry(registry)
        val gjSchemaRaw = gjSchemaRawFromRegistry(registry)

        SchemaDiff(gjSchema, gjSchemaRaw).diff().assertEmpty("\n")
    }

    @Test
    fun `GJSchema fromSchema matches GJSchemaRaw fromRegistry`() {
        // This test compares gjSchemaFromSchema (GraphQLSchema path) against gjSchemaRawFromRegistry
        // to ensure directive arguments are consistently populated with defaults/NullValue
        val registry = SchemaParser().parse(testSchemaString)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)

        val gjSchema = gjSchemaFromSchema(graphQLSchema)
        val gjSchemaRaw = gjSchemaRawFromRegistry(registry)

        SchemaDiff(gjSchema, gjSchemaRaw).diff().assertEmpty("\n")
    }

    @Test
    fun `GJSchemaCheck validates GJSchema against GraphQLSchema`() {
        val registry = SchemaParser().parse(testSchemaString)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        val gjSchema = gjSchemaFromSchema(graphQLSchema)

        GJSchemaCheck(gjSchema, graphQLSchema).assertEmpty("\n")
    }

    @Test
    fun `toRegistry roundtrip preserves schema`() {
        val originalRegistry = SchemaParser().parse(testSchemaString)

        var s = gjSchemaFromRegistry(originalRegistry).toRegistry(TypeDefinitionRegistryOptions.NO_STUBS)
        s.types()
            .filter { (key, _) -> key.startsWith("__") } // introspective types not present in raw registry
            .forEach { (_, value) -> s.remove(value) }
        var sr = gjSchemaRawFromRegistry(originalRegistry).toRegistry(TypeDefinitionRegistryOptions.NO_STUBS)
        SchemaDiff(gjSchemaFromRegistry(s), gjSchemaRawFromRegistry(sr)).diff().assertEmpty("\n")

        // test with base and extension definitions merged
        s = gjSchemaFromRegistry(originalRegistry).toRegistryWithoutExtensionTypeDefinitions(TypeDefinitionRegistryOptions.NO_STUBS)
        s.types().filter { (key, _) -> key.startsWith("__") }.forEach { (_, value) -> s.remove(value) }
        sr = gjSchemaRawFromRegistry(originalRegistry).toRegistryWithoutExtensionTypeDefinitions(TypeDefinitionRegistryOptions.NO_STUBS)
        SchemaDiff(gjSchemaFromRegistry(s), gjSchemaRawFromRegistry(sr)).diff().assertEmpty("\n")
    }

    @Test
    fun `noop filter preserves schema identity`() {
        val schema = gjSchemaFromRegistry(SchemaParser().parse(testSchemaString))
        val noopFilteredSchema = schema.filter(NoopSchemaFilter())
        SchemaDiff(schema, noopFilteredSchema).diff().assertEmpty("\n")
    }

    /**
     * Regression test for SchemaDiff comparing effectiveDefaultValue from two different
     * ViaductSchema implementations.
     *
     * The bug: When comparing GJSchema (from GraphQLSchema) with GJSchemaRaw (from
     * TypeDefinitionRegistry), the effectiveDefaultValue for nullable input fields without
     * explicit defaults returns NullValue.of() from both. However, these are semantically
     * equal values that should compare as equal.
     *
     * This test creates schemas from both paths and compares them, which exercises the
     * effectiveDefaultValue comparison code path in SchemaDiff.
     */
    @Test
    fun `SchemaDiff compares effectiveDefaultValue correctly between GJSchema and GJSchemaRaw`() {
        // Schema with nullable input field without explicit default
        // effectiveDefaultValue will return NullValue.of() for this field
        val schemaSDL =
            """
            directive @include(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @skip(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @defer(if: Boolean! = true, label: String) on FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @experimental_disableErrorPropagation on QUERY | MUTATION | SUBSCRIPTION

            type Query {
                stub: String
                # Include Int, Float, ID so they're referenced in the schema
                intField: Int
                floatField: Float
                idField: ID
            }

            input TestInput {
                # Nullable field without default - effectiveDefaultValue returns NullValue
                nullableNoDefault: String
                # Nullable Int field without default (common in Airbnb schema)
                nullableIntNoDefault: Int
                # Field with explicit default for comparison
                withDefault: String = "hello"
            }
            """.trimIndent()

        val registry = SchemaParser().parse(schemaSDL)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)

        // Create schemas via both paths
        val gjSchema = gjSchemaFromSchema(graphQLSchema)
        val gjSchemaRaw = gjSchemaRawFromRegistry(registry)

        // Verify that the input field has effectiveDefault behavior we expect
        val gjSchemaInput = gjSchema.types["TestInput"] as viaduct.graphql.schema.ViaductSchema.Input
        val gjSchemaRawInput = gjSchemaRaw.types["TestInput"] as viaduct.graphql.schema.ViaductSchema.Input
        val gjField = gjSchemaInput.fields.find { it.name == "nullableNoDefault" }!!
        val rawField = gjSchemaRawInput.fields.find { it.name == "nullableNoDefault" }!!

        // Both should have hasEffectiveDefault = true (nullable without explicit default)
        assertTrue(gjField.hasEffectiveDefault, "GJSchema field should have effectiveDefault")
        assertTrue(rawField.hasEffectiveDefault, "GJSchemaRaw field should have effectiveDefault")

        // Both should NOT have hasDefault (no explicit default)
        assertFalse(gjField.hasDefault, "GJSchema field should not have explicit default")
        assertFalse(rawField.hasDefault, "GJSchemaRaw field should not have explicit default")

        // Both effectiveDefaultValue should be NullValue instances
        val gjEffective = gjField.effectiveDefaultValue
        val rawEffective = rawField.effectiveDefaultValue
        assertTrue(gjEffective is viaduct.graphql.schema.ViaductSchema.NullLiteral, "GJSchema effectiveDefaultValue should be NullValue, got: ${gjEffective::class}")
        assertTrue(rawEffective is viaduct.graphql.schema.ViaductSchema.NullLiteral, "GJSchemaRaw effectiveDefaultValue should be NullValue, got: ${rawEffective::class}")

        // Now verify SchemaDiff compares them correctly
        SchemaDiff(gjSchema, gjSchemaRaw).diff().assertEmpty("\n")
    }

    /**
     * Regression test for SchemaDiff comparing effectiveDefaultValue for Long type fields.
     *
     * The bug: When comparing nullable Long fields without explicit defaults, SchemaDiff's
     * areNodesEqual method had special handling for integral types (Byte, Short, Long) that
     * would extract the integral value. For NullValue instances, extractIntegralValue would
     * return the NullValue itself, and then compare using reference equality (==) instead of
     * semantic equality, causing the comparison to fail.
     *
     * This is a regression test to ensure that nullable Long fields without defaults
     * (which use effectiveDefaultValue = NullValue.of()) compare correctly.
     */
    @Test
    fun `SchemaDiff compares NullValue correctly for Long type fields`() {
        // Schema with nullable Long field without explicit default
        // This triggers the bug in extractIntegralValue which returned NullValue and compared with ==
        val schemaSDL =
            """
            directive @include(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @skip(if: Boolean!) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @defer(if: Boolean! = true, label: String) on FRAGMENT_SPREAD | INLINE_FRAGMENT
            directive @experimental_disableErrorPropagation on QUERY | MUTATION | SUBSCRIPTION

            scalar Long
            scalar Short
            scalar Byte

            type Query {
                stub: String
                intField: Int
                floatField: Float
                idField: ID
            }

            input TestInputWithLong {
                # Nullable Long field without default - this is the key case that triggers the bug
                # effectiveDefaultValue returns NullValue.of(), and the Long type handling in
                # extractIntegralValue returns the NullValue itself, then compares with == (reference equality)
                nullableLongNoDefault: Long
                # Also test Short and Byte for completeness
                nullableShortNoDefault: Short
                nullableByteNoDefault: Byte
            }
            """.trimIndent()

        val registry = SchemaParser().parse(schemaSDL)
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)

        // Create schemas via both paths
        val gjSchema = gjSchemaFromSchema(graphQLSchema)
        val gjSchemaRaw = gjSchemaRawFromRegistry(registry)

        // Verify the Long field setup is correct
        val gjSchemaInput = gjSchema.types["TestInputWithLong"] as viaduct.graphql.schema.ViaductSchema.Input
        val gjSchemaRawInput = gjSchemaRaw.types["TestInputWithLong"] as viaduct.graphql.schema.ViaductSchema.Input
        val gjField = gjSchemaInput.fields.find { it.name == "nullableLongNoDefault" }!!
        val rawField = gjSchemaRawInput.fields.find { it.name == "nullableLongNoDefault" }!!

        // Verify the field type is Long and nullable
        assertTrue(gjField.type.isNullable, "GJSchema Long field should be nullable")
        assertTrue(rawField.type.isNullable, "GJSchemaRaw Long field should be nullable")

        // Both should have hasEffectiveDefault = true (nullable without explicit default)
        assertTrue(gjField.hasEffectiveDefault, "GJSchema Long field should have effectiveDefault")
        assertTrue(rawField.hasEffectiveDefault, "GJSchemaRaw Long field should have effectiveDefault")

        // Both should NOT have hasDefault (no explicit default)
        assertFalse(gjField.hasDefault, "GJSchema Long field should not have explicit default")
        assertFalse(rawField.hasDefault, "GJSchemaRaw Long field should not have explicit default")

        // Both effectiveDefaultValue should be NullValue instances
        val gjEffective = gjField.effectiveDefaultValue
        val rawEffective = rawField.effectiveDefaultValue
        assertTrue(gjEffective is viaduct.graphql.schema.ViaductSchema.NullLiteral, "GJSchema effectiveDefaultValue should be NullValue")
        assertTrue(rawEffective is viaduct.graphql.schema.ViaductSchema.NullLiteral, "GJSchemaRaw effectiveDefaultValue should be NullValue")

        // This is the key test - SchemaDiff must compare the NullValues correctly for Long type
        // Without the fix, this fails because extractIntegralValue returns the NullValue itself
        // and compares with == (reference equality) instead of semantic equality
        SchemaDiff(gjSchema, gjSchemaRaw).diff().assertEmpty("\n")
    }
}
