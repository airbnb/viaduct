package viaduct.graphql.schema.graphqljava

import graphql.Scalars
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLUnionType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/** Tests for error checking in GJSchema TypeDef classes. */
class GJSchemaErrorTest {
    private val schema = SchemaWithData()

    @Test
    fun `accessing unpopulated Scalar throws helpful error`() {
        val def = GraphQLScalarType.newScalar().name("TestScalar").coercing(Scalars.GraphQLString.coercing).build()
        val scalar = SchemaWithData.Scalar(schema, def.name, def)

        val exception = shouldThrow<IllegalStateException> {
            scalar.appliedDirectives
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Enum throws helpful error`() {
        val def = GraphQLEnumType.newEnum().name("TestEnum").build()
        val enum = SchemaWithData.Enum(schema, def.name, def)

        val exception = shouldThrow<IllegalStateException> {
            enum.values
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Union throws helpful error`() {
        // GraphQLUnionType requires at least one member type
        val memberType = GraphQLObjectType.newObject().name("Member").build()
        val def = GraphQLUnionType.newUnionType().name("TestUnion").possibleType(memberType).build()
        val union = SchemaWithData.Union(schema, def.name, def)

        val exception = shouldThrow<IllegalStateException> {
            union.possibleObjectTypes
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Interface throws helpful error`() {
        val def = GraphQLInterfaceType.newInterface().name("TestInterface").build()
        val iface = SchemaWithData.Interface(schema, def.name, def)

        val exception = shouldThrow<IllegalStateException> {
            iface.fields
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Object throws helpful error`() {
        val def = GraphQLObjectType.newObject().name("TestObject").build()
        val obj = SchemaWithData.Object(schema, def.name, def)

        val exception = shouldThrow<IllegalStateException> {
            obj.fields
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Input throws helpful error`() {
        val def = GraphQLInputObjectType.newInputObject().name("TestInput").build()
        val input = SchemaWithData.Input(schema, def.name, def)

        val exception = shouldThrow<IllegalStateException> {
            input.fields
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Scalar throws helpful error`() {
        val def = GraphQLScalarType.newScalar().name("TestScalar").coercing(Scalars.GraphQLString.coercing).build()
        val scalar = SchemaWithData.Scalar(schema, def.name, def)

        val extensions = listOf(
            ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
                def = scalar,
                memberFactory = { emptyList() },
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null
            )
        )

        // First populate succeeds
        scalar.populate(extensions)

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            scalar.populate(extensions)
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Enum throws helpful error`() {
        val def = GraphQLEnumType.newEnum().name("TestEnum").build()
        val enum = SchemaWithData.Enum(schema, def.name, def)

        val extensions = listOf(
            ViaductSchema.Extension.of<SchemaWithData.Enum, SchemaWithData.EnumValue>(
                def = enum,
                memberFactory = { emptyList() },
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null
            )
        )

        // First populate succeeds
        enum.populate(extensions)

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            enum.populate(extensions)
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Union throws helpful error`() {
        // GraphQLUnionType requires at least one member type
        val memberType = GraphQLObjectType.newObject().name("Member").build()
        val def = GraphQLUnionType.newUnionType().name("TestUnion").possibleType(memberType).build()
        val union = SchemaWithData.Union(schema, def.name, def)

        val extensions = listOf(
            ViaductSchema.Extension.of<SchemaWithData.Union, SchemaWithData.Object>(
                def = union,
                memberFactory = { emptyList() },
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null
            )
        )

        // First populate succeeds
        union.populate(extensions)

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            union.populate(extensions)
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Interface throws helpful error`() {
        val def = GraphQLInterfaceType.newInterface().name("TestInterface").build()
        val iface = SchemaWithData.Interface(schema, def.name, def)

        val extensions = listOf(
            ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Interface, SchemaWithData.Field>(
                def = iface,
                memberFactory = { emptyList() },
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null,
                supers = emptyList()
            )
        )

        // First populate succeeds
        iface.populate(extensions, emptySet())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            iface.populate(extensions, emptySet())
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Object throws helpful error`() {
        val def = GraphQLObjectType.newObject().name("TestObject").build()
        val obj = SchemaWithData.Object(schema, def.name, def)

        val extensions = listOf(
            ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Object, SchemaWithData.Field>(
                def = obj,
                memberFactory = { emptyList() },
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null,
                supers = emptyList()
            )
        )

        // First populate succeeds
        obj.populate(extensions, emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            obj.populate(extensions, emptyList())
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Input throws helpful error`() {
        val def = GraphQLInputObjectType.newInputObject().name("TestInput").build()
        val input = SchemaWithData.Input(schema, def.name, def)

        val extensions = listOf(
            ViaductSchema.Extension.of<SchemaWithData.Input, SchemaWithData.Field>(
                def = input,
                memberFactory = { emptyList() },
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null
            )
        )

        // First populate succeeds
        input.populate(extensions)

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            input.populate(extensions)
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }
}
