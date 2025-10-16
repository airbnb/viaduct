@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.Scalars
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
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.checkInvariants
import viaduct.arbitrary.graphql.BridgeGJToRaw.scalar
import viaduct.mapping.graphql.RawENull
import viaduct.mapping.graphql.RawEnum
import viaduct.mapping.graphql.RawINull
import viaduct.mapping.graphql.RawList
import viaduct.mapping.graphql.RawObject
import viaduct.mapping.graphql.RawScalar
import viaduct.mapping.graphql.RawValue
import viaduct.mapping.graphql.ValueMapper

class GJRawValueResultGenTest : KotestPropertyBase() {
    private val minimalSchema = mkGJSchema("", includeMinimal = true)
    private val config = arbitrary {
        mkConfig(
            enull = Arb.of(0.0, 1.0).bind(),
            listValueSize = Arb.of(0, 2).bind()
        )
    }
    private val scalars = Arb.of(
        Scalars.GraphQLInt,
        Scalars.GraphQLFloat,
        Scalars.GraphQLString,
        Scalars.GraphQLID,
        Scalars.GraphQLBoolean
    )

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
        Parser.parse(fragmentString)
            .getFirstDefinitionOfType(FragmentDefinition::class.java)
            .get()
            .selectionSet

    @Test
    fun `rawValueFor -- scalar`(): Unit =
        runBlocking {
            arbitrary {
                val cfg = config.bind()
                val type = scalars.bind()
                val value = Arb.rawValueFor(
                    type = type,
                    selections = null,
                    schema = minimalSchema,
                    cfg = cfg
                ).bind()
                Triple(type, cfg, value)
            }.checkInvariants { (type, cfg, value), check ->
                if (cfg[ExplicitNullValueWeight] == 1.0) {
                    check.isSameInstanceAs(RawENull, value, "expected enull")
                } else {
                    (value as? RawScalar).let {
                        check.isNotNull(it, "expected RawScalar but got null")
                        check.isEqualTo(type.name, it?.typename, "expected ${type.name} but got ${it?.typename}")
                    }
                }
            }
        }

    @Test
    fun `rawValueFor -- list`(): Unit =
        runBlocking {
            arbitrary {
                val cfg = config.bind()
                val type = maybeNonNull(GraphQLList.list(maybeNonNull(scalars.bind())))

                val value = Arb.rawValueFor(
                    type = type,
                    selections = null,
                    schema = minimalSchema,
                    cfg = cfg
                ).bind()
                Triple(type, cfg, value)
            }.checkInvariants { (type, cfg, value), check ->
                if (!type.isNonNull && cfg[ExplicitNullValueWeight] == 1.0) {
                    check.isSameInstanceAs(RawENull, value, "expected enull but got $value")
                } else if (type.isNonNull) {
                    check.isInstanceOf<RawList>(value, "expected instance of RawList but got $value")
                }

                if (value is RawList) {
                    val innerType = GraphQLTypeUtil.unwrapOneAs<GraphQLOutputType>(GraphQLTypeUtil.unwrapNonNull(type))
                    if (!innerType.isNonNull && cfg[ExplicitNullValueWeight] == 1.0) {
                        check.isTrue(
                            value.values.all { it is RawENull },
                            "expected list of enull but got $value"
                        )
                    }
                    if (innerType.isNonNull) {
                        val expType = GraphQLTypeUtil.unwrapAllAs<GraphQLScalarType>(innerType)
                        check.isTrue(
                            value.values.all { it is RawScalar && it.typename == expType.name },
                            "expected list of ${expType.name} scalars but got $value"
                        )
                    }

                    cfg[ListValueSize].let { expSize ->
                        check.isTrue(
                            expSize.contains(value.values.size),
                            "expected list to contain entries in $expSize but found $value"
                        )
                    }
                }
            }
        }

    @Test
    fun `rawValueFor -- enum`(): Unit =
        runBlocking {
            val schema = mkGJSchema("enum E { A, B, C }")
            val enum = schema.getTypeAs<GraphQLEnumType>("E")

            arbitrary {
                val cfg = config.bind()
                val type = maybeNonNull(enum)
                val value = Arb.rawValueFor(
                    type = type,
                    selections = null,
                    schema = schema,
                    cfg = cfg
                ).bind()
                Triple(type, cfg, value)
            }.checkInvariants { (type, cfg, value), check ->
                if (!type.isNonNull && cfg[ExplicitNullValueWeight] == 1.0) {
                    check.isSameInstanceAs(RawENull, value, "Expected enull but got $value")
                } else {
                    check.isTrue(
                        value is RawEnum && value.valueName in setOf("A", "B", "C"),
                        "value did not match the expected RawEnum"
                    )
                }
            }
        }

    @Test
    fun `rawValueFor -- object`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x: Int!, y: Int }")
            val obj = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            val emptySelections = SelectionSet(emptyList())
            val fullSelections = parseSelections("fragment _ on O { x y }")

            arbitrary {
                val cfg = config.bind()
                val selections = Arb.of(emptySelections, fullSelections).bind()
                val value = Arb.rawValueFor(
                    type = obj,
                    selections = selections,
                    schema = schema,
                    cfg = cfg
                ).bind()
                Triple(selections, cfg, value)
            }.checkInvariants { (selections, cfg, value), check ->
                if (value is RawObject) {
                    val fieldValues = value.values.toMap()
                    if (selections == emptySelections) {
                        check.containsExactlyElementsIn(
                            emptySet(),
                            fieldValues.keys,
                            "expected empty RawObject value for empty selections, but got $value"
                        )
                    } else {
                        check.containsExactlyElementsIn(
                            listOf("x", "y"),
                            fieldValues.keys,
                            "expected RawObject with values for `x` and `y`, but got $value"
                        )

                        fieldValues["x"].let { x ->
                            check.isTrue(
                                x is RawScalar && x.typename == "Int",
                                "expected field x to have RawScalar int value, but got $x"
                            )
                        }
                        fieldValues["y"].let { y ->
                            if (cfg[ExplicitNullValueWeight] == 1.0) {
                                check.isTrue(y == RawENull, "expected `y` to be enull but got $y")
                            } else {
                                check.isTrue(
                                    y is RawScalar && y.typename == "Int",
                                    "expected field `y` to have RawScalar int value but got $y"
                                )
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `rawValueFor -- union`(): Unit =
        runBlocking {
            val schema = mkGJSchema(
                """
                type A { x: Int! }
                type B { y: Int! }
                type C { z: Int! }
                union U = A | B | C
                """.trimIndent()
            )
            val u = GraphQLNonNull.nonNull(schema.getTypeAs<GraphQLUnionType>("U"))
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
                val value = Arb.rawValueFor(
                    type = u,
                    selections = selections,
                    schema = schema,
                    cfg = cfg
                ).bind()
                useEmptySelections to value
            }.checkInvariants { (useEmptySelections, value), check ->
                check.isTrue(
                    value is RawObject && value.typename in setOf("A", "B", "C"),
                    "expected matching object but got $value"
                )
                value as RawObject
                val fields = value.values.toMap()
                val expFields =
                    if (useEmptySelections) {
                        emptySet()
                    } else {
                        when (value.typename) {
                            "A" -> setOf("x")
                            "B" -> setOf("y")
                            // "C" is not selected
                            else -> emptySet()
                        }
                    }
                check.isEqualTo(expFields, fields.keys, "expected fields `$expFields` but got `$fields`")
            }
        }

    @Test
    fun `rawValueFor -- interface`(): Unit =
        runBlocking {
            val schema = mkGJSchema(
                """
                type A implements I { i: Int!, x: Int! }
                type B implements I { i: Int!, y: Int! }
                type C implements I { i: Int!, z: Int! }
                interface I { i: Int! }
                """.trimIndent()
            )
            val u = GraphQLNonNull.nonNull(schema.getTypeAs<GraphQLInterfaceType>("I"))
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
                val value = Arb.rawValueFor(
                    type = u,
                    selections = selections,
                    schema = schema,
                    cfg = cfg
                ).bind()
                useEmptySelections to value
            }.checkInvariants { (useEmptySelections, value), check ->
                check.isTrue(
                    value is RawObject && value.typename in setOf("A", "B", "C"),
                    "expected matching object but got $value"
                )
                value as RawObject
                val fields = value.values.toMap()
                val expFields =
                    if (useEmptySelections) {
                        emptySet()
                    } else {
                        when (value.typename) {
                            "A" -> setOf("i", "x")
                            "B" -> setOf("i", "y")
                            // "C" is not selected
                            else -> emptySet()
                        }
                    }
                check.isEqualTo(expFields, fields.keys, "expected fields `$expFields` but got `$fields`")
            }
        }

    @Test
    fun `rawValueFor -- aliases`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x: Int! }")
            val o = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            arbitrary {
                Arb.rawValueFor(
                    o,
                    parseSelections("fragment _ on O { a: x }"),
                    schema,
                ).bind()
            }.checkInvariants { value, check ->
                val obj = value as RawObject
                check.isEqualTo(
                    setOf("a"),
                    obj.values.toMap().keys,
                    "Expected selection `a` but got ${obj.values}"
                )
            }
        }

    @Test
    fun `rawValueFor -- inline fragment`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x: Int! o: O! }")
            val o = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            arbitrary {
                Arb.rawValueFor(
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
                    schema,
                ).bind()
            }.checkInvariants { value, check ->
                val obj = value as RawObject
                check.isEqualTo("O", obj.typename, "Expected RawObject for `O` but got $obj")

                val inner = obj.values.toMap()["o"] as? RawObject
                check.isNotNull(inner, "missing result for selection `o`")

                if (inner != null) {
                    val x = inner.values.toMap()["x"] as? RawScalar
                    check.isNotNull(x, "missing result for selection `x`")
                }
            }
        }

    @Test
    fun `rawValueFor -- field adjacent to inline fragment`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x: Int! y: Int! o: O! }")
            val o = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            arbitrary {
                Arb.rawValueFor(
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
                    schema,
                ).bind()
            }.checkInvariants { value, check ->
                val obj = value as RawObject
                check.isEqualTo("O", obj.typename, "Expected RawObject for `O` but got $obj")

                val inner = obj.values.toMap()["o"] as? RawObject
                check.isNotNull(inner, "missing result for selection `o`")

                if (inner != null) {
                    val innerFields = inner.values.toMap()

                    val x = innerFields["x"] as? RawScalar
                    check.isNotNull(x, "missing result for selection `x` from inline fragment")

                    // Field before inline fragment should be present
                    val y = innerFields["y"] as? RawScalar
                    check.isNotNull(y, "missing result for selection `y` - field adjacent to inline fragment is lost!")
                }
            }
        }

    @Test
    fun `rawValueFor -- __typename on object`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x:Int! }")
            val selectionStrings = listOf(
                "fragment _ on O { __typename }",
                "fragment _ on O { ... { __typename } }",
                "fragment _ on O { ... on O { __typename } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.rawValueFor(o, selectionString, schema).bind()
            }.forAll { value ->
                value == RawObject("O", listOf("__typename" to "O".scalar))
            }
        }

    @Test
    fun `rawValueFor -- __typename on union`(): Unit =
        runBlocking {
            val schema = mkGJSchema(
                """
                    union Union = Impl1 | Impl2
                    type Impl1 { x:Int }
                    type Impl2 { x:Int }
                    type Impl3 { x:Int }
                """.trimIndent()
            )
            val selectionStrings = listOf(
                "fragment _ on Union { __typename }",
                "fragment _ on Union { ... { __typename } }",
                "fragment _ on Union { ... on Union { __typename } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.getTypeAs<GraphQLUnionType>("Union"))
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.rawValueFor(o, selectionString, schema).bind()
            }.forAll { value ->
                value as RawObject
                value.typename in setOf("Impl1", "Impl2") &&
                    value.values.toMap() == mapOf("__typename" to value.typename.scalar)
            }
        }

    @Test
    fun `rawValueFor -- __typename on interface`(): Unit =
        runBlocking {
            val schema = mkGJSchema(
                """
                    interface Interface { x:Int }
                    type Impl1 implements Interface { x:Int }
                    type Impl2 implements Interface { x:Int }
                    type Impl3 { x:Int }
                """.trimIndent()
            )
            val selectionStrings = listOf(
                "fragment _ on Interface { __typename }",
                "fragment _ on Interface { ... { __typename } }",
                "fragment _ on Interface { ... on Interface { __typename } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.getTypeAs<GraphQLInterfaceType>("Interface"))
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.rawValueFor(o, selectionString, schema).bind()
            }.forAll { value ->
                value as RawObject
                value.typename in setOf("Impl1", "Impl2") &&
                    value.values.toMap() == mapOf("__typename" to value.typename.scalar)
            }
        }

    @Test
    fun `rawValueFor -- __typename on list value`(): Unit =
        runBlocking {
            val schema = mkGJSchema(
                """
                    type Item { x:Int }
                    type Subject { items: [Item] }
                """.trimIndent()
            )
            val selectionStrings = listOf(
                "fragment _ on Subject { items { __typename } }",
                "fragment _ on Subject { items { ... { __typename } } }",
                "fragment _ on Subject { items { ... on Item { __typename } } }",
            ).map(::parseSelections)

            val o = GraphQLNonNull.nonNull(schema.getObjectType("Subject"))
            val cfg = Config.default + (ExplicitNullValueWeight to 0.0)
            arbitrary {
                val selectionString = Arb.of(selectionStrings).bind()
                Arb.rawValueFor(o, selectionString, schema, cfg = cfg).bind()
            }.forAll { value ->
                value as RawObject
                val items = value.values.toMap()["items"] as RawList
                items.values.all {
                    it as RawObject
                    it.typename == "Item" && it.values.toMap()["__typename"] == "Item".scalar
                }
            }
        }

    @Test
    fun `rawValueFor -- inline fragment without type condition`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x: Int! }")
            val o = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            arbitrary {
                Arb.rawValueFor(
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
                    schema,
                ).bind()
            }.checkInvariants { value, check ->
                val obj = value as RawObject
                check.isEqualTo("O", obj.typename, "Expected RawObject for `O` but got $obj")

                val x = obj.values.toMap()["x"] as? RawScalar
                check.isNotNull(x, "missing result for selection `x`")
            }
        }

    @Test
    fun `rawValueFor -- fragment spread`(): Unit =
        runBlocking {
            val schema = mkGJSchema("type O { x: Int! }")
            val o = GraphQLNonNull.nonNull(schema.getObjectType("O"))
            arbitrary {
                Arb.rawValueFor(
                    type = o,
                    selections = parseSelections("fragment _ on O { ... F }"),
                    schema,
                    fragments = mapOf(
                        "F" to FragmentDefinition.newFragmentDefinition()
                            .name("F")
                            .typeCondition(TypeName("O"))
                            .selectionSet(parseSelections("fragment F on O { x }"))
                            .build()
                    ),
                ).bind()
            }.checkInvariants { value, check ->
                val obj = value as RawObject
                check.isEqualTo("O", obj.typename, "Expected RawObject for `O` but got $obj")

                val x = obj.values.toMap()["x"] as? RawScalar
                check.isNotNull(x, "missing result for selection `x`")
            }
        }

    @Test
    fun `mapped raw value`(): Unit =
        runBlocking {
            // a mapper that renders a RawValue into a simple kotlin representation
            val mapper = object : ValueMapper<Unit, RawValue, Any?> {
                override fun invoke(
                    type: Unit,
                    value: RawValue
                ): Any? = map(value)

                private fun map(value: RawValue): Any? =
                    when (value) {
                        RawENull, RawINull -> null
                        is RawObject -> value.values.associateBy({ it.first }, { map(it.second) })
                        is RawList -> value.values.map(::map)
                        is RawEnum -> value.valueName
                        is RawScalar -> value.value
                        else -> throw IllegalArgumentException("unexpected value: $value")
                    }
            }

            Arb.rawValueFor(
                document = Parser.parse(
                    """
                        {
                            b {
                                a {
                                    x
                                    y
                                }
                            }
                        }
                    """.trimIndent()
                ),
                schema = mkGJSchema(
                    """
                        type A { x: Int!, y: String! }
                        type B { a: A! }
                        type Query { b: B! }
                    """.trimIndent(),
                    includeMinimal = false
                ),
            )
                .map { mapper(Unit, it) }
                .forAll { value ->
                    val b = (value as Map<*, *>)["b"] as Map<*, *>
                    val a = b["a"] as Map<*, *>
                    a["x"] is Int && a["y"] is String
                }
        }

    @Test
    fun `SelectedTypeBias -- union`(): Unit =
        runBlocking {
            val schema = mkGJSchema(
                """
                type A { x: Int }
                type B { x: Int }
                union U = A | B
                """.trimIndent()
            )
            val selections = listOf(
                "fragment _ on U { ... on A { __typename } }",
                "fragment _ on U { ... on U { ... on A { __typename } } }",
                "fragment _ on U { ... { ... on A { __typename } } }",
            ).map(::parseSelections)

            val u = GraphQLNonNull.nonNull(schema.getTypeAs<GraphQLUnionType>("U"))
            val arb = arbitrary {
                Arb.rawValueFor(
                    type = u,
                    selections = Arb.of(selections).bind(),
                    schema = schema,
                    cfg = Config.default + (SelectedTypeBias to 1.0)
                ).bind()
            }

            arb.forAll { it is RawObject && it.typename == "A" }
        }
}
