package viaduct.graphql.schema.builder

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema

class ViaductSchemaBuilderErrorTest {
    @Test
    fun `schema builder cannot be reused after build`() {
        val builder = builder(ScalarTypeBuilder("Scalar"))
        builder.build()

        val buildError = assertThrows<IllegalStateException> { builder.build() }
        assertEquals("This ViaductSchemaBuilder has already been built", buildError.message)

        val addError = assertThrows<IllegalStateException> {
            builder.addDefinition(ScalarTypeBuilder("Another"))
        }
        assertEquals("This ViaductSchemaBuilder has already been built", addError.message)
    }

    @Test
    fun `duplicate top-level definitions use the last definition`() {
        val schema =
            builder(
                ScalarTypeBuilder("Scalar"),
                ScalarTypeBuilder("Scalar").description("last"),
                DirectiveBuilder("directive").addLocation(ViaductSchema.Directive.Location.OBJECT),
                DirectiveBuilder("directive")
                    .description("last")
                    .addLocation(ViaductSchema.Directive.Location.OBJECT),
            ).build()

        assertEquals("last", schema.types.getValue("Scalar").description)
        assertEquals("last", schema.directives.getValue("directive").description)
    }

    @Test
    fun `duplicate directive definition arguments use the last argument`() {
        val schema =
            builder(
                ScalarTypeBuilder("String"),
                DirectiveBuilder("directive")
                    .addArgument(ArgumentBuilder("argument", TypeExprBuilder("String")))
                    .addArgument(
                        ArgumentBuilder("argument", TypeExprBuilder("String"))
                            .description("last")
                    )
                    .addLocation(ViaductSchema.Directive.Location.OBJECT),
            ).build()

        assertEquals("last", schema.directives.getValue("directive").args.single().description)
    }

    @Test
    fun `duplicate applied directive arguments use the last argument`() {
        val applied =
            AppliedDirectiveBuilder("directive")
                .addArgument("argument", ViaductSchema.StringLiteral.of("first"))
                .addArgument("argument", ViaductSchema.StringLiteral.of("second"))

        val schema =
            builder(
                ScalarTypeBuilder("String"),
                DirectiveBuilder("directive")
                    .addArgument(ArgumentBuilder("argument", TypeExprBuilder("String")))
                    .addLocation(ViaductSchema.Directive.Location.OBJECT),
                ScalarTypeBuilder("Scalar").addAppliedDirective(applied),
            ).build()
        assertEquals(
            ViaductSchema.StringLiteral.of("second"),
            schema.types.getValue("Scalar").appliedDirectives.single().arguments.getValue("argument"),
        )
    }

    @Test
    fun `contained builders cannot be attached twice`() {
        val argument = ArgumentBuilder("argument", TypeExprBuilder("String"))
        OutputFieldBuilder("first", TypeExprBuilder("String")).addArgument(argument)
        val argumentError = assertThrows<IllegalStateException> {
            OutputFieldBuilder("second", TypeExprBuilder("String")).addArgument(argument)
        }
        assertEquals(
            "ArgumentBuilder('argument') has already been added to OutputFieldBuilder('first')",
            argumentError.message,
        )

        val outputField = OutputFieldBuilder("field", TypeExprBuilder("String"))
        ObjectTypeBuilder("First").addField(outputField)
        val outputFieldError = assertThrows<IllegalStateException> {
            ObjectTypeBuilder("Second").addField(outputField)
        }
        assertEquals(
            "OutputFieldBuilder('field') has already been added to ObjectTypeBuilder('First')",
            outputFieldError.message,
        )

        val inputField = InputFieldBuilder("field", TypeExprBuilder("String"))
        InputObjectTypeBuilder("First").addField(inputField)
        val inputFieldError = assertThrows<IllegalStateException> {
            InputObjectTypeBuilder("Second").addField(inputField)
        }
        assertEquals(
            "InputFieldBuilder('field') has already been added to InputObjectTypeBuilder('First')",
            inputFieldError.message,
        )
    }

    @Test
    fun `type expressions must refer to defined types`() {
        assertAll(
            {
                assertBuildError(
                    "Type 'Missing' is not defined",
                    builder(
                        DirectiveBuilder("directive")
                            .addArgument(ArgumentBuilder("argument", TypeExprBuilder("Missing")))
                    ),
                )
            },
            {
                assertBuildError(
                    "Type 'Missing' is not defined",
                    builder(
                        ObjectTypeBuilder("Object")
                            .addField(OutputFieldBuilder("field", TypeExprBuilder("Missing")))
                    ),
                )
            },
            {
                assertBuildError(
                    "Type 'Missing' is not defined",
                    builder(
                        ObjectTypeBuilder("Object")
                            .addField(
                                OutputFieldBuilder("field", TypeExprBuilder("Object"))
                                    .addArgument(ArgumentBuilder("argument", TypeExprBuilder("Missing")))
                            )
                    ),
                )
            },
            {
                assertBuildError(
                    "Type 'Missing' is not defined",
                    builder(
                        InputObjectTypeBuilder("Input")
                            .addField(InputFieldBuilder("field", TypeExprBuilder("Missing")))
                    ),
                )
            },
            {
                assertBuildError(
                    "Type 'Missing' is not defined",
                    builder(
                        ObjectTypeBuilder("Object"),
                        ObjectTypeExtensionBuilder("Object")
                            .addField(OutputFieldBuilder("field", TypeExprBuilder("Missing"))),
                    ),
                )
            },
        )
    }

    @Test
    fun `operation roots must refer to defined object types`() {
        assertAll(
            {
                assertBuildError(
                    "The query root type 'Root' is not defined",
                    ViaductSchemaBuilder(
                        queryTypeName = "Root",
                        noStandardDefs = true,
                    ),
                )
            },
            {
                assertBuildError(
                    "The mutation root type 'Root' is not defined",
                    ViaductSchemaBuilder(
                        queryTypeName = null,
                        mutationTypeName = "Root",
                        noStandardDefs = true,
                    ),
                )
            },
            {
                assertBuildError(
                    "The subscription root type 'Root' is not defined",
                    ViaductSchemaBuilder(
                        queryTypeName = null,
                        subscriptionTypeName = "Root",
                        noStandardDefs = true,
                    ),
                )
            },
            {
                assertBuildError(
                    "The query root type 'Root' is not an object type",
                    ViaductSchemaBuilder(
                        queryTypeName = "Root",
                        noStandardDefs = true,
                    )
                        .addDefinition(ScalarTypeBuilder("Root")),
                )
            },
        )
    }

    @Test
    fun `extensions must refer to base definitions of the same kind`() {
        assertAll(
            {
                assertMissingExtensionBase(ScalarTypeExtensionBuilder("Missing"))
            },
            {
                assertMissingExtensionBase(EnumTypeExtensionBuilder("Missing"))
            },
            {
                assertMissingExtensionBase(UnionTypeExtensionBuilder("Missing"))
            },
            {
                assertMissingExtensionBase(InterfaceTypeExtensionBuilder("Missing"))
            },
            {
                assertMissingExtensionBase(ObjectTypeExtensionBuilder("Missing"))
            },
            {
                assertMissingExtensionBase(InputObjectTypeExtensionBuilder("Missing"))
            },
            {
                assertBuildError(
                    "ObjectTypeExtensionBuilder('Type') does not extend ScalarTypeBuilder",
                    builder(
                        ScalarTypeBuilder("Type"),
                        ObjectTypeExtensionBuilder("Type"),
                    ),
                )
            },
        )
    }

    @Test
    fun `union members must refer to object types`() {
        assertAll(
            {
                assertBuildError(
                    "Union Choice member 'Missing' is not defined",
                    builder(UnionTypeBuilder("Choice").addMember("Missing")),
                )
            },
            {
                assertBuildError(
                    "Union Choice member 'Missing' is not defined",
                    builder(
                        UnionTypeBuilder("Choice"),
                        UnionTypeExtensionBuilder("Choice").addMember("Missing"),
                    ),
                )
            },
            {
                assertBuildError(
                    "Union Choice member 'NotObject' has type Scalar, expected Object",
                    builder(
                        ScalarTypeBuilder("NotObject"),
                        UnionTypeBuilder("Choice").addMember("NotObject"),
                    ),
                )
            },
        )
    }

    @Test
    fun `implemented types must refer to interfaces`() {
        assertAll(
            {
                assertBuildError(
                    "Implemented type 'Missing' is not defined",
                    builder(InterfaceTypeBuilder("Interface").addInterface("Missing")),
                )
            },
            {
                assertBuildError(
                    "Implemented type 'Missing' is not defined",
                    builder(ObjectTypeBuilder("Object").addInterface("Missing")),
                )
            },
            {
                assertBuildError(
                    "Implemented type 'Missing' is not defined",
                    builder(
                        InterfaceTypeBuilder("Interface"),
                        InterfaceTypeExtensionBuilder("Interface").addInterface("Missing"),
                    ),
                )
            },
            {
                assertBuildError(
                    "Implemented type 'Missing' is not defined",
                    builder(
                        ObjectTypeBuilder("Object"),
                        ObjectTypeExtensionBuilder("Object").addInterface("Missing"),
                    ),
                )
            },
            {
                assertBuildError(
                    "Implemented type 'NotInterface' has type Scalar, expected Interface",
                    builder(
                        ScalarTypeBuilder("NotInterface"),
                        ObjectTypeBuilder("Object").addInterface("NotInterface"),
                    ),
                )
            },
        )
    }

    @Test
    fun `applied directives and their arguments must refer to definitions`() {
        assertAll(
            {
                assertBuildError(
                    "Directive @missing is not defined",
                    builder(
                        ScalarTypeBuilder("Scalar")
                            .addAppliedDirective(AppliedDirectiveBuilder("missing"))
                    ),
                )
            },
            {
                assertBuildError(
                    "Directive @directive has no argument(s) [missing]",
                    builder(
                        DirectiveBuilder("directive"),
                        ScalarTypeBuilder("Scalar")
                            .addAppliedDirective(
                                AppliedDirectiveBuilder("directive")
                                    .addArgument("missing", ViaductSchema.NULL)
                            ),
                    ),
                )
            },
        )
    }

    private fun assertMissingExtensionBase(extension: DefinitionBuilder) {
        assertBuildError(
            "Type extension 'Missing' has no base definition",
            builder(extension),
        )
    }

    private fun builder(vararg definitions: DefinitionBuilder): ViaductSchemaBuilder =
        definitions.fold(
            ViaductSchemaBuilder(
                queryTypeName = null,
                noStandardDefs = true,
            )
        ) { builder, definition ->
            builder.addDefinition(definition)
        }

    private fun assertBuildError(
        expectedMessage: String,
        builder: ViaductSchemaBuilder,
    ) {
        val error = assertThrows<IllegalArgumentException> { builder.build() }
        assertEquals(expectedMessage, error.message)
    }
}
