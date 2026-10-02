package viaduct.gradle

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.service.api.scoping.SchemaScoping

@OptIn(ExperimentalApi::class)
class ScopedSchemaValidatorTest {
    private fun schemaFromSdl(sdl: String): GraphQLSchema =
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(sdl).apply {
                DefaultSchemaFactory.addDefaults(this, allowExisting = true)
            },
        )

    private fun validate(
        sdl: String,
        scoping: SchemaScoping,
    ) = ScopedSchemaValidator.validate(schemaFromSdl(sdl), scoping)

    @Test
    fun `an application that declares no scopes is not checked at all`() {
        val failures = validate(
            """
            type Query {
              greeting: String
            }
            """.trimIndent(),
            SchemaScoping.EMPTY,
        )

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `a declared universe with no scoped schemas is still checked`() {
        val failures = validate(
            """
            type Query @scope(to: ["publik"]) {
              greeting: String
            }
            """.trimIndent(),
            SchemaScoping(scopeUniverse = setOf("public"), scopedSchemas = emptyMap()),
        )

        assertEquals(1, failures.size, failures.toString())
        failures.single() shouldContain "publik"
        failures.single() shouldContain "the declared scope universe"
    }

    @Test
    fun `a scope set that removes the query root names the scoped schema`() {
        val failures = validate(
            """
            type Query @scope(to: ["public"]) {
              greeting: String
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("public", "private"),
                scopedSchemas = mapOf("PRIVATE_API" to setOf("private")),
            ),
        )

        assertEquals(1, failures.size, failures.toString())
        failures.single() shouldStartWith "Could not build scoped schema 'PRIVATE_API' (scopes: [private])"
    }

    @Test
    fun `a projection that loses fields is not a failure`() {
        val failures = validate(
            """
            type Query @scope(to: ["default", "extras"]) {
              name: String
              homeworld: Planet
            }

            type Planet @scope(to: ["extras"]) {
              name: String
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("default", "extras"),
                scopedSchemas = mapOf("DEFAULT_API" to setOf("default")),
            ),
        )

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `two scoped schemas selecting the same scopes are built once and reported together`() {
        val failures = validate(
            """
            type Query @scope(to: ["public"]) {
              greeting: String
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("public", "private"),
                scopedSchemas = mapOf("ALPHA" to setOf("private"), "BETA" to setOf("private")),
            ),
        )

        assertEquals(1, failures.size, failures.toString())
        failures.single() shouldContain "scoped schemas 'ALPHA', 'BETA'"
    }

    @Test
    fun `every broken scope set is reported rather than only the first`() {
        val failures = validate(
            """
            type Query @scope(to: ["public"]) {
              greeting: String
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("public", "private", "secret"),
                scopedSchemas = mapOf("PRIVATE_API" to setOf("private"), "SECRET_API" to setOf("secret")),
            ),
        )

        assertEquals(2, failures.size, failures.toString())
        failures[0] shouldStartWith "Could not build scoped schema 'PRIVATE_API'"
        failures[1] shouldStartWith "Could not build scoped schema 'SECRET_API'"
    }

    @Test
    fun `an undeclared scope name is reported once rather than once per scope set`() {
        val failures = validate(
            """
            type Query @scope(to: ["publik"]) {
              greeting: String
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("public", "private"),
                scopedSchemas = mapOf("ALPHA" to setOf("public"), "BETA" to setOf("private")),
            ),
        )

        assertEquals(1, failures.size, failures.toString())
        failures.single() shouldStartWith "Could not build the declared scope universe"
        failures.single() shouldContain "publik"
    }

    @Test
    fun `a type kept alive by a directive is reported once rather than once per scope set`() {
        val failures = validate(
            """
            enum SimpleEnum @scope(to: ["test-scope"]) {
              Foo
            }

            directive @directiveWithEnum(x: SimpleEnum) on FIELD_DEFINITION

            extend type Query @scope(to: ["*"]) {
              a: Int @directiveWithEnum(x: Foo)
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("test-scope", "other-scope"),
                scopedSchemas = mapOf("ALPHA" to setOf("test-scope"), "BETA" to setOf("other-scope")),
            ),
        )

        assertEquals(1, failures.size, failures.toString())
        failures.single() shouldStartWith "Could not build the declared scope universe"
        failures.single() shouldContain "SimpleEnum"
    }

    @Test
    fun `a schema whose every scope is declared passes`() {
        val failures = validate(
            """
            type Query @scope(to: ["default", "extras"]) {
              name: String
            }
            """.trimIndent(),
            SchemaScoping(
                scopeUniverse = setOf("default", "extras"),
                scopedSchemas = mapOf(
                    "publicSchema" to setOf("default"),
                    "publicSchemaWithExtras" to setOf("default", "extras"),
                ),
            ),
        )

        assertEquals(emptyList<String>(), failures)
    }
}
