package viaduct.tenant.codegen.kotlingen

import graphql.schema.idl.SchemaParser
import java.io.File
import kotlin.test.assertContains
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper

// This test suite is useful for inspecting the results of resolver generation.
// While each test case makes only a small number of assertions, they are useful places
// for setting a breakpoint to inspect the generated output before it gets compiled.
class FieldResolverGeneratorTest {
    private fun mkSchema(sdl: String): ViaductSchema {
        val tdr = SchemaParser().parse(sdl)
        return ViaductSchema.fromTypeDefinitionRegistry(tdr)
    }

    private fun gen(
        sdl: String,
        typeName: String
    ): String {
        val schema = mkSchema(sdl)
        val type = schema.types[typeName] as ViaductSchema.Record
        val contents = genResolver(typeName, type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper(schema))
        return contents.toString()
    }

    @Test
    fun `verifies that fieldResolvergenerator function runs succesfully`() {
        val sdl = """
                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: Int
                }
        """.trimIndent()

        val schema = mkSchema(sdl)
        assertDoesNotThrow {
            schema.generateFieldResolvers(
                Args(
                    "viaduct.tenant",
                    "fooo",
                    "tenant_name",
                    "bar",
                    File.createTempFile("temp", null).also { it.deleteOnExit() },
                    File.createTempFile("temp1", null).also { it.deleteOnExit() },
                    File.createTempFile("temp2", null).also { it.deleteOnExit() },
                    baseTypeMapper = ViaductBaseTypeMapper(schema)
                )
            )
        }
    }

    @Test
    fun `generates resolver classes`() {
        val contents = gen(
            """
                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: Int
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.startsWith("package pkg.tenant.resolverbases\n"))
        assertFalse(contents.contains("MutationExecutionContext"))
        assertContains(contents, "object SubjectResolvers ")
        assertContains(contents, "class Field ")
        assertContains(contents, "batchResolve")
    }

    @Test
    fun `generates mutation resolvers`() {
        val contents = gen(
            """
                type Query { placeholder: Int }
                type Mutation { field(x: Int!): Int! }
                type Subscription { placeholder: Int }
            """.trimIndent(),
            "Mutation"
        )
        assertTrue(contents.contains("MutationFieldExecutionContext"))
        assertFalse(contents.contains("batchResolve"))
    }

    @Test
    fun `generates mutation resolvers with custom mutation type name`() {
        val sdl = """
            schema {
                query: CustomQuery
                mutation: CustomMutation
                subscription: CustomSubscription
            }
            type CustomQuery { placeholder: Int }
            type CustomMutation { field(x: Int!): Int! }
            type CustomSubscription { event: String }
        """.trimIndent()

        val schema = mkSchema(sdl)
        val type = schema.types["CustomMutation"] as ViaductSchema.Record

        // With correct mutationTypeName, should generate MutationFieldExecutionContext
        val contentsWithCorrectName = genResolver(
            "CustomMutation",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            queryTypeName = "CustomQuery",
            mutationTypeName = "CustomMutation"
        ).toString()
        assertTrue(
            contentsWithCorrectName.contains("MutationFieldExecutionContext"),
            "Should generate MutationFieldExecutionContext when mutationTypeName matches"
        )

        // With wrong mutationTypeName (default "Mutation"), should NOT generate MutationFieldExecutionContext
        val contentsWithWrongName = genResolver(
            "CustomMutation",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            queryTypeName = "CustomQuery",
            mutationTypeName = "Mutation"
        ).toString()
        assertFalse(
            contentsWithWrongName.contains("MutationFieldExecutionContext"),
            "Should NOT generate MutationFieldExecutionContext when mutationTypeName doesn't match"
        )
    }

    @Test
    fun `generates resolvers with custom query type name`() {
        val sdl = """
            schema {
                query: AppQuery
            }
            type AppQuery { field: Int }
        """.trimIndent()

        val schema = mkSchema(sdl)
        val type = schema.types["AppQuery"] as ViaductSchema.Record

        // With correct queryTypeName, should generate FieldExecutionContext with AppQuery
        val contentsWithCorrectName = genResolver(
            "AppQuery",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema),
            queryTypeName = "AppQuery"
        ).toString()
        assertTrue(
            contentsWithCorrectName.contains("viaduct.api.grts.AppQuery"),
            "Should reference AppQuery in FieldExecutionContext"
        )
        assertFalse(
            contentsWithCorrectName.contains("viaduct.api.grts.Query"),
            "Should NOT reference default Query type"
        )

        // With default queryTypeName, should generate FieldExecutionContext with Query
        val contentsWithDefaultName = genResolver(
            "AppQuery",
            type.fields,
            "pkg.tenant",
            "viaduct.api.grts",
            ViaductBaseTypeMapper(schema)
        ).toString()
        assertTrue(
            contentsWithDefaultName.contains("viaduct.api.grts.Query"),
            "Should reference default Query type when queryTypeName not provided"
        )
    }

    @Test
    fun `generates backing data resolver`() {
        val contents = gen(
            """
                scalar BackingData
                directive @backingData(class: String!) on FIELD_DEFINITION

                type Query { placeholder: Int }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                type Subject {
                    field: BackingData @backingData(class: "com.airbnb.myCustomType")
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.contains("open suspend fun resolve(ctx: Context): kotlin.Any"))
    }

    @Test
    fun `generates resolvers that return ID scalars`() {
        gen(
            """
                type Query { field: ID }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
            """.trimIndent(),
            "Query"
        ).let {
            assertTrue(it.contains("kotlin.String?"))
        }

        gen(
            """
                directive @idOf(type: String!) on FIELD_DEFINITION
                type Query { field: ID @idOf(type: "Foo") }
                type Mutation { placeholder: Int }
                type Subscription { placeholder: Int }
                interface Node { id: ID! }
                type Foo implements Node { id: ID! }
            """.trimIndent(),
            "Query"
        ).let {
            assertTrue(it.contains("GlobalID<viaduct.api.grts.Foo>"))
        }
    }
}
