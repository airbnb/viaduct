package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid
import viaduct.graphql.scopes.ScopedSchemaBuilder

@ExperimentalCoroutinesApi
class RequiredSelectionsTest {
    @Test
    fun `resolve field with required sibling field`() =
        MockTenantModuleBootstrapper("extend type Query { foo: String, bar: String }") {
            fieldWithValue("Query" to "bar", "BAR")
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> (obj.fetch("bar") as String).reversed() }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": "RAB"}}""")
        }

    @Test
    fun `resolve field with transitive required selections`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 2)
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar: bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use deep aliases`() =
        MockTenantModuleBootstrapper("extend type Query { string1: String, bar: Bar } type Bar { value: String }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> mapOf("value" to "B") }
                }
            }
            field("Query" to "string1") {
                resolver {
                    objectSelections("aliasedBar: bar { aliasedValue: value }")
                    fn { _, obj, _, _, _ ->
                        val bar = obj.fetchAs<EngineObjectData>("aliasedBar")
                        val value = bar.fetch("aliasedValue")
                        "A:$value"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{string1}")
                .assertJson("""{"data": {"string1": "A:B"}}""")
        }

    @Test
    fun `required selections use arguments`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections use aliases and arguments`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("aliasedBar:bar(x:3)")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("aliasedBar") * 2 }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 30}}""")
        }

    @Test
    fun `required selections select an argumented field multiple times`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar(x:Int):Int }") {
            field("Query" to "foo") {
                resolver {
                    objectSelections("b1:bar(x:3), b2:bar(x:5)")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("b1") * obj.fetchAs<Int>("b2")
                    }
                }
            }
            field("Query" to "bar") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("x") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 60}}""")
        }

    @Test
    fun `required selections use fragments`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("fragment _ on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use untyped inline fragments`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `required selections use typed inline fragments`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("... on Query { bar }")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 6}}""")
        }

    @Test
    fun `resolve fields with shared requirement`() {
        val bazCount = AtomicInteger()
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int, baz: Int }") {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, _ -> bazCount.incrementAndGet().let { 5 } }
                }
            }
            field("Query" to "bar") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 3 }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections("baz")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 2 }
                }
            }
        }.runFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 10, "bar": 15}}""")
                .also { assertEquals(1, bazCount.get()) }
        }
    }

    @Test
    fun `resolve field with multiple requirements`() =
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int, baz: Int }") {
            fieldWithValue("Query" to "baz", 5)
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar baz")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<Int>("bar") * obj.fetchAs<Int>("baz")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{foo}")
                .assertJson("""{"data": {"foo": 15}}""")
        }

    @Test
    fun `resolve fields multiple mergeable requirements`() {
        val barCount = AtomicInteger()
        MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
            field("Query" to "bar") {
                resolver {
                    fn { _, _, _, _, _ -> 3.also { barCount.incrementAndGet() } }
                }
            }
            field("Query" to "foo") {
                resolver {
                    objectSelections(
                        """
                        fragment F on Query { bar }
                        fragment Main on Query {
                          bar
                          aliasedBar: bar
                          ... {
                            bar
                            ... {
                              bar
                              ... F
                            }
                          }
                          ... on Query {
                            bar
                            ... on Query {
                              bar
                              ... F
                            }
                          }
                          ... F
                        }
                        """.trimIndent()
                    )
                    fn { _, obj, _, _, _ ->
                        // make sure we wait for aliasedBar
                        obj.fetchAs<Int>("aliasedBar")

                        // but ultimately just return 2 * "bar"
                        obj.fetchAs<Int>("bar") * 2
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{foo bar}")
                .assertJson("""{"data": {"foo": 6, "bar": 3}}""")
                .also { assertEquals(2, barCount.get()) }
        }
    }

    @Test
    fun `resolve private field in RSS`() {
        // Need to set up both full schema and scoped schema
        val fullSchemaSDL = """
            extend type Query @scope(to: ["*"]) { _: String }
            extend type Query @scope(to: ["scoped"]) { foo: Int }
            extend type Query @scope(to: ["private"]) { bar: Int }
        """

        val bootstrapper = MockTenantModuleBootstrapper(fullSchemaSDL) {
            fieldWithValue("Query" to "bar", 3)
            field("Query" to "foo") {
                resolver {
                    objectSelections("bar")
                    fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") + 1 }
                }
            }
        }

        val privateSchema = ViaductSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                validScopes = sortedSetOf("scoped", "private")
            ).applyScopes(setOf("scoped")).filtered
        )

        bootstrapper.runFeatureTest(schema = privateSchema) {
            runQuery("{foo}")
                .assertJson("{data: {foo: 4}}")
        }
    }

    @Test
    fun `resolve field with queryValueFragment - simple field access`() =
        MockTenantModuleBootstrapper("extend type Query { currentUser: String, userGreeting: String }") {
            fieldWithValue("Query" to "currentUser", "Alice")
            field("Query" to "userGreeting") {
                resolver {
                    querySelections("currentUser")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("currentUser")
                        "Hello, $user!"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{userGreeting}")
                .assertJson("""{"data": {"userGreeting": "Hello, Alice!"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - with aliases`() =
        MockTenantModuleBootstrapper("extend type Query { currentUser: String, userCount: Int, summary: String }") {
            fieldWithValue("Query" to "currentUser", "Bob")
            fieldWithValue("Query" to "userCount", 42)
            field("Query" to "summary") {
                resolver {
                    querySelections("user: currentUser, count: userCount")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("user")
                        val count = qry.fetchAs<Int>("count")
                        "$user has $count items"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{summary}")
                .assertJson("""{"data": {"summary": "Bob has 42 items"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - with arguments`() =
        MockTenantModuleBootstrapper("extend type Query { user(id: String!): String, userMessage: String }") {
            field("Query" to "user") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val id = args.getAs<String>("id")
                        "User-$id"
                    }
                }
            }
            field("Query" to "userMessage") {
                resolver {
                    querySelections("user(id: \"123\")")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("user")
                        "Message for: $user"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{userMessage}")
                .assertJson("""{"data": {"userMessage": "Message for: User-123"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - using fragments`() =
        MockTenantModuleBootstrapper("extend type Query { userName: String, userEmail: String, profile: String }") {
            fieldWithValue("Query" to "userName", "Charlie")
            fieldWithValue("Query" to "userEmail", "charlie@example.com")
            field("Query" to "profile") {
                resolver {
                    querySelections("fragment UserInfo on Query { userName userEmail }")
                    fn { _, _, qry, _, _ ->
                        val name = qry.fetchAs<String>("userName")
                        val email = qry.fetchAs<String>("userEmail")
                        "Name: $name, Email: $email"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{profile}")
                .assertJson("""{"data": {"profile": "Name: Charlie, Email: charlie@example.com"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment and objectValueFragment together`() =
        MockTenantModuleBootstrapper("extend type Query { globalConfig: String, baz: Baz } type Baz { x: Int, y: String }") {
            fieldWithValue("Query" to "globalConfig", "Premium")
            fieldWithValue("Query" to "baz", mkEngineObjectData(schema.schema.getObjectType("Baz"), mapOf("x" to 100)))
            field("Baz" to "y") {
                resolver {
                    objectSelections("x")
                    querySelections("globalConfig")
                    fn { _, obj, qry, _, _ ->
                        val config = qry.fetchAs<String>("globalConfig")
                        val x = obj.fetchAs<Int>("x")
                        "$config item with value $x"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz { y }}")
                .assertJson("{data: {baz: {y: \"Premium item with value 100\"}}}")
        }

    @Test
    fun `resolve field with queryValueFragment - transitive dependencies`() =
        MockTenantModuleBootstrapper("extend type Query { baseValue: Int, multipliedValue: Int, finalValue: Int }") {
            fieldWithValue("Query" to "baseValue", 5)
            field("Query" to "multipliedValue") {
                resolver {
                    querySelections("baseValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("baseValue") * 2
                    }
                }
            }
            field("Query" to "finalValue") {
                resolver {
                    querySelections("multipliedValue")
                    fn { _, _, qry, _, _ ->
                        qry.fetchAs<Int>("multipliedValue") + 10
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{finalValue}")
                .assertJson("""{"data": {"finalValue": 20}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - multiple query selections`() {
        val userCount = AtomicInteger()
        val configCount = AtomicInteger()
        MockTenantModuleBootstrapper("extend type Query { currentUser: String, globalConfig: String, combined: String }") {
            field("Query" to "currentUser") {
                resolver {
                    fn { _, _, _, _, _ ->
                        userCount.incrementAndGet()
                        "David"
                    }
                }
            }
            field("Query" to "globalConfig") {
                resolver {
                    fn { _, _, _, _, _ ->
                        configCount.incrementAndGet()
                        "Advanced"
                    }
                }
            }
            field("Query" to "combined") {
                resolver {
                    querySelections("currentUser globalConfig")
                    fn { _, _, qry, _, _ ->
                        val user = qry.fetchAs<String>("currentUser")
                        val config = qry.fetchAs<String>("globalConfig")
                        "$user - $config mode"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{combined}")
                .assertJson("""{"data": {"combined": "David - Advanced mode"}}""")
                .also {
                    assertEquals(1, userCount.get(), "currentUser should be resolved only once")
                    assertEquals(1, configCount.get(), "globalConfig should be resolved only once")
                }
        }
    }

    @Test
    fun `resolve field with queryValueFragment - inline fragment without type condition`() =
        MockTenantModuleBootstrapper("extend type Query { isEnabled: Boolean, config: String, result: String }") {
            fieldWithValue("Query" to "isEnabled", true)
            fieldWithValue("Query" to "config", "production")
            field("Query" to "result") {
                resolver {
                    querySelections("... { isEnabled config }")
                    fn { _, _, qry, _, _ ->
                        val enabled = qry.fetchAs<Boolean>("isEnabled")
                        val config = qry.fetchAs<String>("config")
                        if (enabled) "Running in $config" else "Disabled"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{result}")
                .assertJson("""{"data": {"result": "Running in production"}}""")
        }

    @Test
    fun `resolve field with queryValueFragment - handles null gracefully`() =
        MockTenantModuleBootstrapper("extend type Query { optionalValue: String, result: String }") {
            fieldWithValue("Query" to "optionalValue", null)
            field("Query" to "result") {
                resolver {
                    querySelections("optionalValue")
                    fn { _, _, qry, _, _ ->
                        val value = qry.fetch("optionalValue") as? String
                        value ?: "No value provided"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{result}")
                .assertJson("""{"data": {"result": "No value provided"}}""")
        }

    @Test
    fun `resolve mutation with queryValueFragment`() =
        MockTenantModuleBootstrapper("extend type Query { string1: String } extend type Mutation { string1: String }") {
            fieldWithValue("Query" to "string1", "InitialValue")
            field("Mutation" to "string1") {
                resolver {
                    querySelections("string1")
                    fn { _, _, qry, _, _ ->
                        val currentValue = qry.fetchAs<String>("string1")
                        "Mutated from: $currentValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("mutation { string1 }")
                .assertJson("{data: {string1: \"Mutated from: InitialValue\"}}")
        }

    @Test
    fun `resolve field with queryValueFragment - nested object access`() =
        MockTenantModuleBootstrapper("extend type Query { bar: Bar, baz: Baz } type Bar { value: String } type Baz { x: Int, y: String }") {
            fieldWithValue("Query" to "bar", mkEngineObjectData(schema.schema.getObjectType("Bar"), mapOf()))
            fieldWithValue("Bar" to "value", "BarValue")
            fieldWithValue("Query" to "baz", mkEngineObjectData(schema.schema.getObjectType("Baz"), mapOf()))
            field("Baz" to "y") {
                resolver {
                    querySelections("bar { value }")
                    fn { _, _, qry, _, _ ->
                        val bar = qry.fetchAs<EngineObjectData>("bar")
                        val barValue = bar.fetch("value")
                        "Baz sees bar value: $barValue"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{baz { y }}")
                .assertJson("{data: {baz: {y: \"Baz sees bar value: BarValue\"}}}")
        }

    @Test
    fun `resolve field with queryValueFragment - typed inline fragment`() =
        MockTenantModuleBootstrapper("extend type Query { enabled: Boolean, message: String, status: String }") {
            fieldWithValue("Query" to "enabled", false)
            fieldWithValue("Query" to "message", "System offline")
            field("Query" to "status") {
                resolver {
                    querySelections("... on Query { enabled message }")
                    fn { _, _, qry, _, _ ->
                        val enabled = qry.fetchAs<Boolean>("enabled")
                        val message = qry.fetchAs<String>("message")
                        if (!enabled) message else "OK"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{status}")
                .assertJson("""{"data": {"status": "System offline"}}""")
        }

    @Test
    fun `queryValueFragment with unclosed brace should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockTenantModuleBootstrapper("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("{ field") // Missing closing brace
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with invalid field syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockTenantModuleBootstrapper("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("field(") // Invalid - parenthesis without arguments
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment referencing non-existent field should fail at build time`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            MockTenantModuleBootstrapper("extend type Query { existingField: String, result: String }") {
                fieldWithValue("Query" to "existingField", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("nonExistentField") // Field doesn't exist in schema
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with invalid fragment syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockTenantModuleBootstrapper("extend type Query { field: String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("fragment on Query { field }") // Missing fragment name
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment with invalid variable syntax should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockTenantModuleBootstrapper("extend type Query { field(arg: Int!): String, result: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("field(arg: $)") // Invalid variable syntax
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }
        }
    }

    @Test
    fun `queryValueFragment with empty selection set should fail at build time`() {
        assertThrows<IllegalArgumentException> {
            MockTenantModuleBootstrapper("extend type Query { result: String }") {
                field("Query" to "result") {
                    resolver {
                        querySelections("{}") // Empty selection set
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }

    @Test
    fun `queryValueFragment with wrong type condition should fail at build time`() {
        assertThrows<RequiredSelectionsAreInvalid> {
            MockTenantModuleBootstrapper("extend type Query { field: String, result: String } extend type Mutation { dummy: String }") {
                fieldWithValue("Query" to "field", "value")
                field("Query" to "result") {
                    resolver {
                        querySelections("... on Mutation { field }") // Wrong type - should be Query
                        fn { _, _, _, _, _ -> "should not execute" }
                    }
                }
            }.runFeatureTest { }
        }
    }
}
