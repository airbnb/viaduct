@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.Scalars.GraphQLID
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.Coordinate
import viaduct.engine.api.gj
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class IDValueGenTest : KotestPropertyBase() {
    private val codec = GlobalIDCodecDefault
    private val schema = """
        type Foo implements Node { id:ID! }
        type Bar implements Node { id:ID! }
        type Obj {
            fooId:ID @idOf(type:"Foo")
            barIds:[ID] @idOf(type:"Bar")
            anyNodeId:ID @idOf(type:"Node")
            anyId:ID
        }
        input Inp {
            fooId:ID @idOf(type:"Foo")
            anyNodeId:ID @idOf(type:"Node")
            anyId:ID
        }
    """.asViaductSchema

    private fun obj(name: String): GraphQLObjectType = schema.schema.getObjectType(name)

    private fun input(name: String): GraphQLInputObjectType = schema.schema.getTypeAs(name)

    private fun field(coord: Coordinate): GraphQLFieldDefinition = schema.schema.getFieldDefinition(coord.gj)

    private fun inputField(coord: Coordinate): GraphQLInputObjectField =
        schema.schema.getTypeAs<GraphQLInputObjectType>(coord.first)
            .getFieldDefinition(coord.second)

    private fun mkParams(
        cfg: Config,
        rs: RandomSource
    ): IDValueGen.Factory.Params = IDValueGen.Factory.Params(schema, cfg, rs)

    @Test
    fun `arbString -- StringValueSize`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val size = Arb.int(1, 10).bind()
                val gen = IDValueGen.Factory.ArbString(
                    mkParams(
                        Config.default + (StringValueSize to size.asIntRange()),
                        rs
                    )
                )
                val value = gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = field("Foo" to "id"),
                        fieldParent = obj("Foo"),
                    )
                )
                size to value.value
            }

            arb.forAll { (size, value) ->
                value.length == size
            }
        }

    @Test
    fun `globalID -- StringValueSize`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val size = Arb.int(1, 10).bind()
                val gen = IDValueGen.Factory.default(
                    mkParams(
                        Config.default + (StringValueSize to size.asIntRange()),
                        rs
                    )
                )
                val value = gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = field("Foo" to "id"),
                        fieldParent = obj("Foo")
                    )
                )
                size to value.value
            }

            arb.forAll { (size, value) ->
                size == codec.deserialize(value).localID.length
            }
        }

    @Test
    fun `globalID -- node impl id field`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = field("Foo" to "id"),
                        fieldParent = obj("Foo"),
                    )
                ).value
            }

            arb.forAll { id ->
                codec.deserialize(id).typeName == "Foo"
            }
        }

    @Test
    fun `globalID -- object @idOf field`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = field("Obj" to "fooId"),
                        fieldParent = obj("Obj"),
                    )
                ).value
            }

            arb.forAll { id ->
                codec.deserialize(id).typeName == "Foo"
            }
        }

    @Test
    fun `globalID -- object list-typed @idOf`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = field("Obj" to "barIds"),
                        fieldParent = obj("Obj"),
                    )
                ).value
            }

            arb.forAll { id ->
                codec.deserialize(id).typeName == "Bar"
            }
        }

    @Test
    fun `globalID -- object unconstrained @idOf`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = field("Obj" to "anyNodeId"),
                        fieldParent = obj("Obj"),
                    )
                ).value
            }

            arb.forAll { id ->
                val type = codec.deserialize(id).typeName
                type == "Foo" || type == "Bar"
            }
        }

    @Test
    fun `globalID -- object unconstrained ID`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                List(10) {
                    gen.gen(
                        TypeCtx(
                            type = GraphQLID,
                            field = field("Obj" to "anyId"),
                            fieldParent = obj("Obj"),
                        )
                    ).value
                }
            }

            assertTrue(
                arb.asSequence()
                    .take(iterations)
                    .any { ids ->
                        val types = ids.map { codec.deserialize(it).typeName }.toSet()
                        "Foo" in types && "Bar" in types
                    }
            )
        }

    @Test
    fun `globalID -- input object @idOf field`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = inputField("Inp" to "fooId"),
                        fieldParent = input("Inp"),
                    )
                ).value
            }

            arb.forAll { id ->
                codec.deserialize(id).typeName == "Foo"
            }
        }

    @Test
    fun `globalID -- input object unconstrained @idOf`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = inputField("Inp" to "anyNodeId"),
                        fieldParent = input("Inp"),
                    )
                ).value
            }

            arb.forAll { id ->
                val type = codec.deserialize(id).typeName
                type == "Foo" || type == "Bar"
            }
        }

    @Test
    fun `globalID -- input object unconstrained ID`(): Unit =
        runBlocking {
            val arb = arbitrary { rs ->
                val gen = IDValueGen.Factory.default(mkParams(Config.default, rs))
                gen.gen(
                    TypeCtx(
                        type = GraphQLID,
                        field = inputField("Inp" to "anyId"),
                        fieldParent = input("Inp"),
                    )
                ).value
            }

            arb.forAll { id ->
                val type = codec.deserialize(id).typeName
                type == "Foo" || type == "Bar"
            }
        }
}
