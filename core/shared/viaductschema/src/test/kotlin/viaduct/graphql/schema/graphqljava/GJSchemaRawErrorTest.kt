package viaduct.graphql.schema.graphqljava

import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.UnionTypeDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/** Tests for error checking in GJSchemaRaw TypeDef classes. */
class GJSchemaRawErrorTest {
    private val schema = SchemaWithData()

    @Test
    fun `accessing unpopulated Scalar throws helpful error`() {
        val def = ScalarTypeDefinition.newScalarTypeDefinition().name("TestScalar").build()
        val scalar = SchemaWithData.Scalar(schema, def.name, TypeDefData(def, emptyList()))

        val exception = shouldThrow<IllegalStateException> {
            scalar.appliedDirectives
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Enum throws helpful error`() {
        val def = EnumTypeDefinition.newEnumTypeDefinition().name("TestEnum").build()
        val enum = SchemaWithData.Enum(schema, def.name, TypeDefData(def, emptyList()))

        val exception = shouldThrow<IllegalStateException> {
            enum.values
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Union throws helpful error`() {
        val def = UnionTypeDefinition.newUnionTypeDefinition().name("TestUnion").build()
        val union = SchemaWithData.Union(schema, def.name, TypeDefData(def, emptyList()))

        val exception = shouldThrow<IllegalStateException> {
            union.possibleObjectTypes
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Interface throws helpful error`() {
        val def = InterfaceTypeDefinition.newInterfaceTypeDefinition().name("TestInterface").build()
        val iface = SchemaWithData.Interface(schema, def.name, TypeDefData(def, emptyList()))

        val exception = shouldThrow<IllegalStateException> {
            iface.fields
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Object throws helpful error`() {
        val def = ObjectTypeDefinition.newObjectTypeDefinition().name("TestObject").build()
        val obj = SchemaWithData.Object(schema, def.name, TypeDefData(def, emptyList()))

        val exception = shouldThrow<IllegalStateException> {
            obj.fields
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Input throws helpful error`() {
        val def = InputObjectTypeDefinition.newInputObjectDefinition().name("TestInput").build()
        val input = SchemaWithData.Input(schema, def.name, TypeDefData(def, emptyList()))

        val exception = shouldThrow<IllegalStateException> {
            input.fields
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Scalar throws helpful error`() {
        val def = ScalarTypeDefinition.newScalarTypeDefinition().name("TestScalar").build()
        val scalar = SchemaWithData.Scalar(schema, def.name, TypeDefData(def, emptyList()))

        // First populate succeeds
        val ext = ViaductSchema.Extension.of(
            def = scalar,
            memberFactory = { emptyList<Nothing>() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        scalar.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            scalar.populate(listOf(ext))
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Enum throws helpful error`() {
        val def = EnumTypeDefinition.newEnumTypeDefinition().name("TestEnum").build()
        val enum = SchemaWithData.Enum(schema, def.name, TypeDefData(def, emptyList()))

        // First populate succeeds
        val ext = ViaductSchema.Extension.of(
            def = enum,
            memberFactory = { emptyList<SchemaWithData.EnumValue>() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        enum.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            enum.populate(listOf(ext))
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Union throws helpful error`() {
        val def = UnionTypeDefinition.newUnionTypeDefinition().name("TestUnion").build()
        val union = SchemaWithData.Union(schema, def.name, TypeDefData(def, emptyList()))

        // First populate succeeds
        val ext = ViaductSchema.Extension.of(
            def = union,
            memberFactory = { emptyList<SchemaWithData.Object>() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        union.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            union.populate(listOf(ext))
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Interface throws helpful error`() {
        val def = InterfaceTypeDefinition.newInterfaceTypeDefinition().name("TestInterface").build()
        val iface = SchemaWithData.Interface(schema, def.name, TypeDefData(def, emptyList()))

        // First populate succeeds
        val ext = ViaductSchema.ExtensionWithSupers.of(
            def = iface,
            memberFactory = { emptyList<SchemaWithData.Field>() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null,
            supers = emptyList()
        )
        iface.populate(listOf(ext), emptySet())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            iface.populate(listOf(ext), emptySet())
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Object throws helpful error`() {
        val def = ObjectTypeDefinition.newObjectTypeDefinition().name("TestObject").build()
        val obj = SchemaWithData.Object(schema, def.name, TypeDefData(def, emptyList()))

        // First populate succeeds
        val ext = ViaductSchema.ExtensionWithSupers.of(
            def = obj,
            memberFactory = { emptyList<SchemaWithData.Field>() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null,
            supers = emptyList()
        )
        obj.populate(listOf(ext), emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            obj.populate(listOf(ext), emptyList())
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Input throws helpful error`() {
        val def = InputObjectTypeDefinition.newInputObjectDefinition().name("TestInput").build()
        val input = SchemaWithData.Input(schema, def.name, TypeDefData(def, emptyList()))

        // First populate succeeds
        val ext = ViaductSchema.Extension.of(
            def = input,
            memberFactory = { emptyList<SchemaWithData.Field>() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        input.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            input.populate(listOf(ext))
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }
}
