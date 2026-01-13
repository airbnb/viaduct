@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductTenantUsageException
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.mocks.testGlobalId
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.Concrete
import viaduct.api.testschema.E1
import viaduct.api.testschema.HasAbstractField
import viaduct.api.testschema.I1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.Input3
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.api.testschema.RecursiveObject
import viaduct.api.testschema.TestType
import viaduct.api.testschema.TestUser
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.graphql.TypenameValueWeight
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.engineObjectsAreEquivalent
import viaduct.engine.api.gj
import viaduct.engine.api.mocks.mkRawSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR
import viaduct.mapping.test.InputObjectValueWeight
import viaduct.mapping.test.OutputObjectValueWeight
import viaduct.mapping.test.ir
import viaduct.mapping.test.objectIR

class GRTConvTest : KotestPropertyBase() {
    private val schema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(schema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    @Test
    fun `ID -- id field of Node implementation is a GlobalID`() {
        // sanity
        assertTrue(schema.typeAs<GraphQLObjectType>("O1").interfaces.any { it.name == "Node" })

        assertRoundtrip(
            GRTConv(
                internalContext,
                schema.field("O1" to "id"),
                schema.typeAs("O1"),
                selectionSet = null
            ),
            GlobalIDImpl(O1.Reflection, "foo"),
            IR.Value.String(O1.Reflection.testGlobalId("foo"))
        )
    }

    @Test
    fun `ID -- id field of non-Node type is a String`() {
        assertRoundtrip(
            GRTConv(
                internalContext,
                schema.field("ObjectWithGlobalIds" to "id"),
                schema.typeAs("ObjectWithGlobalIds"),
                selectionSet = null
            ),
            "foo",
            IR.Value.String("foo")
        )
    }

    @Test
    fun `ID -- id-typed object field with idOf directive is a GlobalID`() {
        assertRoundtrip(
            GRTConv(
                internalContext,
                schema.field("ObjectWithGlobalIds" to "id"),
                schema.typeAs("ObjectWithGlobalIds"),
                selectionSet = null
            ),
            "foo",
            IR.Value.String("foo")
        )
    }

    @Test
    fun `ID -- list-id-typed object field is a list of String`() {
        assertRoundtrip(
            GRTConv(
                internalContext,
                schema.field("ObjectWithGlobalIds" to "id8"),
                schema.typeAs("ObjectWithGlobalIds"),
                selectionSet = null
            ),
            listOf("foo"),
            IR.Value.List(listOf(IR.Value.String("foo")))
        )
    }

    @Test
    fun `ID -- list-id-typed object field with idOf directive is a list of GlobalID`() {
        assertRoundtrip(
            GRTConv(
                internalContext,
                schema.field("ObjectWithGlobalIds" to "id4"),
                schema.typeAs("ObjectWithGlobalIds"),
                selectionSet = null
            ),
            listOf(
                GlobalIDImpl(TestUser.Reflection, "foo"),
                GlobalIDImpl(TestUser.Reflection, "bar"),
            ),
            IR.Value.List(
                listOf(IR.Value.String(TestUser.Reflection.testGlobalId("foo")), IR.Value.String(TestUser.Reflection.testGlobalId("bar")))
            )
        )
    }

    @Test
    fun `ID -- id field of input type is a String`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.inputField("InputWithGlobalIDs" to "id")),
            "foo",
            IR.Value.String("foo")
        )
    }

    @Test
    fun `ID -- id field of input type with idOf directive is a GlobalID`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.inputField("InputWithGlobalIDs" to "id2")),
            GlobalIDImpl(TestUser.Reflection, "foo"),
            IR.Value.String(TestUser.Reflection.testGlobalId("foo"))
        )
    }

    @Test
    fun `ID -- list-id-typed input field with idOf directive is a GlobalID`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.inputField("InputWithGlobalIDs" to "id3")),
            listOf(
                GlobalIDImpl(O1.Reflection, "foo"),
                GlobalIDImpl(O1.Reflection, "bar"),
            ),
            IR.Value.List(
                listOf(
                    IR.Value.String(O1.Reflection.testGlobalId("foo")),
                    IR.Value.String(O1.Reflection.testGlobalId("bar")),
                )
            )
        )
    }

    @Test
    fun `scalars -- arb`(): Unit =
        runBlocking {
            val scalars = schema.type("Scalars")
            val conv = GRTConv(internalContext, scalars)
            Arb.ir(schema.schema, scalars).forAll { ir ->
                val ir2 = conv(conv.invert(ir))
                ir == ir2
            }
        }

    @Test
    fun `input obj -- empty`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.type("Input2")),
            Input2.Builder(executionContext).build(),
            IR.Value.Object("Input2", emptyMap())
        )
    }

    @Test
    fun `input obj -- simple`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.type("Input2")),
            Input2.Builder(executionContext).stringField("str").build(),
            IR.Value.Object(
                "Input2",
                mapOf("stringField" to IR.Value.String("str"))
            )
        )
    }

    @Test
    fun `input obj -- unset defaults are not materialized`() {
        // Input3.inputField has a default value. We should be able to roundtrip through IR
        // without setting the value in either the IR or the roundtripped value
        assertRoundtrip(
            GRTConv(internalContext, schema.type("Input3")),
            Input3.Builder(executionContext).build(),
            IR.Value.Object("Input3", emptyMap())
        )
    }

    @Test
    fun `input obj -- nested`() {
        val inp = assertRoundtrip(
            GRTConv(internalContext, schema.type("Input3")),
            Input3.Builder(executionContext)
                .inputField(
                    Input2.Builder(executionContext)
                        .stringField("str")
                        .build()
                )
                .build(),
            IR.Value.Object(
                "Input3",
                mapOf(
                    "inputField" to IR.Value.Object(
                        "Input2",
                        mapOf("stringField" to IR.Value.String("str"))
                    )
                )
            )
        )

        assertEquals("str", inp.inputField?.stringField)
    }

    @Test
    fun `input obj -- arb`(): Unit =
        runBlocking {
            val cfg = Config.default + (OutputObjectValueWeight to 0.0)

            Arb.objectIR(schema.schema, cfg).forAll { ir ->
                val conv = GRTConv(internalContext, schema.type(ir.name))
                val ir2 = conv(conv.invert(ir))
                ir == ir2
            }
        }

    @Test
    fun `output obj -- empty`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.type("TestType")),
            TestType.Builder(executionContext).build(),
            IR.Value.Object("TestType", emptyMap())
        )
    }

    @Test
    fun `output obj -- simple`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.type("TestType")),
            TestType.Builder(executionContext).id("foo").build(),
            IR.Value.Object(
                "TestType",
                mapOf("id" to IR.Value.String("foo"))
            )
        )
    }

    @Test
    fun `output obj -- nested`(): Unit =
        runBlocking {
            val o1 = assertRoundtrip(
                GRTConv(internalContext, schema.type("O1")),
                O1.Builder(executionContext)
                    .objectField(
                        O2.Builder(executionContext)
                            .intField(1)
                            .build()
                    )
                    .build(),
                IR.Value.Object(
                    "O1",
                    mapOf(
                        "objectField" to IR.Value.Object(
                            "O2",
                            mapOf("intField" to IR.Value.Number(1))
                        )
                    )
                )
            )

            assertEquals(1, o1.getObjectField()?.getIntField())
        }

    @Test
    fun `output obj -- unset non-nullable fields`(): Unit =
        runBlocking {
            // TestType.id is non-nullable and not set
            // The object can be roundtripped but the field will throw when accessed
            assertRoundtrip(
                GRTConv(internalContext, schema.type("TestType")),
                TestType.Builder(executionContext).build(),
                IR.Value.Object("TestType", emptyMap())
            ).let {
                assertThrows<ViaductTenantUsageException> {
                    it.getId()
                }
            }
        }

    @Test
    fun `output obj -- argumented field`(): Unit =
        runBlocking {
            // O2.argumentedField takes field arguments
            val o2 = assertRoundtrip(
                GRTConv(internalContext, schema.type("O2")),
                O2.Builder(executionContext).argumentedField("x").build(),
                IR.Value.Object(
                    "O2",
                    mapOf("argumentedField" to IR.Value.String("x"))
                )
            )

            assertEquals("x", o2.getArgumentedField())
        }

    @Test
    fun `output obj -- abstract-typed fields`(): Unit =
        runBlocking {
            val obj = assertRoundtrip(
                GRTConv(internalContext, schema.type("HasAbstractField")),
                HasAbstractField.Builder(executionContext)
                    .u2(Concrete.Builder(executionContext).x(1).build())
                    .build(),
                IR.Value.Object(
                    "HasAbstractField",
                    mapOf(
                        "u2" to IR.Value.Object(
                            "Concrete",
                            mapOf("x" to IR.Value.Number(1))
                        )
                    )
                )
            )
            assertEquals(1, (obj.getU2() as? Concrete)?.getX())
        }

    @Test
    fun `output obj -- arb`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (InputObjectValueWeight to 0.0) +
                // __typename field values may be inserted when converting from IR to GRT
                // backing data. This can break comparisons that check the generated IR
                // against the IR that was roundtripped through GRT.
                // To simplify testing, ensure that a __typename field is always generated on
                // the input value where possible
                (TypenameValueWeight to 1.0)

            Arb.objectIR(schema.schema, cfg).forAll { ir ->
                val conv = GRTConv(internalContext, schema.type(ir.name))
                val ir2 = conv(conv.invert(ir))
                ir == ir2
            }
        }

    @Test
    fun `output obj with selections -- handles nested selections`() {
        val conv = GRTConv(
            internalContext,
            schema.type("O1"),
            mkRawSelectionSet(
                "O1",
                """
                    obj: objectField {
                        y:id
                    }
                """.trimIndent()
            ),
        )

        assertRoundtrip(
            conv,
            O1.Builder(executionContext)
                .putWithAlias(
                    "objectField",
                    "obj",
                    O2.Builder(executionContext)
                        .putWithAlias("id", "y", GlobalIDImpl(O2.Reflection, "1"))
                        .build(),
                )
                .build(),
            IR.Value.Object(
                "O1",
                "obj" to IR.Value.Object(
                    "O2",
                    "y" to IR.Value.String(O2.Reflection.testGlobalId("1")),
                )
            )
        )
    }

    @Test
    fun `output obj with selections -- the same type can be selected multiple times with different selections`() {
        val conv = GRTConv(
            internalContext,
            schema.type("RecursiveObject"),
            mkRawSelectionSet(
                "RecursiveObject",
                "x:int, nested { y:int }"
            ),
            KeyMapping.FieldNameToSelection
        )

        assertRoundtrip(
            conv,
            RecursiveObject.Builder(executionContext)
                .int(1)
                .nested(
                    RecursiveObject.Builder(executionContext).int(2).build()
                )
                .build(),
            IR.Value.Object(
                "RecursiveObject",
                "x" to IR.Value.Number(1),
                "nested" to IR.Value.Object(
                    "RecursiveObject",
                    "y" to IR.Value.Number(2)
                )
            )
        )
    }

    @Test
    fun `output obj with selections -- a field can be selected multiple times`() {
        val conv = GRTConv(
            internalContext,
            schema.type("O2"),
            mkRawSelectionSet(
                "O2",
                "a:intField, b:intField"
            )
        )
        assertRoundtrip(
            conv,
            O2.Builder(executionContext)
                .putWithAlias("intField", "a", 1)
                .putWithAlias("intField", "b", 2)
                .build(),
            IR.Value.Object(
                "O2",
                "a" to IR.Value.Number(1),
                "b" to IR.Value.Number(2),
            )
        )
    }

    @Test
    fun `output obj key mapping -- FieldNameToFieldName`() {
        val conv = GRTConv(
            internalContext,
            schema.type("O2"),
            mkRawSelectionSet(
                "O2",
                "x:intField"
            ),
            KeyMapping.FieldNameToFieldName
        )
        val grt = O2.Builder(executionContext).intField(1).build()
        val ir = conv(grt) as IR.Value.Object
        assertEquals(setOf("intField"), ir.fields.keys)
    }

    @Test
    fun `output obj key mapping -- SelectionToSelection`() {
        val conv = GRTConv(
            internalContext,
            schema.type("O2"),
            mkRawSelectionSet(
                "O2",
                "x:intField"
            ),
            KeyMapping.SelectionToSelection
        )
        val grt = O2.Builder(executionContext)
            .putWithAlias("intField", "x", 1)
            .build()
        val ir = conv(grt) as IR.Value.Object
        assertEquals(setOf("x"), ir.fields.keys)
    }

    @Test
    fun `output obj key mapping -- FieldNameToSelection`() {
        val conv = GRTConv(
            internalContext,
            schema.type("O2"),
            mkRawSelectionSet(
                "O2",
                "x:intField"
            ),
            KeyMapping.FieldNameToSelection
        )
        val grt = O2.Builder(executionContext).intField(1).build()
        val ir = conv(grt) as IR.Value.Object
        assertEquals(setOf("x"), ir.fields.keys)
    }

    @Test
    fun `interface -- simple`() {
        // I1 is a concrete type that implements I0
        assertRoundtrip(
            GRTConv(internalContext, schema.type("I0")),
            I1.Builder(executionContext).commonField("str").build(),
            IR.Value.Object(
                "I1",
                mapOf("commonField" to IR.Value.String("str"))
            )
        )
    }

    @Test
    fun `interface with selections -- simple`() {
        val conv = GRTConv(
            internalContext,
            schema.type("I0"),
            mkRawSelectionSet("I0", "x:commonField"),
            KeyMapping.FieldNameToSelection
        )
        assertRoundtrip(
            conv,
            I1.Builder(executionContext).commonField("str").build(),
            IR.Value.Object(
                "I1",
                "x" to IR.Value.String("str")
            )
        )
    }

    @Test
    fun `union -- simple`() {
        // I1 is a member of union U1
        assertRoundtrip(
            GRTConv(internalContext, schema.type("U1")),
            I1.Builder(executionContext).commonField("str").build(),
            IR.Value.Object(
                "I1",
                mapOf("commonField" to IR.Value.String("str"))
            )
        )
    }

    @Test
    fun `union with selections -- simple`() {
        val conv = GRTConv(
            internalContext,
            schema.type("U1"),
            mkRawSelectionSet("U1", "... on I1 { x:commonField }"),
            KeyMapping.FieldNameToSelection
        )
        assertRoundtrip(
            conv,
            I1.Builder(executionContext).commonField("str").build(),
            IR.Value.Object(
                "I1",
                "x" to IR.Value.String("str")
            )
        )
    }

    @Test
    fun `enum -- simple`() {
        assertRoundtrip(
            GRTConv(internalContext, schema.type("E1")),
            E1.A,
            IR.Value.String("A")
        )
    }

    @Test
    fun `enum -- arb`(): Unit =
        runBlocking {
            val enumTypes = schema.schema.allTypesAsList.mapNotNull { it as? GraphQLEnumType }
                // filter out introspection enums, which can't be roundtripped through GRT because
                // we don't generate classes for them
                .filterNot { it.name.startsWith("__") }
                .also {
                    // sanity
                    assertTrue(it.isNotEmpty())
                }

            val typeIRPairs = arbitrary {
                val type = Arb.of(enumTypes).bind()
                val ir = Arb.ir(schema.schema, type).bind()
                type to ir
            }

            typeIRPairs.forAll { (type, ir) ->
                val conv = GRTConv(internalContext, type)
                val ir2 = conv(conv.invert(ir))
                ir == ir2
            }
        }

    private fun <From, To> assertRoundtrip(
        conv: Conv<Any?, To>,
        from: From,
        expectedTo: To
    ): From {
        val to = conv(from)
        assertEquals(expectedTo, to)
        val from2 = conv.invert(to)

        // Objects only support referential equality, check them manually
        if (from is ObjectBase) {
            from2 as ObjectBase
            assertEquals(from.javaClass, from2.javaClass)
            assertTrue(
                engineObjectsAreEquivalent(
                    from.engineObject as EngineObjectData.Sync,
                    from2.engineObject as EngineObjectData.Sync
                )
            )
        } else {
            // everything else
            assertEquals(from, from2)
        }

        @Suppress("UNCHECKED_CAST")
        return from2 as From
    }

    private fun ViaductSchema.type(name: String): GraphQLType = typeAs(name)

    private fun <T : GraphQLType> ViaductSchema.typeAs(name: String): T = schema.getTypeAs(name)!!

    private fun ViaductSchema.field(coord: Coordinate): GraphQLFieldDefinition = schema.getFieldDefinition(coord.gj)

    private fun ViaductSchema.inputField(coord: Coordinate): GraphQLInputObjectField = typeAs<GraphQLInputObjectType>(coord.first).getField(coord.second)

    private fun mkRawSelectionSet(
        selectionsType: String,
        selections: String,
        variables: Map<String, Any?> = emptyMap()
    ): RawSelectionSet =
        mkRawSelectionSet(
            SelectionsParser.parse(selectionsType, selections),
            schema,
            variables
        )
}
