package viaduct.graphql.schema.graphqljava

import graphql.language.Node
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.invariants.FailureCollector

class GJSchemaCheck(
    viaductSchema: ViaductSchema,
    private val gjSchema: GraphQLSchema,
    private val check: FailureCollector = FailureCollector(),
) {
    private val schema: SchemaWithData

    init {
        require(viaductSchema is SchemaWithData) {
            "GJSchemaCheck can only be used with schemas from ViaductSchema.fromGraphQLSchema. " +
                "Got ${viaductSchema::class.simpleName} instead of SchemaWithData."
        }
        // Check that the schema's data fields contain graphql-java schema types (not language types)
        val sampleDef = (viaductSchema.types.values.firstOrNull() ?: viaductSchema.directives.values.firstOrNull())
            as SchemaWithData.Def?
        require(sampleDef == null || sampleDef.data is GraphQLNamedSchemaElement) {
            "GJSchemaCheck can only be used with schemas from ViaductSchema.fromGraphQLSchema. " +
                "The schema appears to be from ViaductSchema.fromTypeDefinitionRegistry (GJSchemaRaw)."
        }
        schema = viaductSchema

        checkViaductSchemaInvariants(schema, check)
        check.containsExactlyElementsIn(
            gjSchema.allTypesAsList.filterNot { it.name.startsWith("__") }.map { it.name },
            schema.types.values.map { it.name },
            "TYPES_AGREE"
        )
        schema.types.values.checkAgreement()
        checkFieldInvariants()
        checkDirectiveInvariants()
        checkSourceLocationInvariants()
    }

    fun assertEmpty(separator: String) = check.assertEmpty(separator)

    private fun Iterable<SchemaWithData.Def>.checkAgreement(): Unit = forEach { it.checkAgreement() }

    private fun SchemaWithData.Def.checkAgreement() {
        check.isEqualTo(gjDef.name, name, "NAME_AGREEMENT")
        if (gjDef is GraphQLDirectiveContainer) {
            check.containsExactlyElementsIn(
                (gjDef as GraphQLDirectiveContainer).appliedDirectives.map { appliedDirective ->
                    // Get directive definition to iterate over ALL arguments (not just explicitly provided ones)
                    val directiveDef = gjSchema.getDirective(appliedDirective.name)
                        ?: error("Directive @${appliedDirective.name} not found in schema.")
                    val argsString = if (directiveDef.arguments.isNotEmpty()) {
                        val args = directiveDef.arguments.sortedBy { it.name }.joinToString(", ") { argDef ->
                            "${argDef.name}: ${convertAppliedDirectiveArg(appliedDirective, argDef)}"
                        }
                        "($args)"
                    } else {
                        ""
                    }
                    "@${appliedDirective.name}$argsString"
                },
                appliedDirectives.map { it.toString() },
                "APPLIED_DIRECTIVES_AGREE"
            )
        }

        check.pushContext(this.name)
        when (this) {
            is SchemaWithData.FieldArg -> { }
            is SchemaWithData.DirectiveArg -> { }
            is SchemaWithData.Enum -> {
                check.containsExactlyElementsIn(
                    gjDef.values.map { it.name },
                    values.map { it.name },
                    "ENUM_VALUES_AGREE"
                )
            }
            is SchemaWithData.EnumValue -> { }
            is SchemaWithData.Directive -> {
                args.checkAgreement()
            }
            is SchemaWithData.Field -> {
                args.checkAgreement()
            }
            is SchemaWithData.Input -> {
                check.containsExactlyElementsIn(
                    gjDef.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
            }
            is SchemaWithData.Interface -> {
                check.containsExactlyElementsIn(
                    gjDef.interfaces.map { it.name },
                    supers.map { it.name },
                    "SUPERS_AGREE"
                )
                check.containsExactlyElementsIn(
                    gjDef.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
            }
            is SchemaWithData.Object -> {
                check.containsExactlyElementsIn(
                    gjDef.interfaces.map { it.name },
                    supers.map { it.name },
                    "SUPERS_AGREE"
                )
                check.containsExactlyElementsIn(
                    gjDef.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
                for (union in schema.types.values) {
                    if (union is SchemaWithData.Union) {
                        if (gjSchema.isPossibleType(
                                gjSchema.getType(union.name) as GraphQLNamedType,
                                gjSchema.getObjectType(gjDef.name)!!
                            )
                        ) {
                            check.isTrue(
                                unions.contains(union),
                                "HAS_ALL_NEEDED_UNIONS: {0} is missing.",
                                arrayOf(union.name)
                            )
                        } else {
                            check.isFalse(
                                unions.contains(union),
                                "HAS_ONLY_NEEDED_UNIONS: {0} is extra.",
                                arrayOf(union.name)
                            )
                        }
                    }
                }
            }
            is SchemaWithData.Scalar -> { }
            is SchemaWithData.Union -> {
                check.containsExactlyElementsIn(
                    gjDef.types.map { it.name },
                    possibleObjectTypes.map { it.name },
                    "UNION_MEMBERS_AGREE"
                )
            }
        }
        check.popContext()
    }

    /**
     * Convert an applied directive argument to a Value, handling missing arguments
     * by using defaults or NullValue for nullable types.
     */
    private fun convertAppliedDirectiveArg(
        appliedDirective: GraphQLAppliedDirective,
        argDef: GraphQLArgument
    ): ViaductSchema.Literal {
        val appliedArg = appliedDirective.getArgument(argDef.name)
        val type = schema.toTypeExpr(argDef.type)
        val convertedValue = when {
            appliedArg != null -> ValueConverter.convert(type, appliedArg.argumentValue)
            argDef.hasSetDefaultValue() -> ValueConverter.convert(type, argDef.argumentDefaultValue)
            else -> null
        }
        return convertedValue ?: ViaductSchema.NULL.also {
            require(type.isNullable) {
                "No value for non-nullable argument ${argDef.name} on @${appliedDirective.name}"
            }
        }
    }

    private fun checkDefaultValue(
        actual: ViaductSchema.HasDefaultValue,
        check: FailureCollector
    ) {
        if (!actual.hasDefault) {
            check.doesThrow<NoSuchElementException>("HAS_NO_DEFAULT") {
                actual.defaultValue
            }
        } else {
            check
                .doesNotThrow("HAS_DEFAULT") {
                    actual.defaultValue
                }.ifNoThrow { default ->
                    if (default is ViaductSchema.NullLiteral) {
                        check.isTrue(actual.type.isNullable, "DEFAULT_NULLABLE")
                    } else {
                        ValueConverter.javaClassFor(actual.type).let {
                            check.isInstanceOf(it.kotlin, default, "DEFAULT_CORRECT_TYPE")
                        }
                    }
                }
        }
    }

    private fun checkFieldInvariants() {
        for (def in schema.types.values) {
            if (def !is ViaductSchema.Record) continue
            check.withContext(def.name) {
                for (field in def.fields) {
                    check.withContext(field.name) {
                        checkDefaultValue(field, check)
                        for (arg in field.args) {
                            checkDefaultValue(arg, check)
                        }
                    }
                }
            }
        }
    }

    private fun checkDirectiveInvariants() {
        for (def in schema.directives) {
            check.withContext(def.key) {
                for (arg in def.value.args) {
                    checkDefaultValue(arg, check)
                }
            }
        }
    }

    private fun checkSourceLocationInvariants() {
        for (d in schema.types.values) {
            check.withContext(d.name) {
                val expectedExts: List<Node<*>?> =
                    when (d) {
                        is SchemaWithData.Enum -> listOf(d.gjDef.definition) + d.gjDef.extensionDefinitions
                        is SchemaWithData.Input -> listOf(d.gjDef.definition) + d.gjDef.extensionDefinitions
                        is SchemaWithData.Interface -> listOf(d.gjDef.definition) + d.gjDef.extensionDefinitions
                        is SchemaWithData.Object -> listOf(d.gjDef.definition) + d.gjDef.extensionDefinitions
                        is SchemaWithData.Scalar -> listOf(d.gjDef.definition) + d.gjDef.extensionDefinitions
                        is SchemaWithData.Union -> listOf(d.gjDef.definition) + d.gjDef.extensionDefinitions
                    }
                val expectedSourceNames = expectedExts.map { it?.sourceLocation?.sourceName }.filterNotNull()
                val actualSourceNames = d.extensions.map { it.sourceLocation?.sourceName }.filterNotNull()
                check.containsExactlyElementsIn(expectedSourceNames, actualSourceNames, "SOURCE_NAMES_AGREE")
            }
        }
    }
}
