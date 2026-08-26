package viaduct.graphql.schema.builder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

class ViaductSchemaBuilderMetadataTest {
    @Test
    fun `local metadata overrides copied values and preserves fallback values`() {
        val inheritedKey = HMap.Key.of<String>("inherited")
        val localKey = HMap.Key.of<String>("local")
        val inherited =
            HMap.Builder()
                .put(inheritedKey, "inherited value")
                .build()
        val state = BuilderElementState(ScalarTypeBuilder("Scalar"))

        state.copyHolder(inherited)
        state.put(localKey, "local value")

        val holder = state.buildHolder()
        assertTrue(inheritedKey in holder)
        assertTrue(localKey in holder)
        assertFalse(HMap.Key.of<String>("missing") in holder)
        assertEquals("inherited value", holder[inheritedKey])
        assertEquals("local value", holder[localKey])
    }

    @Test
    fun `copying metadata after a local value fails`() {
        val state = BuilderElementState(ScalarTypeBuilder("Scalar"))
        state.put(HMap.Key.of<String>("local"), "local value")

        val error =
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                state.copyHolder(HMap.singleton(null))
            }

        assertEquals("Cannot copy a holder after values have been added to ScalarTypeBuilder('Scalar')", error.message)
    }

    @Test
    fun `preserves metadata on definitions extensions and members`() {
        val metadata = HMap.Key.of<String>("metadata")
        val schema =
            ViaductSchemaBuilder()
                .addDefinition(
                    DirectiveBuilder("marker")
                        .addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
                ).addDefinition(
                    DirectiveBuilder("tag")
                        .addArgument(
                            ArgumentBuilder("argument", TypeExprBuilder("String"))
                                .addAppliedDirective(appliedMarker())
                                .defaultValue(ViaductSchema.StringLiteral.of("default"))
                                .description("tag argument")
                                .put(metadata, "tag argument")
                        ).addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
                        .repeatable(true)
                        .description("tag directive")
                        .sourceLocation(source("tag-base"))
                        .put(metadata, "tag directive")
                ).addDefinition(
                    ScalarTypeBuilder("CustomScalar")
                        .addAppliedDirective(appliedTag())
                        .description("scalar")
                        .sourceLocation(source("scalar-base"))
                        .put(metadata, "scalar")
                ).addDefinition(
                    ScalarTypeExtensionBuilder("CustomScalar")
                        .addAppliedDirective(appliedTag())
                        .sourceLocation(source("scalar-extension"))
                ).addDefinition(
                    EnumTypeBuilder("Status")
                        .addValue(
                            EnumValueBuilder("ACTIVE")
                                .addAppliedDirective(appliedTag())
                                .description("active")
                                .put(metadata, "active")
                        ).addAppliedDirective(appliedTag())
                        .description("enum")
                        .sourceLocation(source("enum-base"))
                        .put(metadata, "enum")
                ).addDefinition(
                    EnumTypeExtensionBuilder("Status")
                        .addValue(
                            EnumValueBuilder("INACTIVE")
                                .addAppliedDirective(appliedTag())
                                .description("inactive")
                                .put(metadata, "inactive")
                        ).addAppliedDirective(appliedTag())
                        .sourceLocation(source("enum-extension"))
                ).addDefinition(
                    InterfaceTypeBuilder("Node")
                        .addField(
                            OutputFieldBuilder("id", TypeExprBuilder("ID", nullable = false))
                                .addAppliedDirective(appliedTag())
                                .description("node id")
                                .put(metadata, "node id")
                        ).addAppliedDirective(appliedTag())
                        .description("interface")
                        .sourceLocation(source("interface-base"))
                        .put(metadata, "interface")
                ).addDefinition(
                    InterfaceTypeExtensionBuilder("Node")
                        .addField(OutputFieldBuilder("label", TypeExprBuilder("String")))
                        .addAppliedDirective(appliedTag())
                        .sourceLocation(source("interface-extension"))
                ).addDefinition(
                    ObjectTypeBuilder("Query")
                        .addInterface("Node")
                        .addField(
                            OutputFieldBuilder("id", TypeExprBuilder("ID", nullable = false))
                                .addArgument(
                                    ArgumentBuilder("format", TypeExprBuilder("String"))
                                        .addAppliedDirective(appliedMarker())
                                        .description("id format")
                                        .put(metadata, "id format")
                                ).addAppliedDirective(appliedTag())
                                .description("query id")
                                .put(metadata, "query id")
                        ).addAppliedDirective(appliedTag())
                        .description("object")
                        .sourceLocation(source("object-base"))
                        .put(metadata, "object")
                ).addDefinition(
                    ObjectTypeExtensionBuilder("Query")
                        .addField(OutputFieldBuilder("label", TypeExprBuilder("String")))
                        .addAppliedDirective(appliedTag())
                        .sourceLocation(source("object-extension"))
                ).addDefinition(ObjectTypeBuilder("Other"))
                .addDefinition(
                    UnionTypeBuilder("Result")
                        .addMember("Query")
                        .addAppliedDirective(appliedTag())
                        .description("union")
                        .sourceLocation(source("union-base"))
                        .put(metadata, "union")
                ).addDefinition(
                    UnionTypeExtensionBuilder("Result")
                        .addMember("Other")
                        .addAppliedDirective(appliedTag())
                        .sourceLocation(source("union-extension"))
                ).addDefinition(
                    InputObjectTypeBuilder("Filter")
                        .addField(
                            InputFieldBuilder("term", TypeExprBuilder("String"))
                                .addAppliedDirective(appliedTag())
                                .defaultValue(ViaductSchema.StringLiteral.of("all"))
                                .description("filter term")
                                .put(metadata, "filter term")
                        ).addAppliedDirective(appliedTag())
                        .description("input")
                        .sourceLocation(source("input-base"))
                        .put(metadata, "input")
                ).addDefinition(
                    InputObjectTypeExtensionBuilder("Filter")
                        .addField(InputFieldBuilder("limit", TypeExprBuilder("Int")))
                        .addAppliedDirective(appliedTag())
                        .sourceLocation(source("input-extension"))
                ).build()

        val tag = schema.directives.getValue("tag")
        assertTrue(tag.isRepeatable)
        assertEquals(setOf(ViaductSchema.Directive.Location.FIELD_DEFINITION), tag.allowedLocations)
        assertEquals("tag directive", tag.description)
        assertEquals(source("tag-base"), tag.sourceLocation)
        assertEquals("tag directive", tag.holder[metadata])

        val tagArgument = tag.args.single()
        assertEquals("tag argument", tagArgument.description)
        assertEquals("tag argument", tagArgument.holder[metadata])
        assertTrue(tagArgument.hasDefault)
        assertEquals(ViaductSchema.StringLiteral.of("default"), tagArgument.defaultValue)
        assertEquals("marker", tagArgument.appliedDirectives.single().name)

        assertTypeMetadata(schema, "CustomScalar", "scalar", "scalar", metadata)
        assertTypeMetadata(schema, "Status", "enum", "enum", metadata)
        assertTypeMetadata(schema, "Node", "interface", "interface", metadata)
        assertTypeMetadata(schema, "Query", "object", "object", metadata)
        assertTypeMetadata(schema, "Result", "union", "union", metadata)
        assertTypeMetadata(schema, "Filter", "input", "input", metadata)

        val status = schema.types.getValue("Status") as ViaductSchema.Enum
        assertMemberMetadata(status.value("ACTIVE")!!, "active", "active", source("enum-base"), metadata)
        assertMemberMetadata(status.value("INACTIVE")!!, "inactive", "inactive", source("enum-extension"), metadata)

        val node = schema.types.getValue("Node") as ViaductSchema.Interface
        assertMemberMetadata(node.field("id")!!, "node id", "node id", source("interface-base"), metadata)

        val query = schema.queryTypeDef!!
        val id = query.field("id")!!
        assertMemberMetadata(id, "query id", "query id", source("object-base"), metadata)
        val format = id.args.single()
        assertEquals("id format", format.description)
        assertEquals("id format", format.holder[metadata])
        assertEquals("marker", format.appliedDirectives.single().name)

        val filter = schema.types.getValue("Filter") as ViaductSchema.Input
        val term = filter.field("term")!!
        assertMemberMetadata(term, "filter term", "filter term", source("input-base"), metadata)
        assertTrue(term.hasDefault)
        assertEquals(ViaductSchema.StringLiteral.of("all"), term.defaultValue)
    }

    @Test
    fun `nullable metadata setters can clear earlier values`() {
        val scalar =
            ScalarTypeBuilder("Scalar")
                .description("stale")
                .description(null)
                .sourceLocation(source("stale"))
                .sourceLocation(null)
        val schema =
            ViaductSchemaBuilder(
                queryTypeName = null,
                noStandardDefs = true,
            ).addDefinition(scalar)
                .build()

        assertEquals(null, schema.types.getValue("Scalar").description)
        assertEquals(null, schema.types.getValue("Scalar").sourceLocation)
    }

    private fun assertTypeMetadata(
        schema: ViaductSchema,
        name: String,
        description: String,
        holderValue: String,
        metadata: HMap.Key<String>,
    ) {
        val type = schema.types.getValue(name)
        assertEquals(description, type.description)
        assertEquals(holderValue, type.holder[metadata])
        assertEquals(source("$description-base"), type.sourceLocation)

        val extensions = type.extensions.toList()
        assertEquals(2, extensions.size)
        assertTrue(extensions[0].isBase)
        assertEquals(source("$description-base"), extensions[0].sourceLocation)
        assertEquals("tag", extensions[0].appliedDirectives.single().name)
        assertFalse(extensions[1].isBase)
        assertEquals(source("$description-extension"), extensions[1].sourceLocation)
        assertEquals("tag", extensions[1].appliedDirectives.single().name)
    }

    private fun assertMemberMetadata(
        def: ViaductSchema.Def,
        description: String,
        holderValue: String,
        sourceLocation: ViaductSchema.SourceLocation,
        metadata: HMap.Key<String>,
    ) {
        assertEquals(description, def.description)
        assertEquals(holderValue, def.holder[metadata])
        assertEquals(sourceLocation, def.sourceLocation)
        assertEquals("tag", def.appliedDirectives.single().name)
    }

    private fun appliedMarker() = AppliedDirectiveBuilder("marker")

    private fun appliedTag() = AppliedDirectiveBuilder("tag")

    private fun source(name: String) = ViaductSchema.SourceLocation(name)
}
