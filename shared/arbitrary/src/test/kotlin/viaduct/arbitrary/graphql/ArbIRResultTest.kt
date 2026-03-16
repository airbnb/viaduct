@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.language.TypeName
import graphql.parser.Parser
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.ArbitraryBuilderContext
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.mapping.graphql.IR

class ArbIRResultTest : KotestPropertyBase() {
    private val minimalSchema = "extend type Query { x:Int }".asViaductSchema

    private val config = arbitrary {
        mkConfig(
            enull = Arb.of(0.0, 1.0).bind(),
            listValueSize = Arb.of(0, 2).bind()
        )
    }
    private val scalars = Arb.of(builtinScalars.values)

    private suspend fun ArbitraryBuilderContext.maybeNonNull(type: GraphQLOutputType): GraphQLOutputType =
        type.let {
            if (Arb.boolean().bind()) {
                GraphQLNonNull.nonNull(type)
            } else {
                type
            }
        }

    private val GraphQLType.isNonNull: Boolean get() = GraphQLTypeUtil.isNonNull(this)

    private fun parseSelections(fragmentString: String): SelectionSet =
        Parser
            .parse(fragmentString)
            .getFirstDefinitionOfType(FragmentDefinition::class.java)
            .get()
            .selectionSet

    @Test
    fun `ir -- scalar`(): Unit =
        runBlocking {
            val arb = arbitrary {
                val cfg = config.bind()
                val type = scalars.bind()

                val value = Arb.ir(
                    schema = minimalSchema,
                    type = type,
                    selections = null,
                    cfg = cfg
                ).bind()
                Triple(type, cfg, value)
            }
            arb.checkInvariants { (type, cfg, value), check ->
                if (cfg[ExplicitNullValueWeight] == 1.0) {
                    check.isSameInstanceAs(IR.Value.Null, value, "expected enull")
                } else {
                    check.isNotEqualTo(IR.Value.Null, value, "expected non enull value")
                }
            }
        }

    @Test
    fun `ir -- list`(): Unit =
        runBlocking {
            arbitrary {
                val cfg = config.bind()
                val type = maybeNonNull(GraphQLList(maybeNonNull(scalars.bind())))

                val value = Arb
                    .ir(
                        type = type,
                        selections = null,
                        schema = minimalSchema,
                        cfg = cfg
                    ).bind()
                Triple(type, cfg, value)
            }.checkInvariants { (type, cfg, value), check ->
                if (!type.isNonNull && cfg[ExplicitNullValueWeight] == 1.0) {
                    check.isSameInstanceAs(IR.Value.Null, value, "expected enull but got $value")
                } else if (type.isNonNull) {
                    check.isInstanceOf<IR.Value.List>(value, "expected instance of IR.Value.List but got $value")
                }

                if (value is IR.Value.List) {
                    val innerType = GraphQLTypeUtil.unwrapOneAs<GraphQLOutputType>(GraphQLTypeUtil.unwrapNonNull(type))
                    if (!innerType.isNonNull && cfg[ExplicitNullValueWeight] == 1.0) {
                        check.isTrue(
                            value.value.all { it is IR.Value.Null },
                            "expected list of enull but got $value"
                        )
                    }
                    if (innerType.isNonNull) {
                        val expType = GraphQLTypeUtil.unwrapAllAs<GraphQLScalarType>(innerType)
                        check.isTrue(
                            value.value.all { it !is IR.Value.Null },
                            "expected list of ${expType.name} scalars but got $value"
                        )
                    }

                    cfg[ListValueSize].let { expSize ->
                        check.isTrue(
                            expSize.contains(value.value.size),
                            "expected list to contain entries in $expSize but found $value"
                        )
                    }
                }
            }
        }

    @Test
    fun `ir -- enum`(): Unit =
        runBlocking {
            val schema = "enum E { A, B, C }".asViaductSchema
            val enum = schema.schema.getTypeAs<GraphQLEnumType>("E")

            arbitrary {
                val cfg = config.bind()
                val type = maybeNonNull(enum)
                val value = Arb
                    .ir(
                        schema = schema,
                        type = type,
                        selections = null,
                        cfg = cfg
                    ).bind()
                Triple(type, cfg, value)
            }.checkInvariants { (type, cfg, value), check ->
                if (!type.isNonNull && cfg[ExplicitNullValueWeight] == 1.0) {
                    check.isSameInstanceAs(IR.Value.Null, value, "Expected enull but got $value")
                } else {
                    check.isTrue(
                        value is IR.Value.String && value.value in setOf("A", "B", "C"),
                        "value did not match the expected IR String"
                    )
                }
            }
        }

    @Test
    fun `ir -- object`(): Unit =
        runBlocking {
            val schema = "type O { x: Int!, y: Int }".asViaductSchema
            val obj = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            val emptySelections = SelectionSet(emptyList())
            val fullSelections = parseSelections("fragment _ on O { x y }")

            arbitrary {
                val cfg = config.bind()
                val selections = Arb.of(emptySelections, fullSelections).bind()
                val value = Arb
                    .ir(
                        schema = schema,
                        type = obj,
                        selections = selections,
                        cfg = cfg
                    ).bind()
                Triple(selections, cfg, value)
            }.checkInvariants { (selections, cfg, value), check ->
                if (value is IR.Value.Object) {
                    val fieldValues = value.fields.toMap()
                    if (selections == emptySelections) {
                        check.containsExactlyElementsIn(
                            emptySet(),
                            fieldValues.keys,
                            "expected empty IR Object value for empty selections, but got $value"
                        )
                    } else {
                        check.containsExactlyElementsIn(
                            listOf("x", "y"),
                            fieldValues.keys,
                            "expected IR object with values for `x` and `y`, but got $value"
                        )

                        fieldValues["x"].let { x ->
                            check.isTrue(
                                x is IR.Value.Number,
                                "expected field x to have IR Number value, but got $x"
                            )
                        }
                        fieldValues["y"].let { y ->
                            if (cfg[ExplicitNullValueWeight] == 1.0) {
                                check.isTrue(y == IR.Value.Null, "expected `y` to be enull but got $y")
                            } else {
                                check.isTrue(
                                    y is IR.Value.Number,
                                    "expected field `y` to have IR Number value but got $y"
                                )
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `ir -- union`(): Unit =
        runBlocking {
            val schema =
                """
                type A { x: Int! }
                type B { y: Int! }
                type C { z: Int! }
                union U = A | B | C
                """.asViaductSchema
            val u = GraphQLNonNull.nonNull(schema.schema.getTypeAs<GraphQLUnionType>("U"))
            val emptySelections = SelectionSet(emptyList())
            val fullSelections = parseSelections(
                """
                fragment _ on U {
                    ... on A { x }
                    ... on B { y }
                }
                """.trimIndent()
            )

            arbitrary {
                val cfg = config.bind()
                val useEmptySelections = Arb.boolean().bind()
                val selections = if (useEmptySelections) emptySelections else fullSelections
                val value = Arb
                    .ir(
                        schema = schema,
                        type = u,
                        selections = selections,
                        cfg = cfg
                    ).bind()
                useEmptySelections to value
            }.checkInvariants { (useEmptySelections, value), check ->
                check.isTrue(
                    value is IR.Value.Object && value.name in setOf("A", "B", "C"),
                    "expected matching object but got $value"
                )
                value as IR.Value.Object
                val expFields =
                    if (useEmptySelections) {
                        emptySet()
                    } else {
                        when (value.name) {
                            "A" -> setOf("x")
                            "B" -> setOf("y")
                            // "C" is not selected
                            else -> emptySet()
                        }
                    }
                check.isEqualTo(expFields, value.fields.keys, "expected fields `$expFields` but got `${value.fields}`")
            }
        }

    @Test
    fun `ir -- interface`(): Unit =
        runBlocking {
            val schema =
                """
                type A implements I { i: Int!, x: Int! }
                type B implements I { i: Int!, y: Int! }
                type C implements I { i: Int!, z: Int! }
                interface I { i: Int! }
                """.asViaductSchema
            val u = GraphQLNonNull.nonNull(schema.schema.getTypeAs<GraphQLInterfaceType>("I"))
            val emptySelections = SelectionSet(emptyList())
            val fullSelections = parseSelections(
                """
                fragment _ on I {
                    ... on A { i, x }
                    ... on B { i, y }
                }
                """.trimIndent()
            )

            arbitrary {
                val cfg = config.bind()
                val useEmptySelections = Arb.boolean().bind()
                val selections = if (useEmptySelections) emptySelections else fullSelections
                val value = Arb
                    .ir(
                        schema = schema,
                        type = u,
                        selections = selections,
                        cfg = cfg
                    ).bind()
                useEmptySelections to value
            }.checkInvariants { (useEmptySelections, value), check ->
                check.isTrue(
                    value is IR.Value.Object && value.name in setOf("A", "B", "C"),
                    "expected matching object but got $value"
                )
                value as IR.Value.Object
                val expFields =
                    if (useEmptySelections) {
                        emptySet()
                    } else {
                        when (value.name) {
                            "A" -> setOf("i", "x")
                            "B" -> setOf("i", "y")
                            // "C" is not selected
                            else -> emptySet()
                        }
                    }
                check.isEqualTo(expFields, value.fields.keys, "expected fields `$expFields` but got `$${value.fields}`")
            }
        }

    @Test
    fun `ir -- aliases`(): Unit =
        runBlocking {
            val schema = "type O { x: Int! }".asViaductSchema
            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            arbitrary {
                Arb
                    .ir(
                        schema,
                        o,
                        parseSelections("fragment _ on O { a: x }"),
                    ).bind()
            }.checkInvariants { value, check ->
                val obj = value as IR.Value.Object
                check.isEqualTo(
                    setOf("a"),
                    obj.fields.keys,
                    "Expected selection `a` but got ${obj.fields.keys}"
                )
            }
        }

    @Test
    fun `ir -- inline fragment`(): Unit =
        runBlocking {
            val schema = "type O { x: Int! o: O! }".asViaductSchema
            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            arbitrary {
                Arb
                    .ir(
                        schema,
                        o,
                        parseSelections(
                            """
                        fragment _ on O {
                            o {
                                ... on O {
                                    x
                                }
                            }
                        }
                            """.trimIndent()
                        ),
                    ).bind()
            }.checkInvariants { value, check ->
                val obj = value as IR.Value.Object
                check.isEqualTo("O", obj.name, "Expected IR Object for `O` but got $obj")

                val inner = obj.fields["o"] as? IR.Value.Object
                check.isNotNull(inner, "missing result for selection `o`")

                if (inner != null) {
                    val x = inner.fields["x"] as? IR.Value.Number
                    check.isNotNull(x, "missing result for selection `x`")
                }
            }
        }

    @Test
    fun `ir -- field adjacent to inline fragment`(): Unit =
        runBlocking {
            val schema = "type O { x: Int! y: Int! o: O! }".asViaductSchema
            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            arbitrary {
                Arb
                    .ir(
                        schema,
                        o,
                        parseSelections(
                            """
                        fragment _ on O {
                            o {
                                y
                                ... on O {
                                    x
                                }
                            }
                        }
                            """.trimIndent()
                        ),
                    ).bind()
            }.checkInvariants { value, check ->
                val obj = value as IR.Value.Object
                check.isEqualTo("O", obj.name, "Expected IR object for `O` but got $obj")

                val inner = obj.fields["o"] as? IR.Value.Object
                check.isNotNull(inner, "missing result for selection `o`")

                if (inner != null) {
                    val x = inner.fields["x"] as? IR.Value.Number
                    check.isNotNull(x, "missing result for selection `x` from inline fragment")

                    // Field before inline fragment should be present
                    val y = inner.fields["y"] as? IR.Value.Number
                    check.isNotNull(y, "missing result for selection `y` - field adjacent to inline fragment is lost!")
                }
            }
        }

    @Test
    fun `ir -- __typename on object`(): Unit =
        runBlocking {
            val schema = "type O { x:Int! }".asViaductSchema
            val selectionStrings = listOf(
                "fragment _ on O { __typename }",
                "fragment _ on O { ... { __typename } }",
                "fragment _ on O { ... on O { __typename } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.ir(schema, o, selectionString).bind()
            }.forAll { value ->
                value == IR.Value.Object("O", "__typename" to IR.Value.String("O"))
            }
        }

    @Test
    fun `ir -- __typename on union`(): Unit =
        runBlocking {
            val schema =
                """
                    union Union = Impl1 | Impl2
                    type Impl1 { x:Int }
                    type Impl2 { x:Int }
                    type Impl3 { x:Int }
                """.asViaductSchema
            val selectionStrings = listOf(
                "fragment _ on Union { __typename }",
                "fragment _ on Union { ... { __typename } }",
                "fragment _ on Union { ... on Union { __typename } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.schema.getTypeAs<GraphQLUnionType>("Union"))
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.ir(schema, o, selectionString).bind()
            }.forAll { value ->
                value as IR.Value.Object
                value.name in setOf("Impl1", "Impl2") &&
                    value.fields == mapOf("__typename" to IR.Value.String(value.name))
            }
        }

    @Test
    fun `ir -- __typename on interface`(): Unit =
        runBlocking {
            val schema =
                """
                    interface Interface { x:Int }
                    type Impl1 implements Interface { x:Int }
                    type Impl2 implements Interface { x:Int }
                    type Impl3 { x:Int }
                """.asViaductSchema
            val selectionStrings = listOf(
                "fragment _ on Interface { __typename }",
                "fragment _ on Interface { ... { __typename } }",
                "fragment _ on Interface { ... on Interface { __typename } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.schema.getTypeAs<GraphQLInterfaceType>("Interface"))
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.ir(schema, o, selectionString).bind()
            }.forAll { value ->
                value as IR.Value.Object
                value.name in setOf("Impl1", "Impl2") &&
                    value.fields == mapOf("__typename" to IR.Value.String(value.name))
            }
        }

    @Test
    fun `ir -- __typename on list value`(): Unit =
        runBlocking {
            val schema =
                """
                    type Item { x:Int }
                    type Subject { items: [Item] }
                """.asViaductSchema
            val selectionStrings = listOf(
                "fragment _ on Subject { items { __typename } }",
                "fragment _ on Subject { items { ... { __typename } } }",
                "fragment _ on Subject { items { ... on Item { __typename } } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("Subject"))
            val cfg = Config.default + (ExplicitNullValueWeight to 0.0)
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.ir(schema, o, selectionString, cfg = cfg).bind()
            }.forAll { value ->
                value as IR.Value.Object
                val items = value.fields["items"] as IR.Value.List
                items.value.all {
                    it as IR.Value.Object
                    it.name == "Item" && it.fields["__typename"] == IR.Value.String("Item")
                }
            }
        }

    @Test
    fun `ir -- inline fragment without type condition`(): Unit =
        runBlocking {
            val schema = "type O { x: Int! }".asViaductSchema
            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            arbitrary {
                Arb
                    .ir(
                        schema,
                        o,
                        parseSelections(
                            """
                        fragment _ on O {
                            ... {
                               ... {
                                  ... {
                                      x
                                  }
                               }
                            }
                        }
                            """.trimIndent()
                        ),
                    ).bind()
            }.checkInvariants { value, check ->
                val obj = value as IR.Value.Object
                check.isEqualTo("O", obj.name, "Expected IR Object for `O` but got $obj")

                val x = obj.fields["x"] as? IR.Value.Number
                check.isNotNull(x, "missing result for selection `x`")
            }
        }

    @Test
    fun `ir -- fragment spread`(): Unit =
        runBlocking {
            val schema = "type O { x: Int! }".asViaductSchema
            val o = GraphQLNonNull.nonNull(schema.schema.getObjectType("O"))
            arbitrary {
                Arb
                    .ir(
                        schema,
                        type = o,
                        selections = parseSelections("fragment _ on O { ... F }"),
                        fragments = mapOf(
                            "F" to FragmentDefinition
                                .newFragmentDefinition()
                                .name("F")
                                .typeCondition(TypeName("O"))
                                .selectionSet(parseSelections("fragment F on O { x }"))
                                .build()
                        ),
                    ).bind()
            }.checkInvariants { value, check ->
                val obj = value as IR.Value.Object
                check.isEqualTo("O", obj.name, "Expected IR Object for `O` but got $obj")

                val x = obj.fields["x"] as? IR.Value.Number
                check.isNotNull(x, "missing result for selection `x`")
            }
        }

    @Test
    fun `SelectedTypeBias -- union`(): Unit =
        runBlocking {
            val schema =
                """
                type A { x: Int }
                type B { x: Int }
                union U = A | B
                """.asViaductSchema
            val selections = listOf(
                "fragment _ on U { ... on A { __typename } }",
                "fragment _ on U { ... on U { ... on A { __typename } } }",
                "fragment _ on U { ... { ... on A { __typename } } }",
            ).map(::parseSelections)

            val u = GraphQLNonNull.nonNull(schema.schema.getTypeAs<GraphQLUnionType>("U"))
            val arb = arbitrary {
                Arb
                    .ir(
                        schema = schema,
                        type = u,
                        selections = Arb.of(selections).bind(),
                        cfg = Config.default + (SelectedTypeBias to 1.0)
                    ).bind()
            }

            arb.forAll { it is IR.Value.Object && it.name == "A" }
        }
}
