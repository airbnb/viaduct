package viaduct.graphql.schema.builder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.unparseWrappers
import viaduct.utils.collections.HMap

class ViaductSchemaBuilderTest {
    @Test
    fun `builds definitions extensions and derived relationships`() {
        val privateKey = HMap.Key.of<String>("private")
        val idType = TypeExprBuilder("String", nullable = false)
        val userListType = TypeExprBuilder("User", nullable = false).list(nullable = false)

        val enumExtension =
            EnumTypeExtensionBuilder("Role")
                .addValue(EnumValueBuilder("ADMIN"))
        val inputExtension =
            InputObjectTypeExtensionBuilder("Filter")
                .addField(InputFieldBuilder("exact", TypeExprBuilder("String")))
        val interfaceExtension =
            InterfaceTypeExtensionBuilder("Node")
                .addField(OutputFieldBuilder("label", TypeExprBuilder("String")))
        val objectExtension =
            ObjectTypeExtensionBuilder("User")
                .addField(OutputFieldBuilder("label", TypeExprBuilder("String")))
        val unionExtension =
            UnionTypeExtensionBuilder("SearchResult")
                .addMember("Query")
        val scalarExtension = ScalarTypeExtensionBuilder("String")

        val role =
            EnumTypeBuilder("Role")
                .addValue(EnumValueBuilder("USER").put(privateKey, "enum value"))
        val filter =
            InputObjectTypeBuilder("Filter")
                .addField(InputFieldBuilder("term", TypeExprBuilder("String")))
        val node =
            InterfaceTypeBuilder("Node")
                .addField(OutputFieldBuilder("id", idType))
        val user =
            ObjectTypeBuilder("User")
                .addInterface("Node")
                .addField(OutputFieldBuilder("id", idType).put(privateKey, "field"))
                .put(privateKey, "type")
        val query =
            ObjectTypeBuilder("Query")
                .addField(
                    OutputFieldBuilder("users", userListType)
                        .addArgument(ArgumentBuilder("filter", TypeExprBuilder("Filter")))
                )
        val searchResult =
            UnionTypeBuilder("SearchResult")
                .addMember("User")

        val schema =
            ViaductSchemaBuilder(noStandardDefs = true)
                .addDefinition(enumExtension)
                .addDefinition(inputExtension)
                .addDefinition(interfaceExtension)
                .addDefinition(objectExtension)
                .addDefinition(unionExtension)
                .addDefinition(scalarExtension)
                .addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(role)
                .addDefinition(filter)
                .addDefinition(node)
                .addDefinition(user)
                .addDefinition(query)
                .addDefinition(searchResult)
                .build()

        assertEquals("Query", schema.queryTypeDef?.name)
        assertEquals(listOf("USER", "ADMIN"), (schema.types.getValue("Role") as ViaductSchema.Enum).values.map { it.name })
        assertEquals(listOf("term", "exact"), (schema.types.getValue("Filter") as ViaductSchema.Input).fields.map { it.name })
        assertEquals(2, (schema.types.getValue("String") as ViaductSchema.Scalar).extensions.size)

        val nodeDef = schema.types.getValue("Node") as ViaductSchema.Interface
        val userDef = schema.types.getValue("User") as ViaductSchema.Object
        val searchDef = schema.types.getValue("SearchResult") as ViaductSchema.Union
        assertEquals(setOf(userDef), nodeDef.possibleObjectTypes)
        assertEquals(listOf(searchDef), userDef.unions)
        assertTrue(userDef.field("id")!!.isOverride)
        assertEquals("type", userDef.holder[privateKey])
        assertEquals("field", userDef.field("id")!!.holder[privateKey])
        assertEquals("enum value", (schema.types.getValue("Role") as ViaductSchema.Enum).value("USER")!!.holder[privateKey])

        val usersType = schema.queryTypeDef!!.field("users")!!.type
        assertEquals("User", usersType.baseTypeDef.name)
        assertEquals(1, usersType.listDepth)
        assertFalse(usersType.nullableAtDepth(0))
        assertFalse(usersType.nullableAtDepth(1))
    }

    @Test
    fun `type expression list wrappers are immutable and reusable`() {
        val base = TypeExprBuilder("String", nullable = false)
        val nullableList = base.list()
        val nested = nullableList.list(nullable = false)

        assertEquals("String", nested.baseTypeName)
        assertFalse(nested.baseTypeNullable)

        val query =
            ObjectTypeBuilder("Query")
                .addField(OutputFieldBuilder("base", base))
                .addField(OutputFieldBuilder("list", nullableList))
                .addField(OutputFieldBuilder("nested", nested))
        val schema =
            ViaductSchemaBuilder(noStandardDefs = true)
                .addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(query)
                .build()

        assertEquals("!", schema.queryTypeDef!!.field("base")!!.type.unparseWrappers())
        assertEquals("?!", schema.queryTypeDef!!.field("list")!!.type.unparseWrappers())
        assertEquals("!?!", schema.queryTypeDef!!.field("nested")!!.type.unparseWrappers())
    }

    @Test
    fun `resolves self recursive and mutually recursive references`() {
        val schema =
            ViaductSchemaBuilder(
                queryTypeName = null,
                noStandardDefs = true,
            ).addDefinition(
                ObjectTypeBuilder("Node")
                    .addField(OutputFieldBuilder("next", TypeExprBuilder("Node")))
            ).addDefinition(
                ObjectTypeBuilder("Left")
                    .addField(OutputFieldBuilder("right", TypeExprBuilder("Right")))
            ).addDefinition(
                ObjectTypeBuilder("Right")
                    .addField(OutputFieldBuilder("left", TypeExprBuilder("Left")))
            ).build()

        assertEquals(
            "Node",
            (schema.types.getValue("Node") as ViaductSchema.Object)
                .field("next")!!.type.baseTypeDef.name
        )
        assertEquals(
            "Right",
            (schema.types.getValue("Left") as ViaductSchema.Object)
                .field("right")!!.type.baseTypeDef.name
        )
        assertEquals(
            "Left",
            (schema.types.getValue("Right") as ViaductSchema.Object)
                .field("left")!!.type.baseTypeDef.name
        )
    }

    @Test
    fun `resolves nonstandard operation roots`() {
        val schema =
            ViaductSchemaBuilder(
                queryTypeName = "Read",
                mutationTypeName = "Write",
                subscriptionTypeName = "Watch",
                noStandardDefs = true,
            ).addDefinition(ObjectTypeBuilder("Read"))
                .addDefinition(ObjectTypeBuilder("Write"))
                .addDefinition(ObjectTypeBuilder("Watch"))
                .build()

        assertEquals("Read", schema.queryTypeDef!!.name)
        assertEquals("Write", schema.mutationTypeDef!!.name)
        assertEquals("Watch", schema.subscriptionTypeDef!!.name)
    }

    @Test
    fun `resolves interface and union memberships introduced by extensions`() {
        val schema =
            ViaductSchemaBuilder(
                queryTypeName = null,
                noStandardDefs = true,
            ).addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(
                    InterfaceTypeBuilder("Node")
                        .addField(OutputFieldBuilder("id", TypeExprBuilder("String")))
                ).addDefinition(ObjectTypeBuilder("User"))
                .addDefinition(
                    ObjectTypeExtensionBuilder("User")
                        .addInterface("Node")
                ).addDefinition(
                    UnionTypeBuilder("SearchResult")
                ).addDefinition(
                    UnionTypeExtensionBuilder("SearchResult")
                        .addMember("User")
                ).build()

        val user = schema.types.getValue("User") as ViaductSchema.Object
        val searchResult = schema.types.getValue("SearchResult") as ViaductSchema.Union
        assertEquals(listOf("Node"), user.supers.map { it.name })
        assertEquals(listOf("User"), searchResult.extensions.flatMap { it.members }.map { it.name })
    }

    @Test
    fun `duplicate contained additions use the last value`() {
        val schema =
            ViaductSchemaBuilder(
                queryTypeName = null,
                noStandardDefs = true,
            ).addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(
                    InterfaceTypeBuilder("Node")
                        .addField(OutputFieldBuilder("id", TypeExprBuilder("String")))
                ).addDefinition(
                    ObjectTypeBuilder("User")
                        .addInterface("Node")
                        .addInterface("Node")
                        .addField(
                            OutputFieldBuilder("name", TypeExprBuilder("String"))
                                .description("first")
                                .addArgument(
                                    ArgumentBuilder("format", TypeExprBuilder("String"))
                                        .description("first")
                                )
                        ).addField(
                            OutputFieldBuilder("name", TypeExprBuilder("String"))
                                .description("last")
                                .addArgument(
                                    ArgumentBuilder("format", TypeExprBuilder("String"))
                                        .description("last")
                                )
                        )
                ).addDefinition(
                    ObjectTypeExtensionBuilder("User")
                        .addField(
                            OutputFieldBuilder("name", TypeExprBuilder("String"))
                                .description("extension-last")
                                .addArgument(
                                    ArgumentBuilder("format", TypeExprBuilder("String"))
                                        .description("extension-last")
                                )
                        )
                ).addDefinition(
                    EnumTypeBuilder("Role")
                        .addValue(EnumValueBuilder("USER").description("first"))
                        .addValue(EnumValueBuilder("USER").description("last"))
                ).addDefinition(
                    UnionTypeBuilder("Result")
                        .addMember("User")
                        .addMember("User")
                ).build()

        val user = schema.types.getValue("User") as ViaductSchema.Object
        assertEquals(listOf("Node"), user.supers.map { it.name })
        assertEquals("extension-last", user.field("name")!!.description)
        assertEquals("extension-last", user.field("name")!!.args.single().description)
        assertEquals(
            "last",
            (schema.types.getValue("Role") as ViaductSchema.Enum).value("USER")!!.description,
        )
        assertEquals(
            listOf("User"),
            (schema.types.getValue("Result") as ViaductSchema.Union).extensions
                .flatMap { it.members }
                .map { it.name },
        )
    }

    @Test
    fun `exposes configured operation roots and standard definition policy`() {
        val builder =
            ViaductSchemaBuilder(
                queryTypeName = "Read",
                mutationTypeName = "Write",
                subscriptionTypeName = "Watch",
                noStandardDefs = true,
            )

        assertEquals("Read", builder.queryTypeName)
        assertEquals("Write", builder.mutationTypeName)
        assertEquals("Watch", builder.subscriptionTypeName)
        assertTrue(builder.noStandardDefs)
    }

    @Test
    fun `applied directive arguments are made dense`() {
        val directive =
            DirectiveBuilder("tag")
                .addArgument(ArgumentBuilder("required", TypeExprBuilder("String", nullable = false)))
                .addArgument(ArgumentBuilder("nullable", TypeExprBuilder("String")))
                .addArgument(
                    ArgumentBuilder("defaulted", TypeExprBuilder("String", nullable = false))
                        .defaultValue(ViaductSchema.StringLiteral.of("default"))
                ).addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
        val applied =
            AppliedDirectiveBuilder("tag")
                .addArgument("required", ViaductSchema.StringLiteral.of("provided"))
        val query =
            ObjectTypeBuilder("Query")
                .addField(
                    OutputFieldBuilder("value", TypeExprBuilder("String"))
                        .addAppliedDirective(applied)
                )

        val schema =
            ViaductSchemaBuilder(noStandardDefs = true)
                .addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(directive)
                .addDefinition(query)
                .build()

        val arguments = schema.queryTypeDef!!.field("value")!!.appliedDirectives.single().arguments
        assertEquals(ViaductSchema.StringLiteral.of("provided"), arguments["required"])
        assertEquals(ViaductSchema.NULL, arguments["nullable"])
        assertEquals(ViaductSchema.StringLiteral.of("default"), arguments["defaulted"])
    }

    @Test
    fun `defaults are absent until explicitly supplied`() {
        val query =
            ObjectTypeBuilder("Query")
                .addField(
                    OutputFieldBuilder("value", TypeExprBuilder("String"))
                        .addArgument(ArgumentBuilder("absent", TypeExprBuilder("String")))
                        .addArgument(
                            ArgumentBuilder("explicitNull", TypeExprBuilder("String"))
                                .defaultValue(ViaductSchema.NULL)
                        )
                )
        val input =
            InputObjectTypeBuilder("Input")
                .addField(InputFieldBuilder("absent", TypeExprBuilder("String")))
                .addField(
                    InputFieldBuilder("explicitNull", TypeExprBuilder("String"))
                        .defaultValue(ViaductSchema.NULL)
                )
        val schema =
            ViaductSchemaBuilder(noStandardDefs = true)
                .addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(query)
                .addDefinition(input)
                .build()

        val args = schema.queryTypeDef!!.field("value")!!.args.associateBy { it.name }
        assertFalse(args.getValue("absent").hasDefault)
        assertTrue(args.getValue("explicitNull").hasDefault)
        assertSame(ViaductSchema.NULL, args.getValue("explicitNull").defaultValue)

        val fields = (schema.types.getValue("Input") as ViaductSchema.Input).fields.associateBy { it.name }
        assertFalse(fields.getValue("absent").hasDefault)
        assertTrue(fields.getValue("explicitNull").hasDefault)
        assertSame(ViaductSchema.NULL, fields.getValue("explicitNull").defaultValue)
    }

    @Test
    fun `standard definitions are included unless disabled`() {
        val standard = ViaductSchemaBuilder(queryTypeName = null).build()
        val minimal =
            ViaductSchemaBuilder(
                queryTypeName = null,
                noStandardDefs = true,
            ).build()

        assertEquals(setOf("Int", "Float", "String", "Boolean", "ID"), standard.types.keys)
        assertEquals(setOf("include", "skip", "deprecated", "specifiedBy", "oneOf"), standard.directives.keys)
        assertTrue(minimal.types.isEmpty())
        assertTrue(minimal.directives.isEmpty())
        assertNull(minimal.queryTypeDef)
    }

    @Test
    fun `mutable builders have one owner`() {
        val value = EnumValueBuilder("VALUE")
        EnumTypeBuilder("First").addValue(value)
        assertThrows<IllegalStateException> {
            EnumTypeExtensionBuilder("Second").addValue(value)
        }

        val definition = ScalarTypeBuilder("Scalar")
        ViaductSchemaBuilder(queryTypeName = null, noStandardDefs = true).addDefinition(definition)
        assertThrows<IllegalStateException> {
            ViaductSchemaBuilder(queryTypeName = null, noStandardDefs = true).addDefinition(definition)
        }

        val directive = AppliedDirectiveBuilder("tag")
        OutputFieldBuilder("first", TypeExprBuilder("String")).addAppliedDirective(directive)
        assertThrows<IllegalStateException> {
            OutputFieldBuilder("second", TypeExprBuilder("String")).addAppliedDirective(directive)
        }
    }

    @Test
    fun `missing required directive argument fails build`() {
        val directive =
            DirectiveBuilder("tag")
                .addArgument(ArgumentBuilder("required", TypeExprBuilder("String", nullable = false)))
                .addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
        val query =
            ObjectTypeBuilder("Query")
                .addField(
                    OutputFieldBuilder("value", TypeExprBuilder("String"))
                        .addAppliedDirective(AppliedDirectiveBuilder("tag"))
                )
        val builder =
            ViaductSchemaBuilder(noStandardDefs = true)
                .addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(directive)
                .addDefinition(query)

        val error = assertThrows<IllegalStateException> { builder.build() }
        assertEquals("No value for required argument 'required' of directive @tag", error.message)
    }
}
