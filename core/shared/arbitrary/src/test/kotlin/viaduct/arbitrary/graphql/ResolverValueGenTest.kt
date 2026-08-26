package viaduct.arbitrary.graphql

import io.kotest.common.runBlocking
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.take
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.mapNotNull
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RootFieldReference
import viaduct.mapping.graphql.IR
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class ResolverValueGenTest : KotestPropertyBase() {
    @Test
    fun `fieldResolverValue -- simple`(): Unit =
        runBlocking {
            val arb = Arb.fieldResolverValue(
                schema = "extend type Query { x:Int! @resolver }".asViaductSchema,
                coord = "Query" to "x",
                selections = null,
                ctx = MockEngineCtx(),
            )

            arb.forAll { it is Int }
        }

    @Test
    fun `fieldResolverValue -- obj simple`(): Unit =
        runBlocking {
            val schema = "extend type Query { query:Query! @resolver }".asViaductSchema
            val arb = Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "query",
                selections = schema.mkEngineSelectionSet("Query", "__typename"),
                ctx = MockEngineCtx(),
            )
            arb.forAll { it is EngineObjectData && it.type.name == "Query" }
        }

    @Test
    fun `fieldResolverValue -- excludes parent fields`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { foo: Foo! @resolver }
                type Foo { bar: Bar! }
                type Bar { parent: Foo! @parent, x: Int! }
            """.trimIndent().asViaductSchema

            val arb = Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "foo",
                selections = schema.mkEngineSelectionSet("Foo", "bar { x }"),
                ctx = MockEngineCtx(),
            )

            arb.checkAll { value ->
                value as EngineObjectData
                val bar = value.fetch("bar") as EngineObjectData
                assertEquals(setOf("x"), bar.fetchSelections())
            }
        }

    @Test
    fun `fieldResolverValue -- selective`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { obj:Obj! @resolver }
                type Obj { x:Int!, y:Int! }
            """.trimIndent().asViaductSchema

            val arb = arbitrary {
                val selective = Arb.boolean().bind()

                val value = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "obj",
                    selections = schema.mkEngineSelectionSet("Obj", "x"),
                    ctx = MockEngineCtx(),
                    selective = selective,
                ).bind()

                selective to value
            }

            arb.forAll { (selective, value) ->
                val expKeys = if (selective) setOf("x") else setOf("x", "y")
                value is EngineObjectData && value.fetchSelections().toSet() == expKeys
            }
        }

    @Test
    fun `fieldResolverValue -- selective -- subset stability`(): Unit =
        runBlocking {
            // ensure that for a fixed seed, selective values are always a subset of non-selective values
            // This ensures that the value that is generated for any individual selection does
            // not depend on values generated for previous selections

            val schema = """
                extend type Query { obj:Obj! @resolver }
                type Obj { a:Int, b:Int, foo:Foo }
                type Foo { x:Int, y:Int }
            """.trimIndent().asViaductSchema

            val arb = arbitrary {
                val seed = Arb.long().bind()

                val nonSelective = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "obj",
                    selections = schema.mkEngineSelectionSet(
                        "Obj",
                        "a, b, foo { x, y }"
                    ),
                    ctx = MockEngineCtx(),
                    selective = false
                ).next(RandomSource.seeded(seed))

                val selective = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "obj",
                    selections = schema.mkEngineSelectionSet(
                        "Obj",
                        "a, foo { y }"
                    ),
                    ctx = MockEngineCtx(),
                    selective = true
                ).next(RandomSource.seeded(seed))

                nonSelective to selective
            }

            arb.checkAll { (nonSelective, selective) ->
                assertTrue(isSubset(nonSelective, selective)) {
                    """
                        Selective value is not a subset of the non-selective value
                        selective value: $selective
                        non-selective value: $nonSelective
                    """.trimIndent()
                }
            }
        }

    @Test
    fun `fieldResolverValue -- selective -- abstract fields`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { obj:Obj! @resolver }
                type Obj implements Node { id:ID!, x:Int!, y:Int! }
            """.trimIndent().asViaductSchema

            val arb = arbitrary {
                val selective = Arb.boolean().bind()

                val value = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "obj",
                    selections = schema.mkEngineSelectionSet(
                        "Node",
                        "id, ... on Obj { x }"
                    ),
                    ctx = MockEngineCtx(),
                    selective = selective,
                ).bind()

                selective to value
            }

            arb.forAll { (selective, value) ->
                val expKeys = if (selective) setOf("id", "x") else setOf("id", "x", "y")
                value is EngineObjectData && value.fetchSelections().toSet() == expKeys
            }
        }

    @Test
    fun `fieldResolverValue -- result is scoped to output selection set`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { obj:Obj! @resolver }
                type Foo implements Node @resolver { id:ID! }
                type Obj { x:Int!, y:Int! @resolver, z:Foo! }
            """.trimIndent().asViaductSchema
            val resolverCoordinate = "Query" to "obj"
            val arb = arbitrary { rs ->
                val env = ViaductGenEnv(schema, Config.default, rs)
                val value = env.fieldResolverValueGen.gen(
                    coord = resolverCoordinate,
                    selective = false,
                    selections = schema.mkEngineSelectionSet(
                        "Obj",
                        "x, y, z { id }"
                    ),
                    ctx = MockEngineCtx(),
                )
                env.coordinateIndex to value
            }
            arb.checkAll { (coordinateIndex, value) ->
                value as EngineObjectData
                assertEquals(setOf("x", "z"), value.fetchSelections())

                val nodeRefIsAllowed =
                    coordinateIndex.comparator.compare("Foo" to null, resolverCoordinate) < 0
                assertEquals(nodeRefIsAllowed, value.fetch("z") is NodeReference)
            }
        }

    @Test
    fun `nodeResolverValue -- simple`(): Unit =
        runBlocking {
            val schema = "type Foo implements Node { id:ID!, x:Int! }".asViaductSchema

            val arb = Arb.nodeResolverValue(
                schema = schema,
                type = "Foo",
                selections = schema.mkEngineSelectionSet("Foo", "id x"),
                ctx = MockEngineCtx(),
            )

            arb.forAll {
                it is EngineObjectData && it.type.name == "Foo" && it.fetchSelections() == setOf("x")
            }
        }

    @Test
    fun `nodeResolverValue -- selective`(): Unit =
        runBlocking {
            val schema = "type Foo implements Node { id:ID!, x:Int!, y:Int!}".asViaductSchema

            val arb = arbitrary {
                val selective = Arb.boolean().bind()
                val value = Arb.nodeResolverValue(
                    schema = schema,
                    type = "Foo",
                    selections = schema.mkEngineSelectionSet("Foo", "x"),
                    ctx = MockEngineCtx(),
                    selective = selective,
                ).bind()

                selective to value
            }

            arb.forAll { (selective, value) ->
                value as EngineObjectData
                val sels = value.fetchSelections().toSet()

                if (selective) {
                    sels == setOf("x")
                } else {
                    sels == setOf("x", "y")
                }
            }
        }

    @Test
    fun `nodeResolverValue -- result is scoped to output selection set`(): Unit =
        runBlocking {
            val schema = """
                type Foo implements Node @resolver { id:ID!, x:Int!, y:Int! @resolver, obj:Obj! }
                type Obj { x:Int!, y:Int! @resolver, z: Foo }
            """.trimIndent().asViaductSchema
            val arb = Arb.nodeResolverValue(
                schema = schema,
                type = "Foo",
                selections = schema.mkEngineSelectionSet(
                    "Foo",
                    "id, x, y, obj { x, y }"
                ),
                ctx = MockEngineCtx(),
                cfg = Config.default + (ExplicitNullValueWeight to 0.0),
            )

            arb.checkAll {
                it as EngineObjectData
                assertEquals(setOf("x", "obj"), it.fetchSelections())

                // check that the node resolver resolved nested fields that don't have a resolver
                val obj = it.fetch("obj") as EngineObjectData
                assertEquals(setOf("x", "z"), obj.fetchSelections())

                // check that Obj.z is returned as a NodeReference
                assertTrue(obj.fetch("z") is NodeReference)
            }
        }

    @Test
    fun `nodeResolverValue -- recursively nested`(): Unit =
        runBlocking {
            val schema = "type Foo implements Node @resolver { id:ID!, foo1:Foo, foo2:Foo! }".asViaductSchema
            val arb = Arb.nodeResolverValue(
                schema = schema,
                type = "Foo",
                selections = schema.mkEngineSelectionSet(
                    "Foo",
                    "id, foo1 { id }, foo2 { id }"
                ),
                ctx = MockEngineCtx(),
                cfg = Config.default + (ExplicitNullValueWeight to 0.0)
            )

            arb.checkAll {
                it as EngineObjectData

                // the node resolver value generator should not hydrate the foo1 or foo2 selections,
                // since they have a resolver
                assertTrue(it.fetch("foo1") is NodeReference)
                assertTrue(it.fetch("foo2") is NodeReference)
            }
        }

    @Test
    fun `enum values`(): Unit =
        runBlocking {
            val schema = """
                enum E { A, B, C }
                extend type Query { e:E! }
            """.trimIndent().asViaductSchema

            Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "e",
                selections = null,
                ctx = MockEngineCtx(),
            ).forAll {
                it in setOf("A", "B", "C")
            }
        }

    @Test
    fun `ID values`(): Unit =
        runBlocking {
            val codec = GlobalIDCodecDefault
            val schema = """
                extend type Query { id:ID! @resolver @idOf(type:"Foo") }
                type Foo implements Node { id:ID! }
                type Bar implements Node { id:ID! }
            """.trimIndent().asViaductSchema

            Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "id",
                selections = null,
                ctx = MockEngineCtx(),
            ).forAll { value ->
                value as String
                codec.deserialize(value).typeName == "Foo"
            }
        }

    @Test
    fun `ID values -- list`(): Unit =
        runBlocking {
            val codec = GlobalIDCodecDefault
            val schema = """
                extend type Query { ids:[ID!]! @resolver @idOf(type:"Foo") }
                type Foo implements Node { id:ID! }
                type Bar implements Node { id:ID! }
            """.trimIndent().asViaductSchema

            Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "ids",
                selections = null,
                ctx = MockEngineCtx(),
                cfg = Config.default + (ListValueSize to 3.asIntRange()),
            ).forAll { value ->
                @Suppress("UNCHECKED_CAST")
                value as List<String>
                value.all {
                    codec.deserialize(it).typeName == "Foo"
                }
            }
        }

    @Test
    fun `ID -- IDValueGenFactory`(): Unit =
        runBlocking {
            val factory = IDValueGen.Factory {
                IDValueGen {
                    IR.Value.String("TEST_ID")
                }
            }

            Arb.fieldResolverValue(
                schema = "extend type Query { id:ID! @resolver }".asViaductSchema,
                coord = "Query" to "id",
                selections = null,
                ctx = MockEngineCtx(),
                cfg = Config.default + (IDValueGenFactory to factory),
            ).forAll { value ->
                value == "TEST_ID"
            }
        }

    @Test
    fun `ExplicitNullValueWeight`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:Int @resolver }".asViaductSchema
            val arb = arbitrary {
                val enull = Arb.of(0.0, 1.0).bind()
                val value = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "x",
                    selections = null,
                    ctx = MockEngineCtx(),
                    cfg = Config.default + (ExplicitNullValueWeight to enull),
                ).bind()

                enull to value
            }

            arb.forAll { (enull, value) ->
                (value == null) == (enull == 1.0)
            }
        }

    @Test
    fun `ListValueSize`(): Unit =
        runBlocking {
            val schema = "extend type Query { x:[Int!]! @resolver }".asViaductSchema
            val arb = arbitrary {
                val size = Arb.int(0, 10).bind()
                val value = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "x",
                    selections = null,
                    ctx = MockEngineCtx(),
                    cfg = Config.default + (ListValueSize to size.asIntRange()),
                ).bind()

                size to value
            }

            arb.forAll { (size, value) ->
                value as List<*>
                value.size == size
            }
        }

    @Test
    fun `MaxValueDepth -- nullable recursive field exits with null`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { obj: Obj! @resolver }
                type Obj { obj: Obj }
            """.trimIndent().asViaductSchema

            val arb = Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "obj",
                selections = schema.mkEngineSelectionSet("Obj", "obj { __typename }"),
                ctx = MockEngineCtx(),
                cfg = Config.default +
                    (IncludeRequiredResolvers to false) +
                    (ExplicitNullValueWeight to 0.0) +
                    (MaxValueDepth to 1),
            )
            arb.checkAll { v ->
                v as EngineObjectData
                assertNull(v.fetch("obj"))
            }
        }

    @Test
    fun `MaxValueDepth -- recursive list exits with empty list`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { entries: [Entry!]! @resolver }
                type Entry { entries: [Entry!]! }
            """.trimIndent().asViaductSchema

            val arb = Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "entries",
                selections = schema.mkEngineSelectionSet("Entry", "entries { __typename }"),
                ctx = MockEngineCtx(),
                cfg = Config.default +
                    (IncludeRequiredResolvers to false) +
                    (ExplicitNullValueWeight to 0.0) +
                    (ListValueSize to 1.asIntRange()) +
                    (MaxValueDepth to 1),
            )
            arb.checkAll { v ->
                v as List<*>
                assertEquals(emptyList<Any>(), v)
            }
        }

    @Test
    fun `SelectedTypeBias -- interface`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { entry: Node! }
                type Foo implements Node { id:ID! }
                type Bar implements Node { id:ID! }
            """.asViaductSchema

            val ss = schema.mkEngineSelectionSet("Node", "... on Foo { id }")

            // disabled
            run {
                val arb = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "entry",
                    selections = ss,
                    ctx = MockEngineCtx(),
                    selective = false,
                    cfg = Config.default + (SelectedTypeBias to 0.0),
                )

                assertTrue(
                    arb.asSequence().any { value -> (value as EngineObjectData).type.name == "Bar" }
                )
            }

            // enabled
            Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "entry",
                selections = ss,
                ctx = MockEngineCtx(),
                selective = false,
                cfg = Config.default + (SelectedTypeBias to 1.0),
            ).forAll { value ->
                value as EngineObjectData
                value.type.name == "Foo"
            }
        }

    @Test
    fun `SelectedTypeBias -- union`(): Unit =
        runBlocking {
            val schema = """
                extend type Query { entry:Union! }
                union Union = Foo | Bar
                type Foo { id:ID! }
                type Bar { id:ID! }
            """.asViaductSchema

            val ss = schema.mkEngineSelectionSet("Union", "... on Foo { id }")

            // disabled
            run {
                val arb = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "entry",
                    selections = ss,
                    ctx = MockEngineCtx(),
                    selective = false,
                    cfg = Config.default + (SelectedTypeBias to 0.0),
                )

                assertTrue(
                    arb.asSequence().any { value -> (value as EngineObjectData).type.name == "Bar" }
                )
            }

            // enabled
            Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "entry",
                selections = ss,
                ctx = MockEngineCtx(),
                selective = false,
                cfg = Config.default + (SelectedTypeBias to 1.0),
            ).forAll { value ->
                value as EngineObjectData
                value.type.name == "Foo"
            }
        }

    @Test
    fun `SelectedTypeBias -- empty`(): Unit =
        runBlocking {
            // ensure that when SelectedTypeBias is enabled but no types are selected, that
            // the generator can still return a value
            val schema = """
                extend type Query { entry:Union! }
                union Union = Foo | Bar
                type Foo { id:ID! }
                type Bar { id:ID! }
            """.asViaductSchema

            val ss = schema.mkEngineSelectionSet("Union", "__typename")

            Arb.fieldResolverValue(
                schema = schema,
                coord = "Query" to "entry",
                selections = ss,
                ctx = MockEngineCtx(),
                selective = false,
                cfg = Config.default + (SelectedTypeBias to 1.0),
            ).forAll { value ->
                value as EngineObjectData
                value.type.name in setOf("Foo", "Bar")
            }
        }

    @Test
    fun `SelectedTypeBias -- subset stability`(): Unit =
        runBlocking {
            // ensure that for a fixed seed, selective values are always a subset of non-selective values
            // This ensures that the value that is generated for any individual selection does
            // not depend on values generated for previous selections

            val schema = """
                extend type Query { entry:Union! }
                union Union = Foo | Bar
                type Foo { id:ID!, bar:Bar, x:Int }
                type Bar { id:ID!, x:Int }
            """.asViaductSchema
            val cfg = Config.default + (SelectedTypeBias to 1.0)

            val arb = arbitrary {
                val seed = Arb.long().bind()

                val nonSelective = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "entry",
                    selections = schema.mkEngineSelectionSet(
                        "Union",
                        """
                            ... on Foo { id, bar { id, x }, x }
                            ... on Bar { id, x }
                        """.trimIndent(),
                    ),
                    ctx = MockEngineCtx(),
                    selective = false,
                    cfg = cfg
                ).next(RandomSource.seeded(seed))

                val selective = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "entry",
                    selections = schema.mkEngineSelectionSet(
                        "Union",
                        """
                          ... on Bar { x },
                          ... on Foo { bar { x }, x }
                        """.trimIndent()
                    ),
                    ctx = MockEngineCtx(),
                    selective = true,
                    cfg = cfg
                ).next(RandomSource.seeded(seed))

                nonSelective to selective
            }

            arb.checkAll { (nonSelective, selective) ->
                assertTrue(isSubset(nonSelective, selective)) {
                    """
                        Selective value is not a subset of the non-selective value
                        selective value: $selective
                        non-selective value: $nonSelective
                    """.trimIndent()
                }
            }
        }

    @Nested
    inner class FieldRefs {
        @Test
        fun `simple`() {
            runBlocking {
                val schema = """
                    | extend type Query { ns:Ns, obj:Obj! }
                    | type Ns @namespaceType { obj:Obj! }
                    | type Obj { x:Int }
                """.trimMargin().asViaductSchema

                val arb = arbitrary { rs ->
                    val weight = Arb.of(0.0, 1.0).bind()
                    val cfg = Config.default + (ResolverFieldRefWeight to weight)
                    val env = ViaductGenEnv(schema, cfg, rs)

                    val value = env.fieldResolverValueGen.gen(
                        coord = "Query" to "obj",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Obj", "x"),
                        ctx = MockEngineCtx(schema),
                    )
                    Triple(weight, env.coordinateIndex, value)
                }

                arb.forAll { (weight, coordinateIndex, value) ->
                    val refIsAllowed =
                        coordinateIndex.comparator.compare("Ns" to "obj", "Query" to "obj") < 0
                    (value is RootFieldReference) == (weight == 1.0 && refIsAllowed)
                }
            }
        }

        @Test
        fun `no self-references`() {
            runBlocking {
                val schema = """
                  | extend type Query { obj:Obj! }
                  | type Obj { x:Int }
                """.trimMargin().asViaductSchema

                val arb = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "obj",
                    selections = schema.mkEngineSelectionSet("Obj", "x"),
                    ctx = MockEngineCtx(schema),
                    cfg = Config.default + (ResolverFieldRefWeight to 1.0)
                )

                arb.checkAll { value ->
                    assertTrue(value !is RootFieldReference) {
                        value.toString()
                    }
                }
            }
        }

        @Test
        fun `argumented fields`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, obj:Obj! }
                  | input Inp { x:Int, y:[Int], inp:Inp }
                  | type Ns @namespaceType { obj(inp:Inp!):Obj! }
                  | type Obj { x:Int }
                """.trimMargin().asViaductSchema

                val cfg = Config.default + (ResolverFieldRefWeight to 1.0)
                arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = "Query" to "obj",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Obj", "x"),
                        ctx = MockEngineCtx(schema)
                    )
                    env.coordinateIndex to value
                }.checkAll { (coordinateIndex, value) ->
                    val refIsAllowed =
                        coordinateIndex.comparator.compare("Ns" to "obj", "Query" to "obj") < 0
                    if (refIsAllowed) {
                        assertTrue(value is RootFieldReference && value.args["inp"] is Map<*, *>) {
                            value.toString()
                        }
                    } else {
                        assertTrue(value !is RootFieldReference)
                    }
                }
            }
        }

        @Test
        fun `nullable position can reference nullable root`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, obj:Obj }
                  | type Ns @namespaceType { obj:Obj }
                  | type Obj { x:Int }
                """.trimMargin().asViaductSchema

                val cfg = Config.default +
                    (ResolverFieldRefWeight to 1.0) +
                    (ExplicitNullValueWeight to 0.0)
                val arb = arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = "Query" to "obj",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Obj", "x"),
                        ctx = MockEngineCtx(schema)
                    )
                    env.coordinateIndex to value
                }

                arb
                    .mapNotNull { (index, value) ->
                        value.takeIf { index.comparator.compare("Ns" to "obj", "Query" to "obj") < 0 }
                    }
                    .forAll { it is RootFieldReference }
            }
        }

        @Test
        fun `nullable position can reference non-nullable root`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, obj:Obj }
                  | type Ns @namespaceType { obj:Obj! }
                  | type Obj { x:Int }
                """.trimMargin().asViaductSchema

                val cfg = Config.default +
                    (ResolverFieldRefWeight to 1.0) +
                    (ExplicitNullValueWeight to 0.0)
                val arb = arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = "Query" to "obj",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Obj", "x"),
                        ctx = MockEngineCtx(schema)
                    )
                    env.coordinateIndex to value
                }

                arb
                    .mapNotNull { (index, value) ->
                        value.takeIf { index.comparator.compare("Ns" to "obj", "Query" to "obj") < 0 }
                    }
                    .forAll { it is RootFieldReference }
            }
        }

        @Test
        fun `non-null position cannot reference nullable root`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, obj:Obj! }
                  | type Ns @namespaceType { obj:Obj }
                  | type Obj { x:Int }
                """.trimMargin().asViaductSchema

                val cfg = Config.default +
                    (ResolverFieldRefWeight to 1.0) +
                    (ExplicitNullValueWeight to 0.0)
                arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    env.fieldResolverValueGen.gen(
                        coord = "Query" to "obj",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Obj", "x"),
                        ctx = MockEngineCtx(schema)
                    )
                }.forAll { value ->
                    value !is RootFieldReference
                }
            }
        }

        @Test
        fun `mixed unions`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, u:U! }
                  | union U = Foo | Bar
                  | type Ns @namespaceType { foo:Foo! }
                  | type Foo { x:Int }
                  | type Bar { x:Int }
                """.trimMargin().asViaductSchema

                val arb = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "u",
                    selections = schema.mkEngineSelectionSet("U", "__typename"),
                    ctx = MockEngineCtx(schema),
                    cfg = Config.default + (ResolverFieldRefWeight to 1.0)
                )

                val values = arb.take(200).toList()

                // check that only Foo is generated as a reference
                values.filterIsInstance<RootFieldReference>().forEach {
                    assertEquals("Foo", it.type.name) {
                        it.toString()
                    }
                }

                // Foo branch
                values.firstOrNull { it is RootFieldReference && it.type.name == "Foo" }.shouldNotBeNull()

                // Bar branch
                values.firstOrNull { it is EngineObjectData && it.type.name == "Bar" }.shouldNotBeNull()
            }
        }

        @Test
        fun `multiple providers`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns1:Ns1, ns2:Ns2, foo:Foo! }
                  | type Ns1 @namespaceType { f1:Foo! }
                  | type Ns2 @namespaceType { f2:Foo! }
                  | type Foo { x:Int }
                """.trimMargin().asViaductSchema

                val arb = Arb.fieldResolverValue(
                    schema = schema,
                    coord = "Query" to "foo",
                    selections = schema.mkEngineSelectionSet("Foo", "x"),
                    ctx = MockEngineCtx(schema),
                    cfg = Config.default + (ResolverFieldRefWeight to 1.0)
                )

                // ns1 branch
                arb.take(100)
                    .firstOrNull { it is RootFieldReference && it.rootFieldPath == listOf("ns1", "f1") }
                    .shouldNotBeNull()

                // ns2 branch
                arb.take(100)
                    .firstOrNull { it is RootFieldReference && it.rootFieldPath == listOf("ns2", "f2") }
                    .shouldNotBeNull()
            }
        }

        @Test
        fun `ref used in list context`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, foo:[Foo!]! }
                  | type Ns @namespaceType { foo:Foo! }
                  | type Foo { x:Int }
                """.trimMargin().asViaductSchema

                val cfg = Config.default +
                    (ResolverFieldRefWeight to 1.0) +
                    (ListValueSize to 2..2)
                arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = "Query" to "foo",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Foo", "x"),
                        ctx = MockEngineCtx(schema)
                    )
                    env.coordinateIndex to value
                }.forAll { (coordinateIndex, value) ->
                    val refIsAllowed =
                        coordinateIndex.comparator.compare("Ns" to "foo", "Query" to "foo") < 0
                    value is List<*> &&
                        value.size == 2 &&
                        value.all { (it is RootFieldReference) == refIsAllowed }
                }
            }
        }

        @Test
        fun `cycle avoidance`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, wrapper:Wrapper! }
                  | type Ns @namespaceType { foo:Foo! }
                  | type Wrapper { foo:Foo! }
                  | type Foo { x:Int }
                """.trimMargin().asViaductSchema
                val resolverCoordinate = "Query" to "wrapper"
                val refCoordinate = "Ns" to "foo"
                val cfg = Config.default + (ResolverFieldRefWeight to 1.0)

                arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = resolverCoordinate,
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Wrapper", "foo { x }"),
                        ctx = MockEngineCtx(schema)
                    ) as EngineObjectData

                    env.coordinateIndex to value
                }.forAll { (coordinateIndex, data) ->
                    val value = data.fetch("foo")
                    val refIsAllowed =
                        coordinateIndex.comparator.compare(refCoordinate, resolverCoordinate) < 0
                    (value is RootFieldReference) == refIsAllowed
                }
            }
        }

        @Test
        fun `node ref`() {
            runBlocking {
                val schema = """
                  | extend type Query { ns:Ns, foo:Foo! }
                  | type Ns @namespaceType { foo:Foo! }
                  | type Foo implements Node @resolver { id:ID! }
                """.trimMargin().asViaductSchema

                val cfg = Config.default + (ResolverFieldRefWeight to 1.0)
                arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = "Query" to "foo",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Foo", "id"),
                        ctx = MockEngineCtx(schema)
                    )
                    env.coordinateIndex to value
                }.forAll { (coordinateIndex, value) ->
                    val rootRefIsAllowed =
                        coordinateIndex.comparator.compare("Ns" to "foo", "Query" to "foo") < 0
                    val nodeRefIsAllowed =
                        coordinateIndex.comparator.compare("Foo" to null, "Query" to "foo") < 0
                    when {
                        rootRefIsAllowed ->
                            value is RootFieldReference &&
                                value.rootFieldPath == listOf("ns", "foo")
                        nodeRefIsAllowed -> value is NodeReference
                        else -> value is EngineObjectData
                    }
                }

                arbitrary { rs ->
                    val env = ViaductGenEnv(schema, cfg, rs)
                    val value = env.fieldResolverValueGen.gen(
                        coord = "Ns" to "foo",
                        selective = false,
                        selections = schema.mkEngineSelectionSet("Foo", "id"),
                        ctx = MockEngineCtx(schema)
                    )
                    env.coordinateIndex to value
                }.forAll { (coordinateIndex, value) ->
                    val rootRefIsAllowed =
                        coordinateIndex.comparator.compare("Query" to "foo", "Ns" to "foo") < 0
                    val nodeRefIsAllowed =
                        coordinateIndex.comparator.compare("Foo" to null, "Ns" to "foo") < 0
                    when {
                        rootRefIsAllowed ->
                            value is RootFieldReference && value.rootFieldPath == listOf("foo")
                        nodeRefIsAllowed -> value is NodeReference
                        else -> value is EngineObjectData
                    }
                }
            }
        }
    }

    private fun isSubset(
        superset: Any?,
        subset: Any?
    ): Boolean =
        when (subset) {
            null -> superset == null
            is List<*> ->
                superset is List<*> &&
                    subset.size == superset.size &&
                    superset.zip(subset).all { isSubset(it.first, it.second) }
            is EngineObjectData -> {
                runBlocking {
                    superset is EngineObjectData &&
                        superset.type == subset.type &&
                        subset.fetchSelections().all { key ->
                            val subValue = subset.fetch(key)
                            val superValue = superset.fetch(key)
                            isSubset(superValue, subValue)
                        }
                }
            }
            else -> superset == subset
        }
}
