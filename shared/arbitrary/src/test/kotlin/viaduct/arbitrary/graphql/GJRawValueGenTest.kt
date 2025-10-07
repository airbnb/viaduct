@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.Scalars
import graphql.language.NullValue
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLTypeReference
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.set
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.checkInvariants
import viaduct.arbitrary.graphql.BridgeGJToRaw.nullish
import viaduct.mapping.graphql.RawENull
import viaduct.mapping.graphql.RawEnum
import viaduct.mapping.graphql.RawINull
import viaduct.mapping.graphql.RawInput
import viaduct.mapping.graphql.RawList
import viaduct.mapping.graphql.RawScalar
import viaduct.mapping.graphql.RawValue

class GJRawValueGenTest : KotestPropertyBase() {
    private inline fun <reified T> mkType(
        typeName: String,
        sdl: String
    ): T =
        buildString {
            append(sdl)
            append("\n")
            append(
                """
                    type Query { placeholder: Int }
                    schema { query: Query }
                """.trimIndent()
            )
        }.let { fullSdl ->
            SchemaParser().parse(fullSdl).let { reg ->
                UnExecutableSchemaGenerator.makeUnExecutableSchema(reg).getTypeAs(typeName)
            }
        }

    private fun GraphQLInputType.nonNull(): GraphQLInputType =
        if (this is GraphQLNonNull) {
            this
        } else {
            GraphQLNonNull.nonNull(this)
        }

    private fun GraphQLInputType.inList(): GraphQLList = GraphQLList.list(this)

    private fun RawValue.asInputFieldMap(): Map<String, RawValue> = (this as RawInput).values.toMap()

    private val RawValue.scalarType: String?
        get() = (this as? RawScalar)?.typename

    @Test
    fun `object values`(): Unit =
        runBlocking {
            val inp = mkType<GraphQLInputObjectType>(
                "Input",
                "input Input { int: Int! str: String! }"
            )

            Arb.rawValueFor(inp.nonNull())
                .checkInvariants { v, check ->
                    val fieldMap = v.asInputFieldMap()
                    check.containsExactlyElementsIn(
                        inp.fields.map { it.name }.toSet(),
                        fieldMap.keys,
                        "unexpected keys; ${fieldMap.keys} "
                    )
                    fieldMap.forEach { (k, v) ->
                        if (k == "int") {
                            check.isTrue(v.scalarType == "Int", "not int: $v")
                        } else {
                            check.isTrue(v.scalarType == "String", "not string: $v")
                        }
                    }
                }
        }

    @Test
    fun `enull and inull`(): Unit =
        runBlocking {
            val inp = mkType<GraphQLInputObjectType>(
                "Input",
                """
                input Input {
                    scalar: Int
                    scalarWithDefault: Int = 42
                    list: [Int!]
                    listWithDefault: [Int!] = []
                    nestedList: [[Int!]!]
                    nestedListWithDefault: [[Int!]!] = []
                    self: Input
                }
                """.trimIndent()
            )

            // explicit null values
            Arb.rawValueFor(inp.nonNull(), cfg = mkConfig(enull = 1.0))
                .checkInvariants { v, check ->
                    v.asInputFieldMap().forEach { (k, v) ->
                        check.isTrue(
                            v is RawENull,
                            "expected field {0} to be RawENull, but found {1}",
                            arrayOf(k, v.toString())
                        )
                    }
                }

            // implicit null values
            Arb.rawValueFor(inp.nonNull(), cfg = mkConfig(inull = 1.0))
                .checkInvariants { v, check ->
                    v.asInputFieldMap().forEach { (k, v) ->
                        check.isTrue(
                            v is RawINull,
                            "expected field {0} to be RawINull, but found {1}",
                            arrayOf(k, v.toString())
                        )
                    }
                }
        }

    @Test
    fun `non-null values`(): Unit =
        runBlocking {
            val inp = mkType<GraphQLInputObjectType>(
                "Input",
                """
                input Other { int: Int! }

                input Input {
                    scalar: Int
                    scalarWithDefault: Int = 42
                    nonNullScalar: Int!
                    nonNullScalarWithDefault: Int! = 42

                    list: [Int!]
                    listWithDefault: [Int!] = []
                    nonNullList: [Int]!
                    nonNullListWithDefault: [Int]! = [1]

                    obj: Other
                    nonNullObj: Other!
                    objWithDefault: Other = { int: 20 }
                    nonNullObjWithDefault: Other! = { int: 30 }
                }
                """.trimIndent()
            )

            Arb.rawValueFor(inp, cfg = mkConfig(enull = 0.0, inull = 0.0))
                .checkInvariants { v, check ->
                    val fieldMap = v.asInputFieldMap()
                    inp.fields.forEach { f ->
                        check.isTrue(
                            !fieldMap[f.name]!!.nullish,
                            "expected field {0} to be non-null but found {1}",
                            arrayOf(f.name, fieldMap[f.name]?.toString())
                        )
                    }
                }
        }

    @Test
    fun `boolean values`() {
        Arb.rawValueFor(Scalars.GraphQLBoolean.nonNull())
            .checkInvariants { v, check ->
                check.isEqualTo(
                    "Boolean",
                    v.scalarType,
                    "expected RawScalar \"Boolean\" value but got $v",
                )
            }
    }

    @Test
    fun `int values`() {
        Arb.rawValueFor(Scalars.GraphQLInt.nonNull())
            .checkInvariants { v, check ->
                check.isEqualTo(
                    "Int",
                    v.scalarType,
                    "expected RawScalar \"Int\" value but got $v",
                )
            }
    }

    @Test
    fun `float values`() {
        Arb.rawValueFor(Scalars.GraphQLFloat.nonNull())
            .checkInvariants { v, check ->
                check.isEqualTo(
                    "Float",
                    v.scalarType,
                    "expected RawScalar \"Float\" value but got $v",
                )
            }
    }

    @Test
    fun `enum values`() {
        Arb.set(Arb.graphQLEnumValueName(), 1..10)
            .flatMap { names ->
                val values = names.map {
                    GraphQLEnumValueDefinition.newEnumValueDefinition().name(it).build()
                }
                val enum = GraphQLEnumType.newEnum()
                    .name("Enum")
                    .values(values)
                    .build()
                Arb.rawValueFor(enum.nonNull()).zip(Arb.constant(names))
            }.checkInvariants { (v, names), check ->
                check.isInstanceOf(RawEnum::class, v, "Expected RawEnum but got $v")
                (v as RawEnum).valueName.let { name ->
                    check.containedBy(names, name, "Expected name $name to be in $names")
                }
            }
    }

    @Test
    fun `list values`() {
        val typ = Scalars.GraphQLInt.inList().nonNull()
        Arb.rawValueFor(typ).checkInvariants { v, check ->
            check.isInstanceOf(RawList::class, v, "Expected RawList but got $v")
        }
    }

    @Test
    fun `string-ish values`() {
        val types = listOf(Scalars.GraphQLString, Scalars.GraphQLID)
        Arb.element(types)
            .flatMap { type ->
                Arb.rawValueFor(type.nonNull()).map { it to type }
            }
            .checkInvariants { (v, type), check ->
                check.isFalse(v is NullValue, "unexpected non-null value")
                check.isTrue(
                    v.scalarType == type.name,
                    "expected RawScalar with type ${type.name} value, but got ${v.scalarType}"
                )
                check.isTrue(
                    (v as RawScalar).value is String,
                    "expected RawScalar \"String\" value, but got $v"
                )
            }
    }

    @Test
    fun `resolvable typeref`() {
        val types = GraphQLTypes.empty.copy(
            inputs = mapOf(
                "Other" to
                    mkType<GraphQLInputObjectType>("Other", "input Other { int: Int! }")
            )
        )
        val ref = GraphQLTypeReference.typeRef("Other")

        Arb.rawValueFor(ref.nonNull(), types, mkConfig(enull = 0.0))
            .checkInvariants { v, check ->
                check.isTrue(v is RawInput, "expected RawInput value but got $v")
                val fields = v.asInputFieldMap()
                check.isTrue(!fields["int"]!!.nullish, "Missing field `int`")
            }
    }

    @Test
    fun `unresolvable typeref`() {
        val ref = GraphQLTypeReference.typeRef("Other")

        // nullable
        Arb.rawValueFor(ref, cfg = mkConfig(enull = 1.0))
            .checkInvariants { v, check ->
                check.isTrue(v is RawENull, "expected RawENull type but got $v")
            }

        // non-nullable
        Arb.graphQLName()
            .map { GraphQLTypeReference(it).nonNull() }
            .checkInvariants { v, check ->
                val result = Result.runCatching {
                    Arb.rawValueFor(v).next(randomSource())
                }
                check.isTrue(
                    result.exceptionOrNull() is IllegalStateException,
                    "expected IllegalArgumentException but got ${result.exceptionOrNull()}"
                )
            }
    }
}
