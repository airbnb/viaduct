package viaduct.graphql.schema.builder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.SchemaDiff
import viaduct.utils.collections.HMap

class ViaductSchemaBuilderFilteredCopyTest {
    private val metadata = HMap.Key.of<String>("metadata")

    @Test
    fun `all-accept copy preserves schema structure and metadata`() {
        val source = sourceSchema()

        val copy =
            ViaductSchemaBuilder
                .filteredCopy(source, object : ViaductSchemaBuilderFilter {})
                .build()

        SchemaDiff(source, copy).diff().assertEmpty()
        assertEquals("Read", copy.queryTypeDef?.name)
        assertEquals("Write", copy.mutationTypeDef?.name)
        assertEquals("Watch", copy.subscriptionTypeDef?.name)

        val sourceTag = source.directives.getValue("tag")
        val copiedTag = copy.directives.getValue("tag")
        assertEquals(sourceTag.description, copiedTag.description)
        assertEquals(sourceTag.isRepeatable, copiedTag.isRepeatable)
        assertEquals(sourceTag.allowedLocations, copiedTag.allowedLocations)
        assertSame(sourceTag.holder, copiedTag.holder)
        assertSame(sourceTag.args.single().holder, copiedTag.args.single().holder)

        val sourceRead = source.types.getValue("Read") as ViaductSchema.Object
        val copiedRead = copy.types.getValue("Read") as ViaductSchema.Object
        assertEquals(sourceRead.description, copiedRead.description)
        assertSame(sourceRead.holder, copiedRead.holder)
        assertEquals(
            sourceRead.extensions.map { it.sourceLocation },
            copiedRead.extensions.map { it.sourceLocation },
        )
        assertSame(sourceRead.field("search")!!.holder, copiedRead.field("search")!!.holder)
        assertSame(
            sourceRead.field("search")!!.args.first { it.name == "keep" }.holder,
            copiedRead.field("search")!!.args.first { it.name == "keep" }.holder,
        )
    }

    @Test
    fun `filtering is hierarchical and filters applied directives at their owners`() {
        val source = sourceSchema()
        val visitedFields = mutableListOf<String>()
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterTopLevelDef(source: ViaductSchema.TopLevelDef): Boolean = source.name != "Hidden"

                override fun filterField(source: ViaductSchema.Field): Boolean {
                    check(source.containingDef.name != "Hidden") {
                        "Fields below a rejected object must not be visited"
                    }
                    visitedFields.add("${source.containingDef.name}.${source.name}")
                    return source.name != "discarded"
                }

                override fun filterArg(source: ViaductSchema.Arg): Boolean {
                    check(source.containingDef.name != "discarded") {
                        "Arguments below a rejected field must not be visited"
                    }
                    return source.name != "remove" && source.containingDef.name != "unused"
                }

                override fun filterEnumValue(source: ViaductSchema.EnumValue): Boolean = source.name != "GUEST"

                override fun filterAppliedDirective(
                    source: ViaductSchema.Def,
                    appliedDirective: ViaductSchema.AppliedDirective<*>,
                ): Boolean = appliedDirective.name != "remove"

                override fun filterExtensionAppliedDirective(
                    source: ViaductSchema.Extension<*, *>,
                    appliedDirective: ViaductSchema.AppliedDirective<*>,
                ): Boolean = appliedDirective.name != "remove"
            }

        val copy = ViaductSchemaBuilder.filteredCopy(source, filter).build()

        assertFalse("Hidden" in copy.types)
        assertTrue("Read.search" in visitedFields)
        assertTrue("Read.discarded" in visitedFields)

        val read = copy.types.getValue("Read") as ViaductSchema.Object
        assertEquals(listOf("id", "search", "extensionField"), read.fields.map { it.name })
        assertEquals(listOf("keep"), read.field("search")!!.args.map { it.name })
        assertEquals(listOf("tag"), read.appliedDirectives.map { it.name })
        assertEquals(listOf("tag"), read.field("search")!!.appliedDirectives.map { it.name })
        assertEquals(
            listOf("tag"),
            read.field("search")!!.args.single().appliedDirectives.map { it.name },
        )
        assertEquals(ViaductSchema.NULL, read.field("search")!!.args.single().defaultValue)

        val role = copy.types.getValue("Role") as ViaductSchema.Enum
        assertEquals(listOf("ADMIN", "USER"), role.values.map { it.name })
        assertEquals(listOf("tag"), role.appliedDirectives.map { it.name })
        assertEquals(listOf("tag"), role.value("ADMIN")!!.appliedDirectives.map { it.name })
        assertEquals(listOf("tag", "tag"), copy.types.getValue("Date").appliedDirectives.map { it.name })
        assertEquals(listOf("tag"), copy.types.getValue("Filter").appliedDirectives.map { it.name })
        assertEquals(listOf("tag"), copy.types.getValue("Node").appliedDirectives.map { it.name })
        assertEquals(
            listOf("BaseNode"),
            (copy.types.getValue("Node") as ViaductSchema.Interface).supers.map { it.name },
        )
        assertEquals(listOf("tag"), copy.types.getValue("Result").appliedDirectives.map { it.name })
        assertEquals(
            listOf("tag"),
            copy.directives.getValue("tag").args.single().appliedDirectives.map { it.name },
        )
        assertTrue(copy.directives.getValue("unused").args.isEmpty())
    }

    @Test
    fun `type applied directives retain their extension owner`() {
        val source = sourceSchema()
        val visitedExtensions = mutableListOf<String>()
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterAppliedDirective(
                    source: ViaductSchema.Def,
                    appliedDirective: ViaductSchema.AppliedDirective<*>,
                ): Boolean {
                    check(source !is ViaductSchema.TypeDef) {
                        "Type directives must be filtered through their extension"
                    }
                    return true
                }

                override fun filterExtensionAppliedDirective(
                    source: ViaductSchema.Extension<*, *>,
                    appliedDirective: ViaductSchema.AppliedDirective<*>,
                ): Boolean {
                    val sourceName = source.sourceLocation?.sourceName ?: source.def.name
                    visitedExtensions.add(sourceName)
                    return sourceName != "date-extension"
                }
            }

        val copy = ViaductSchemaBuilder.filteredCopy(source, filter).build()
        val date = copy.types.getValue("Date")
        val base = date.extensions.single { it.isBase }
        val extension = date.extensions.single { !it.isBase }

        assertEquals(listOf("tag", "remove"), base.appliedDirectives.map { it.name })
        assertTrue(extension.appliedDirectives.isEmpty())
        assertTrue("date-base" in visitedExtensions)
        assertTrue("date-extension" in visitedExtensions)
    }

    @Test
    fun `filtered named definitions can be replaced without changing references`() {
        val source = sourceSchema()
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterTopLevelDef(source: ViaductSchema.TopLevelDef): Boolean {
                    return source.name !in setOf("tag", "String", "Role", "Result", "Filter", "Node")
                }

                override fun filterEnumValue(source: ViaductSchema.EnumValue): Boolean {
                    check(source.containingDef.name != "Role") {
                        "Values below a rejected enum must not be visited"
                    }
                    return true
                }

                override fun filterField(source: ViaductSchema.Field): Boolean {
                    check(source.containingDef.name !in setOf("Filter", "Node")) {
                        "Fields below a rejected type must not be visited"
                    }
                    return true
                }

                override fun filterArg(source: ViaductSchema.Arg): Boolean {
                    check(source.containingDef.name != "tag") {
                        "Arguments below a rejected directive must not be visited"
                    }
                    return true
                }

                override fun filterExtension(source: ViaductSchema.Extension<*, *>): Boolean {
                    check(source.def.name !in setOf("Role", "Result", "Filter", "Node")) {
                        "Extensions below a rejected type must not be visited"
                    }
                    return true
                }
            }

        val copy =
            ViaductSchemaBuilder
                .filteredCopy(source, filter)
                .addDefinition(ScalarTypeBuilder("String"))
                .addDefinition(
                    DirectiveBuilder("tag")
                        .addArgument(ArgumentBuilder("label", TypeExprBuilder("String")))
                        .addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
                        .addLocation(ViaductSchema.Directive.Location.OBJECT)
                        .addLocation(ViaductSchema.Directive.Location.ARGUMENT_DEFINITION)
                        .addLocation(ViaductSchema.Directive.Location.ENUM_VALUE)
                ).addDefinition(
                    EnumTypeBuilder("Role")
                        .addValue(EnumValueBuilder("ADMIN"))
                        .addValue(EnumValueBuilder("USER"))
                        .addValue(EnumValueBuilder("GUEST"))
                ).addDefinition(
                    UnionTypeBuilder("Result")
                        .addMember("Read")
                        .addMember("Other")
                ).addDefinition(
                    InputObjectTypeBuilder("Filter")
                        .addField(InputFieldBuilder("term", TypeExprBuilder("String")))
                ).addDefinition(
                    InterfaceTypeBuilder("Node")
                        .addField(OutputFieldBuilder("id", TypeExprBuilder("ID", nullable = false)))
                ).build()

        val read = copy.types.getValue("Read") as ViaductSchema.Object
        assertEquals(listOf("Node"), read.supers.map { it.name })
        assertEquals("String", read.field("search")!!.type.baseTypeDef.name)
        assertEquals("tag", read.field("search")!!.appliedDirectives.first().name)
    }

    @Test
    fun `every reference and extension add operation can be filtered`() {
        val source = sourceSchema()
        val visitedFields = mutableListOf<String>()
        val visitedEnumValues = mutableListOf<String>()
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterDirectiveLocation(
                    source: ViaductSchema.Directive,
                    location: ViaductSchema.Directive.Location,
                ): Boolean = location != ViaductSchema.Directive.Location.OBJECT

                override fun filterExtension(source: ViaductSchema.Extension<*, *>): Boolean {
                    check(!source.isBase) {
                        "Base extensions must be governed by filterTopLevelDef"
                    }
                    return false
                }

                override fun filterMember(
                    source: ViaductSchema.Extension<ViaductSchema.Union, ViaductSchema.Object>,
                    member: ViaductSchema.Object,
                ): Boolean = member.name != "Read"

                override fun filterSupertype(
                    source: ViaductSchema.ExtensionWithSupers<*, *>,
                    supertype: ViaductSchema.Interface,
                ): Boolean = false

                override fun filterField(source: ViaductSchema.Field): Boolean {
                    visitedFields.add(source.name)
                    return true
                }

                override fun filterEnumValue(source: ViaductSchema.EnumValue): Boolean {
                    visitedEnumValues.add(source.name)
                    return true
                }
            }

        val copy = ViaductSchemaBuilder.filteredCopy(source, filter).build()

        assertFalse(
            ViaductSchema.Directive.Location.OBJECT in
                copy.directives.getValue("tag").allowedLocations
        )
        assertEquals(1, copy.types.getValue("Date").extensions.size)

        val role = copy.types.getValue("Role") as ViaductSchema.Enum
        assertEquals(listOf("ADMIN", "USER"), role.values.map { it.name })
        assertFalse("GUEST" in visitedEnumValues)

        val input = copy.types.getValue("Filter") as ViaductSchema.Input
        assertEquals(listOf("term"), input.fields.map { it.name })
        assertFalse("limit" in visitedFields)

        val node = copy.types.getValue("Node") as ViaductSchema.Interface
        assertTrue(node.supers.isEmpty())
        assertEquals(listOf("id"), node.fields.map { it.name })
        assertFalse("label" in visitedFields)

        val read = copy.types.getValue("Read") as ViaductSchema.Object
        assertTrue(read.supers.isEmpty())
        assertFalse("extensionField" in visitedFields)
        val copiedTag = read.field("search")!!.appliedDirectives.first { it.name == "tag" }
        assertEquals(
            ViaductSchema.StringLiteral.of("copied"),
            copiedTag.arguments.getValue("label"),
        )

        val result = copy.types.getValue("Result") as ViaductSchema.Union
        assertTrue(result.possibleObjectTypes.isEmpty())
        assertEquals(1, result.extensions.size)
    }

    @Test
    fun `filtered directive arguments can be replaced before build`() {
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterArg(source: ViaductSchema.Arg): Boolean = source.name != "label"
            }

        assertThrows<IllegalArgumentException> {
            ViaductSchemaBuilder.filteredCopy(sourceSchema(), filter).build()
        }

        val copy =
            ViaductSchemaBuilder
                .filteredCopy(sourceSchema(), filter)
                .addDefinition(
                    DirectiveBuilder("tag")
                        .addArgument(ArgumentBuilder("label", TypeExprBuilder("String")))
                        .addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
                        .addLocation(ViaductSchema.Directive.Location.OBJECT)
                        .addLocation(ViaductSchema.Directive.Location.ARGUMENT_DEFINITION)
                        .addLocation(ViaductSchema.Directive.Location.ENUM_VALUE)
                ).build()

        assertEquals("label", copy.directives.getValue("tag").args.single().name)
    }

    @Test
    fun `filtered optional roots can be removed`() {
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterTopLevelDef(source: ViaductSchema.TopLevelDef): Boolean = source.name !in setOf("Write", "Watch")
            }

        val copy =
            ViaductSchemaBuilder
                .filteredCopy(sourceSchema(), filter)
                .build()

        assertEquals("Read", copy.queryTypeDef?.name)
        assertEquals(null, copy.mutationTypeDef)
        assertEquals(null, copy.subscriptionTypeDef)
    }

    @Test
    fun `filtering a type used by multiple roots clears every matching root`() {
        val source =
            ViaductSchemaBuilder(
                queryTypeName = "Root",
                mutationTypeName = "Root",
                noStandardDefs = true,
            ).addDefinition(ObjectTypeBuilder("Root")).build()
        val filter =
            object : ViaductSchemaBuilderFilter {
                override fun filterTopLevelDef(source: ViaductSchema.TopLevelDef): Boolean = false
            }

        val copy = ViaductSchemaBuilder.filteredCopy(source, filter).build()

        assertEquals(null, copy.queryTypeDef)
        assertEquals(null, copy.mutationTypeDef)
    }

    private fun sourceSchema(): ViaductSchema =
        ViaductSchemaBuilder(
            queryTypeName = "Read",
            mutationTypeName = "Write",
            subscriptionTypeName = "Watch",
            noStandardDefs = true,
        ).addDefinition(ScalarTypeBuilder("String"))
            .addDefinition(ScalarTypeBuilder("Int"))
            .addDefinition(ScalarTypeBuilder("ID"))
            .addDefinition(
                DirectiveBuilder("tag")
                    .addArgument(
                        ArgumentBuilder("label", TypeExprBuilder("String"))
                            .addAppliedDirective(tag())
                            .addAppliedDirective(remove())
                            .description("tag label")
                            .put(metadata, "tag argument")
                    ).addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
                    .addLocation(ViaductSchema.Directive.Location.OBJECT)
                    .addLocation(ViaductSchema.Directive.Location.ARGUMENT_DEFINITION)
                    .addLocation(ViaductSchema.Directive.Location.ENUM_VALUE)
                    .repeatable(true)
                    .description("tag directive")
                    .sourceLocation(source("tag"))
                    .put(metadata, "tag")
            ).addDefinition(
                DirectiveBuilder("remove")
                    .addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
                    .addLocation(ViaductSchema.Directive.Location.OBJECT)
                    .addLocation(ViaductSchema.Directive.Location.ARGUMENT_DEFINITION)
                    .addLocation(ViaductSchema.Directive.Location.ENUM_VALUE)
            ).addDefinition(
                DirectiveBuilder("unused")
                    .addArgument(ArgumentBuilder("drop", TypeExprBuilder("String")))
                    .addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
            ).addDefinition(
                ScalarTypeBuilder("Date")
                    .addAppliedDirective(tag())
                    .addAppliedDirective(remove())
                    .description("date")
                    .sourceLocation(source("date-base"))
            ).addDefinition(
                ScalarTypeExtensionBuilder("Date")
                    .addAppliedDirective(tag())
                    .sourceLocation(source("date-extension"))
            ).addDefinition(
                EnumTypeBuilder("Role")
                    .addValue(
                        EnumValueBuilder("ADMIN")
                            .addAppliedDirective(tag())
                            .addAppliedDirective(remove())
                    ).addValue(EnumValueBuilder("USER"))
                    .addAppliedDirective(tag())
                    .addAppliedDirective(remove())
                    .description("role")
                    .sourceLocation(source("role-base"))
            ).addDefinition(
                EnumTypeExtensionBuilder("Role")
                    .addValue(EnumValueBuilder("GUEST"))
                    .sourceLocation(source("role-extension"))
            ).addDefinition(
                InputObjectTypeBuilder("Filter")
                    .addField(
                        InputFieldBuilder("term", TypeExprBuilder("String"))
                            .addAppliedDirective(tag())
                            .addAppliedDirective(remove())
                            .defaultValue(ViaductSchema.StringLiteral.of("all"))
                    ).addAppliedDirective(tag())
                    .addAppliedDirective(remove())
                    .sourceLocation(source("filter-base"))
            ).addDefinition(
                InputObjectTypeExtensionBuilder("Filter")
                    .addField(InputFieldBuilder("limit", TypeExprBuilder("Int")))
                    .sourceLocation(source("filter-extension"))
            ).addDefinition(
                InterfaceTypeBuilder("BaseNode")
                    .sourceLocation(source("base-node"))
            ).addDefinition(
                InterfaceTypeBuilder("Node")
                    .addInterface("BaseNode")
                    .addField(OutputFieldBuilder("id", TypeExprBuilder("ID", nullable = false)))
                    .addAppliedDirective(tag())
                    .addAppliedDirective(remove())
                    .sourceLocation(source("node-base"))
            ).addDefinition(
                InterfaceTypeExtensionBuilder("Node")
                    .addField(OutputFieldBuilder("label", TypeExprBuilder("String")))
                    .sourceLocation(source("node-extension"))
            ).addDefinition(
                ObjectTypeBuilder("Read")
                    .addInterface("Node")
                    .addField(OutputFieldBuilder("id", TypeExprBuilder("ID", nullable = false)))
                    .addField(
                        OutputFieldBuilder(
                            "search",
                            TypeExprBuilder("String", nullable = false)
                                .list(nullable = true)
                                .list(nullable = false),
                        ).addArgument(
                            ArgumentBuilder("keep", TypeExprBuilder("Filter"))
                                .addAppliedDirective(tag())
                                .addAppliedDirective(remove())
                                .defaultValue(ViaductSchema.NULL)
                                .put(metadata, "keep argument")
                        ).addArgument(ArgumentBuilder("remove", TypeExprBuilder("String")))
                            .addAppliedDirective(tag())
                            .addAppliedDirective(remove())
                            .description("search")
                            .put(metadata, "search")
                    ).addField(
                        OutputFieldBuilder("discarded", TypeExprBuilder("String"))
                            .addArgument(ArgumentBuilder("unvisited", TypeExprBuilder("String")))
                    ).addAppliedDirective(tag())
                    .addAppliedDirective(remove())
                    .description("read")
                    .sourceLocation(source("read-base"))
                    .put(metadata, "read")
            ).addDefinition(
                ObjectTypeExtensionBuilder("Read")
                    .addField(OutputFieldBuilder("extensionField", TypeExprBuilder("String")))
                    .sourceLocation(source("read-extension"))
            ).addDefinition(
                ObjectTypeBuilder("Write")
                    .addField(OutputFieldBuilder("write", TypeExprBuilder("String")))
            ).addDefinition(
                ObjectTypeBuilder("Watch")
                    .addField(OutputFieldBuilder("watch", TypeExprBuilder("String")))
            ).addDefinition(
                ObjectTypeBuilder("Hidden")
                    .addField(OutputFieldBuilder("unvisited", TypeExprBuilder("String")))
            ).addDefinition(ObjectTypeBuilder("Other"))
            .addDefinition(
                UnionTypeBuilder("Result")
                    .addMember("Read")
                    .addAppliedDirective(tag())
                    .addAppliedDirective(remove())
                    .sourceLocation(source("result-base"))
            ).addDefinition(
                UnionTypeExtensionBuilder("Result")
                    .addMember("Other")
                    .sourceLocation(source("result-extension"))
            ).build()

    private fun tag() =
        AppliedDirectiveBuilder("tag")
            .addArgument("label", ViaductSchema.StringLiteral.of("copied"))

    private fun remove() = AppliedDirectiveBuilder("remove")

    private fun source(name: String) = ViaductSchema.SourceLocation(name)
}
