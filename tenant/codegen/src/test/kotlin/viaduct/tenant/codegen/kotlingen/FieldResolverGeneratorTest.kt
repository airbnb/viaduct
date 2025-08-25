package viaduct.tenant.codegen.kotlingen

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper

// This test suite is useful for inspecting the results of resolver generation.
// While each test case makes only a small number of assertions, they are useful places
// for setting a breakpoint to inspect the generated output before it gets compiled.
class FieldResolverGeneratorTest {
    private fun mkSchema(sdl: String): ViaductExtendedSchema {
        val tdr = SchemaParser().parse(sdl)
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(tdr)
        return GJSchema.fromSchema(schema)
    }

    private fun gen(
        sdl: String,
        typeName: String
    ): String {
        val schema = mkSchema(sdl)
        val type = schema.types[typeName] as ViaductExtendedSchema.Record
        val contents = genResolver(typeName, type.fields, "pkg.tenant", "viaduct.api.grts", ViaductBaseTypeMapper())
        return contents.toString()
    }

    @Test
    fun `verifies that fieldResolvergenerator function runs succesfully`() {
        val sdl = """
                type Query { placeholder: Int }
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
                    "bar",
                    File.createTempFile("temp", null).also { it.deleteOnExit() },
                    File.createTempFile("temp1", null).also { it.deleteOnExit() },
                    File.createTempFile("temp2", null).also { it.deleteOnExit() },
                    baseTypeMapper = ViaductBaseTypeMapper()
                )
            )
        }
    }

    @Test
    fun `generates resolver classes`() {
        val contents = gen(
            """
                type Query { placeholder: Int }
                type Subject {
                    field: Int
                }
            """.trimIndent(),
            "Subject"
        )

        assertTrue(contents.startsWith("package pkg.tenant.resolverbases\n"))
        assertFalse(contents.contains("MutationExecutionContext"))
        assertTrue(contents.contains("object SubjectResolvers "))
        assertTrue(contents.contains("class Field "))
    }

    @Test
    fun `generates mutation resolvers`() {
        val contents = gen(
            """
                type Query { placeholder: Int }
                type Mutation { field(x: Int!): Int! }
            """.trimIndent(),
            "Mutation"
        )
        assertTrue(contents.contains("MutationFieldExecutionContext"))
    }

    @Test
    fun `generates backing data resolver`() {
        val contents = gen(
            """
                scalar BackingData
                directive @backingData(class: String!) on FIELD_DEFINITION

                type Query { placeholder: Int }
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
            """.trimIndent(),
            "Query"
        ).let {
            assertTrue(it.contains("kotlin.String?"))
        }

        gen(
            """
                directive @idOf(type: String!) on FIELD_DEFINITION
                type Query { field: ID @idOf(type: "Foo") }
                interface Node { id: ID! }
                type Foo implements Node { id: ID! }
            """.trimIndent(),
            "Query"
        ).let {
            assertTrue(it.contains("GlobalID<out viaduct.api.grts.Foo>"))
        }
    }
}
