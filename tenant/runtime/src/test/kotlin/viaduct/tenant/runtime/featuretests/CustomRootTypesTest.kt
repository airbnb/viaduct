package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.get

/**
 * Integration tests for custom root types support.
 * Verifies that schemas with custom root type names (CustomQuery, CustomMutation, CustomSubscription)
 * work correctly at runtime with resolvers executing correctly.
 *
 * These tests verify runtime behavior with fake GRTs. For code generation tests with custom root types,
 * see ParameterizedSchemaDrivenTests which includes "custom-schema" and "custom-root-types" test cases.
 */
@ExperimentalCoroutinesApi
class CustomRootTypesTest {
    @Test
    fun `custom query root type executes simple field resolver`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              value: Int
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "value") { 42 }
            .build()
            .assertJson("{data: {value: 42}}", "{value}")
    }

    @Test
    fun `custom mutation root type executes resolver with arguments`() {
        var value = 0
        val fixture = FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
              mutation: CustomMutation
            }
            type CustomQuery { value: Int }
            type CustomMutation {
              setValue(newValue: Int!): Int
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "value") { value }
            .resolver("CustomMutation" to "setValue") { ctx ->
                val oldValue = value
                value = ctx.arguments.get<Int>("newValue")
                oldValue
            }
            .build()

        fixture.assertJson("{data: {value: 0}}", "{value}")
        fixture.assertJson("{data: {setValue: 0}}", "mutation { setValue(newValue: 99) }")
        fixture.assertJson("{data: {value: 99}}", "{value}")
        assertEquals(99, value)
    }

    @Test
    fun `custom query and mutation root types work together in same schema`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
              mutation: CustomMutation
            }
            type CustomQuery {
              currentValue: Int
            }
            type CustomMutation {
              updateValue(newValue: Int!): Int
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "currentValue") { 10 }
            .resolver("CustomMutation" to "updateValue") { ctx ->
                ctx.arguments.get<Int>("newValue")
            }
            .build()
            .apply {
                assertJson("{data: {currentValue: 10}}", "{currentValue}")
                assertJson("{data: {updateValue: 50}}", "mutation { updateValue(newValue: 50) }")
            }
    }

    @Test
    fun `custom query root type returns object type`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              shop(id: ID!): Shop
            }
            type Shop {
              id: ID!
              name: String!
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "shop") { ctx ->
                val id = ctx.arguments.get<String>("id")
                mapOf("id" to id, "name" to "Shop $id")
            }
            .build()
            .assertJson(
                "{data: {shop: {id: \"test-123\", name: \"Shop test-123\"}}}",
                "{shop(id: \"test-123\") {id name}}"
            )
    }

    @Test
    fun `custom mutation root type returns object type`() {
        FeatureTestBuilder(
            """
            schema {
              query: EmptyQuery
              mutation: CustomMutation
            }
            type EmptyQuery { empty: Int }
            type CustomMutation {
              createShop(name: String!): Shop
            }
            type Shop {
              id: ID!
              name: String!
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("EmptyQuery" to "empty") { null }
            .resolver("CustomMutation" to "createShop") { ctx ->
                val name = ctx.arguments.get<String>("name")
                mapOf("id" to "new-123", "name" to name)
            }
            .build()
            .assertJson(
                "{data: {createShop: {id: \"new-123\", name: \"Test Shop\"}}}",
                "mutation { createShop(name: \"Test Shop\") {id name} }"
            )
    }

    @Test
    fun `custom query root type returns list of objects`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              shops: [Shop!]!
            }
            type Shop {
              id: ID!
              name: String!
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "shops") {
                listOf(
                    mapOf("id" to "1", "name" to "Shop A"),
                    mapOf("id" to "2", "name" to "Shop B")
                )
            }
            .build()
            .assertJson(
                "{data: {shops: [{id: \"1\", name: \"Shop A\"}, {id: \"2\", name: \"Shop B\"}]}}",
                "{shops {id name}}"
            )
    }

    @Test
    fun `custom query root type with multiple fields executes all resolvers`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              intField: Int
              stringField: String
              boolField: Boolean
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "intField") { 42 }
            .resolver("CustomQuery" to "stringField") { "test" }
            .resolver("CustomQuery" to "boolField") { true }
            .build()
            .assertJson(
                "{data: {intField: 42, stringField: \"test\", boolField: true}}",
                "{intField stringField boolField}"
            )
    }

    @Test
    fun `custom query root type with nested type resolvers`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              shop: Shop
            }
            type Shop {
              id: ID!
              name: String!
              department: Department
            }
            type Department {
              id: ID!
              name: String!
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "shop") {
                mapOf("id" to "shop1", "name" to "Main Shop")
            }
            .resolver("Shop" to "department") {
                mapOf("id" to "dept1", "name" to "Electronics")
            }
            .build()
            .assertJson(
                "{data: {shop: {id: \"shop1\", name: \"Main Shop\", department: {id: \"dept1\", name: \"Electronics\"}}}}",
                "{shop {id name department {id name}}}"
            )
    }

    @Test
    fun `custom query root type with field arguments performs computation`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              multiply(a: Int!, b: Int!): Int
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "multiply") { ctx ->
                val a = ctx.arguments.get<Int>("a")
                val b = ctx.arguments.get<Int>("b")
                a * b
            }
            .build()
            .assertJson(
                "{data: {multiply: 24}}",
                "{multiply(a: 6, b: 4)}"
            )
    }

    @Test
    fun `custom mutation root type with multiple mutations`() {
        FeatureTestBuilder(
            """
            schema {
              query: EmptyQuery
              mutation: CustomMutation
            }
            type EmptyQuery { empty: Int }
            type CustomMutation {
              createItem(name: String!): String
              deleteItem(id: ID!): Boolean
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("EmptyQuery" to "empty") { null }
            .resolver("CustomMutation" to "createItem") { ctx ->
                ctx.arguments.get<String>("name")
            }
            .resolver("CustomMutation" to "deleteItem") { ctx ->
                ctx.arguments.get<String>("id") == "123"
            }
            .build()
            .apply {
                assertJson(
                    "{data: {createItem: \"TestItem\"}}",
                    "mutation { createItem(name: \"TestItem\") }"
                )
                assertJson(
                    "{data: {deleteItem: true}}",
                    "mutation { deleteItem(id: \"123\") }"
                )
            }
    }

    @Test
    fun `custom query root type returning null`() {
        FeatureTestBuilder(
            """
            schema {
              query: CustomQuery
            }
            type CustomQuery {
              nullableField: String
            }
            """.trimIndent(),
            useFakeGRTs = true
        )
            .resolver("CustomQuery" to "nullableField") { null }
            .build()
            .assertJson("{data: {nullableField: null}}", "{nullableField}")
    }
}
