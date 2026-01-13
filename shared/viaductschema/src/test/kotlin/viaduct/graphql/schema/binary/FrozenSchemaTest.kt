package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.graphqljava.GJSchemaRaw

/**
 * Tests for BSchema freezing and mutation prevention.
 *
 * These tests verify that once a schema is frozen, all mutation operations
 * throw IllegalStateException. Each test corresponds to a checkMutation()
 * call site in BSchema.kt.
 */
class FrozenSchemaTest {
    /**
     * Helper function to create a simple BSchema instance for testing.
     */
    private fun createSimpleSchema(): BSchema {
        val sdl = """
            type Query {
                hello: String
            }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)

        // Round-trip through binary format to get a BSchema
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        return readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema
    }

    @Test
    fun `freeze is idempotent`() {
        val schema = createSimpleSchema()

        schema.freeze()
        assert(schema.frozen)

        // Freezing again should not throw
        schema.freeze()
        assert(schema.frozen)

        // Multiple freezes should be safe
        schema.freeze()
        schema.freeze()
        assert(schema.frozen)
    }

    @Test
    fun `setting queryTypeDef after freeze throws exception`() {
        val schema = BSchema()
        val queryType = schema.addTypeDef<BSchema.Object>("Query")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            schema.queryTypeDef = queryType
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting mutationTypeDef after freeze throws exception`() {
        val schema = BSchema()
        val mutationType = schema.addTypeDef<BSchema.Object>("Mutation")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            schema.mutationTypeDef = mutationType
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting subscriptionTypeDef after freeze throws exception`() {
        val schema = BSchema()
        val subscriptionType = schema.addTypeDef<BSchema.Object>("Subscription")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            schema.subscriptionTypeDef = subscriptionType
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `makeDirective after freeze throws exception`() {
        val schema = BSchema()

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            schema.makeDirective("deprecated")
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `addTypeDef after freeze throws exception`() {
        val schema = BSchema()

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            schema.addTypeDef<BSchema.Object>("Query")
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Field args after freeze throws exception`() {
        val schema = createSimpleSchema()
        val queryType = schema.types["Query"] as BSchema.Object
        val field = queryType.fields.first()

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            field.args = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Directive sourceLocation after freeze throws exception`() {
        val schema = BSchema()
        val directive = schema.makeDirective("test")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            directive.sourceLocation = null
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Directive isRepeatable after freeze throws exception`() {
        val schema = BSchema()
        val directive = schema.makeDirective("test")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            directive.isRepeatable = true
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Directive allowedLocations after freeze throws exception`() {
        val schema = BSchema()
        val directive = schema.makeDirective("test")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            directive.allowedLocations = emptySet()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Directive args after freeze throws exception`() {
        val schema = BSchema()
        val directive = schema.makeDirective("test")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            directive.args = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Extension members after freeze throws exception`() {
        val schema = createSimpleSchema()
        val queryType = schema.types["Query"] as BSchema.Object
        val extension = queryType.extensions.first()

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            extension.members = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Enum extensions after freeze throws exception`() {
        val sdl = """
            enum Status { ACTIVE, INACTIVE }
            type Query { status: Status }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        val schema = readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema

        val enumType = schema.types["Status"] as BSchema.Enum

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            enumType.extensions = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Scalar sourceLocation after freeze throws exception`() {
        val schema = BSchema()
        val scalar = schema.addTypeDef<BSchema.Scalar>("CustomScalar")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            scalar.sourceLocation = null
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Scalar appliedDirectives after freeze throws exception`() {
        val schema = BSchema()
        val scalar = schema.addTypeDef<BSchema.Scalar>("CustomScalar")

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            scalar.appliedDirectives = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Union extensions after freeze throws exception`() {
        val sdl = """
            type A { id: ID }
            type B { id: ID }
            union Result = A | B
            type Query { result: Result }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        val schema = readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema

        val unionType = schema.types["Result"] as BSchema.Union

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            unionType.extensions = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Interface possibleObjectTypes after freeze throws exception`() {
        val sdl = """
            interface Node { id: ID }
            type User implements Node { id: ID, name: String }
            type Query { node: Node }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        val schema = readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema

        val interfaceType = schema.types["Node"] as BSchema.Interface

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            interfaceType.possibleObjectTypes = emptySet()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Interface extensions after freeze throws exception`() {
        val sdl = """
            interface Node { id: ID }
            type User implements Node { id: ID, name: String }
            type Query { node: Node }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        val schema = readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema

        val interfaceType = schema.types["Node"] as BSchema.Interface

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            interfaceType.extensions = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Input extensions after freeze throws exception`() {
        val sdl = """
            input UserInput {
                name: String
                email: String
            }
            type Query { user(input: UserInput): String }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        val schema = readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema

        val inputType = schema.types["UserInput"] as BSchema.Input

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            inputType.extensions = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Object unions after freeze throws exception`() {
        val sdl = """
            type User { id: ID }
            union Result = User
            type Query { result: Result }
        """.trimIndent()

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val gjSchema = GJSchemaRaw.fromRegistry(tdr)
        val tmp = ByteArrayOutputStream()
        writeBSchema(gjSchema, tmp)
        val schema = readBSchema(ByteArrayInputStream(tmp.toByteArray())) as BSchema

        val objectType = schema.types["User"] as BSchema.Object

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            objectType.unions = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `setting Object extensions after freeze throws exception`() {
        val schema = createSimpleSchema()
        val queryType = schema.types["Query"] as BSchema.Object

        schema.freeze()

        val exception = assertThrows<IllegalStateException> {
            queryType.extensions = emptyList()
        }
        assert(exception.message!!.contains("frozen"))
    }

    @Test
    fun `mutations before freeze succeed`() {
        val schema = BSchema()

        // All these should work before freezing
        val queryType = schema.addTypeDef<BSchema.Object>("Query")
        schema.queryTypeDef = queryType

        val mutationType = schema.addTypeDef<BSchema.Object>("Mutation")
        schema.mutationTypeDef = mutationType

        val subscriptionType = schema.addTypeDef<BSchema.Object>("Subscription")
        schema.subscriptionTypeDef = subscriptionType

        val directive = schema.makeDirective("test")
        directive.isRepeatable = true
        directive.allowedLocations = emptySet()
        directive.args = emptyList()
        directive.sourceLocation = null

        val scalar = schema.addTypeDef<BSchema.Scalar>("CustomScalar")
        scalar.sourceLocation = null
        scalar.appliedDirectives = emptyList()

        // Now freeze and verify no exception has been thrown yet
        schema.freeze()

        assert(schema.frozen)
    }

    @Test
    fun `reading and freezing an already frozen schema from binary format`() {
        val schema = createSimpleSchema()

        // Freeze it
        schema.freeze()
        assert(schema.frozen)

        // Freezing again should be safe
        schema.freeze()
        assert(schema.frozen)

        // Attempting any mutation should fail
        val exception = assertThrows<IllegalStateException> {
            schema.addTypeDef<BSchema.Object>("NewType")
        }
        assert(exception.message!!.contains("frozen"))
    }
}
