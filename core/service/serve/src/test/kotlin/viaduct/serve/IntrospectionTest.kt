package viaduct.serve

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests to verify that GraphQL introspection queries return complete responses.
 *
 * These tests specifically verify that empty arrays (args, interfaces) are
 * properly included in introspection responses, which is required for GraphiQL 5
 * compatibility.
 */
class IntrospectionTest {
    private lateinit var helper: ViaductServerTestHelper

    @BeforeEach
    fun setup() {
        helper = ViaductServerTestHelper()
    }

    @AfterEach
    fun teardown() {
        helper.close()
    }

    @Test
    fun `introspection response should include args and interfaces fields`() {
        val query = """
            query IntrospectionQuery {
                __schema {
                    directives {
                        name
                        args { name }
                    }
                    types {
                        kind
                        name
                        interfaces { name }
                        fields {
                            name
                            args { name }
                        }
                    }
                }
            }
        """.trimIndent()

        val result = helper.executeGraphQL(query, operationName = "IntrospectionQuery")
        assertEquals(200, result.statusCode, "Introspection query should succeed")
        assertNotNull(result.data, "Response should contain data")

        @Suppress("UNCHECKED_CAST")
        val schema = result.data!!["__schema"] as Map<String, Any>
        assertNotNull(schema, "Response should contain __schema")

        @Suppress("UNCHECKED_CAST")
        val directives = schema["directives"] as List<Map<String, Any>>
        assertTrue(directives.isNotEmpty(), "Schema should have directives")
        for (directive in directives) {
            assertTrue(
                directive.containsKey("args"),
                "Directive '${directive["name"]}' should have 'args' field"
            )
            assertTrue(directive["args"] is List<*>, "Directive '${directive["name"]}' args should be a list")
        }

        @Suppress("UNCHECKED_CAST")
        val types = schema["types"] as List<Map<String, Any>>
        val objectTypes = types.filter { it["kind"] == "OBJECT" }
        assertTrue(objectTypes.isNotEmpty(), "Schema should have OBJECT types")
        for (type in objectTypes) {
            val typeName = type["name"]
            assertTrue(type.containsKey("interfaces"), "OBJECT type '$typeName' should have 'interfaces' field")
            assertTrue(type["interfaces"] is List<*>, "OBJECT type '$typeName' interfaces should be a list")

            @Suppress("UNCHECKED_CAST")
            val fields = type["fields"] as? List<Map<String, Any>>
            if (fields != null) {
                for (field in fields) {
                    assertTrue(field.containsKey("args"), "Field '${field["name"]}' on type '$typeName' should have 'args' field")
                    assertTrue(field["args"] is List<*>, "Field '${field["name"]}' args should be a list")
                }
            }
        }
    }

    @Test
    fun `field without arguments should have empty args array`() {
        val result = helper.executeGraphQL(
            """
            {
                __type(name: "Query") {
                    fields {
                        name
                        args { name }
                    }
                }
            }
            """.trimIndent()
        )
        assertEquals(200, result.statusCode)

        @Suppress("UNCHECKED_CAST")
        val typeInfo = result.data!!["__type"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val fields = typeInfo["fields"] as List<Map<String, Any>>
        val field = fields.firstOrNull()
        assertNotNull(field, "Query type should have at least one field")
        assertTrue(field.containsKey("args"), "Field should have 'args' key")
        assertTrue(field["args"] is List<*>, "args should be a List")
    }

    @Test
    fun `deeply nested introspection query should succeed`() {
        // graphql-java 26 added GoodFaithIntrospection which rejects queries where
        // __Type.fields appears too many times. Viaduct disables this check because
        // tenants like ContentBuilderIntrospectionProvider run deeply recursive
        // introspection queries.
        // Test this by building a 100-level deep query of __type selections
        val depth = 100
        val inner = (1..depth).fold("name kind") { acc, _ ->
            "name kind fields { name type { $acc } }"
        }
        val query = "{ __type(name: \"Query\") { $inner } }"

        val result = helper.executeGraphQL(query)
        assertEquals(200, result.statusCode)
        assertTrue(
            result.errors.isEmpty(),
            "Deeply nested introspection should not produce errors, but got: " +
                result.errors.map { it["message"] }
        )
        assertNotNull(result.data, "Response should contain data")
    }
}
