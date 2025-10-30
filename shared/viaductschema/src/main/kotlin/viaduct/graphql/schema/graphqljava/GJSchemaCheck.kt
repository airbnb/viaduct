package viaduct.graphql.schema.graphqljava

import graphql.language.Node
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkBridgeSchemaInvariants
import viaduct.invariants.InvariantChecker

class GJSchemaCheck(
    private val schema: GJSchema,
    private val gjSchema: GraphQLSchema,
    private val valueConverter: ValueConverter
) {
    private val check = InvariantChecker()

    fun assertEmpty(separator: String) = check.assertEmpty(separator)

    init {
        checkBridgeSchemaInvariants(schema, check)
        check.containsExactlyElementsIn(
            gjSchema.allTypesAsList.map { it.name },
            schema.types.values.map { it.name },
            "TYPES_AGREE"
        )
        schema.types.values.checkAgreement()
        checkFieldInvariants()
        checkDirectiveInvariants()
        checkSourceLocationInvariants()
    }

    private fun Iterable<GJSchema.Def>.checkAgreement(): Unit = forEach { it.checkAgreement() }

    private fun GJSchema.Def.checkAgreement() {
        check.isEqualTo(def.name, name, "NAME_AGREEMENT")
        if (def is GraphQLDirectiveContainer) {
            check.containsExactlyElementsIn(
                (def as GraphQLDirectiveContainer).appliedDirectives.map { directive ->
                    val args =
                        if (0 < directive.arguments.size) {
                            "(${
                                directive.arguments.sortedBy { it.name }.joinToString(", ") {
                                    "${it.name}: ${valueConverter.convert(schema.toTypeExpr(it.type), it.argumentValue)}"
                                } })"
                        } else {
                            ""
                        }
                    "@${directive.name}$args"
                },
                appliedDirectives.map { it.toString() },
                "APPLIED_DIRECTIVES_AGREE"
            )
        }

        check.pushContext(this.name)
        when (this) {
            is GJSchema.FieldArg -> { }
            is GJSchema.DirectiveArg -> { }
            is GJSchema.Enum -> {
                check.containsExactlyElementsIn(
                    def.values.map { it.name },
                    values.map { it.name },
                    "ENUM_VALUES_AGREE"
                )
            }
            is GJSchema.EnumValue -> { }
            is GJSchema.Directive -> {
                args.checkAgreement()
            }
            is GJSchema.Field -> {
                args.checkAgreement()
            }
            is GJSchema.Input -> {
                check.containsExactlyElementsIn(
                    def.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
            }
            is GJSchema.Interface -> {
                check.containsExactlyElementsIn(
                    def.interfaces.map { it.name },
                    supers.map { it.name },
                    "SUPERS_AGREE"
                )
                check.containsExactlyElementsIn(
                    def.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
            }
            is GJSchema.Object -> {
                check.containsExactlyElementsIn(
                    def.interfaces.map { it.name },
                    supers.map { it.name },
                    "SUPERS_AGREE"
                )
                check.containsExactlyElementsIn(
                    def.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
                for (union in schema.types.values) if (union is GJSchema.Union) {
                    if (gjSchema.isPossibleType(
                            gjSchema.getType(union.name) as GraphQLNamedType,
                            gjSchema.getObjectType(def.name)!!
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
            is GJSchema.Scalar -> { }
            is GJSchema.Union -> {
                check.containsExactlyElementsIn(
                    def.types.map { it.name },
                    possibleObjectTypes.map { it.name },
                    "UNION_MEMBERS_AGREE"
                )
            }
            else -> throw IllegalArgumentException("Unknown type ($def).")
        }
        check.popContext()
    }

    private fun checkDefaultValue(
        actual: ViaductSchema.HasDefaultValue,
        check: InvariantChecker
    ) {
        if (!actual.hasDefault) {
            check.doesThrow<NoSuchElementException>("HAS_NO_DEFAULT") {
                actual.defaultValue
            }
        } else {
            check.doesNotThrow("HAS_DEFAULT") {
                actual.defaultValue
            }.ifNoThrow { default ->
                if (default == null) {
                    check.isTrue(actual.type.isNullable, "DEFAULT_NULLABLE")
                }
                valueConverter.javaClassFor(actual.type)?.let {
                    check.isInstanceOf(it.kotlin, default, "DEFAULT_CORRECT_TYPE")
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
            if (d !is ViaductSchema.HasExtensions<*, *>) continue
            check.withContext(d.name) {
                val expectedExts: List<Node<*>?> =
                    when (d) {
                        is GJSchema.Enum -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Input -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Interface -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Object -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Union -> listOf(d.def.definition) + d.def.extensionDefinitions
                        else -> throw IllegalArgumentException("Unknown type ($d).")
                    }
                val expectedSourceNames = expectedExts.map { it?.sourceLocation?.sourceName }.filterNotNull()
                val actualSourceNames = d.extensions.map { it.sourceLocation?.sourceName }.filterNotNull()
                check.containsExactlyElementsIn(expectedSourceNames, actualSourceNames, "SOURCE_NAMES_AGREE")
            }
        }
    }
}
