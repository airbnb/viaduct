@file:Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "ForbiddenImport")

package viaduct.mapping.graphql

import graphql.Scalars
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.forAll
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.asSequence
import viaduct.arbitrary.graphql.ExplicitNullValueWeight
import viaduct.arbitrary.graphql.ImplicitNullValueWeight
import viaduct.arbitrary.graphql.ListValueSize
import viaduct.arbitrary.graphql.MaxValueDepth
import viaduct.arbitrary.graphql.asIntRange
import viaduct.mapping.test.IntrospectionObjectValueWeight
import viaduct.mapping.test.OutputObjectValueWeight
import viaduct.mapping.test.inputObjectIR
import viaduct.mapping.test.ir
import viaduct.mapping.test.objectIR
import viaduct.mapping.test.outputObjectIR

class ArbIRTest : KotestPropertyBase() {
    @Disabled("https://app.asana.com/1/150975571430/project/1211295233988904/task/1211525978501301")
    @Test
    fun `generates scalar values -- BackingData`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.getType("BackingData").nonNullable)
                .forAll { false }
        }

    @Test
    fun `generates scalar values -- Boolean`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, Scalars.GraphQLBoolean.nonNullable)
                .forAll { it is IR.Value.Boolean }
        }

    @Test
    fun `generates scalar values -- Byte`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.scalar("Byte").nonNullable)
                .forAll { it is IR.Value.Number && it.value is Byte }
        }

    @Test
    fun `generates scalar values -- Date`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.scalar("Date").nonNullable)
                .forAll { it is IR.Value.Time && it.value is LocalDate }
        }

    @Test
    fun `generates scalar values -- DateTime`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.scalar("DateTime").nonNullable)
                .forAll { it is IR.Value.Time && it.value is Instant }
        }

    @Test
    fun `generates scalar values -- Float`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, Scalars.GraphQLFloat.nonNullable)
                .forAll { it is IR.Value.Number && it.value is Double }
        }

    @Test
    fun `generates scalar values -- ID`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, Scalars.GraphQLID.nonNullable)
                .forAll { it is IR.Value.String }
        }

    @Test
    fun `generates scalar values -- Int`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, Scalars.GraphQLInt.nonNullable)
                .forAll { it is IR.Value.Number && it.value is Int }
        }

    @Disabled("https://app.asana.com/1/150975571430/project/1211295233988904/task/1211549203021234")
    @Test
    fun `generates scalar values -- JSON`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.scalar("JSON").nonNullable)
                .forAll { false }
        }

    @Test
    fun `generates scalar values -- Short`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.scalar("Short").nonNullable)
                .forAll { it is IR.Value.Number && it.value is Short }
        }

    @Test
    fun `generates scalar values -- String`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, Scalars.GraphQLString.nonNullable)
                .forAll { it is IR.Value.String }
        }

    @Test
    fun `generates scalar values -- Time`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, emptySchema.scalar("Time").nonNullable)
                .forAll { it is IR.Value.Time && it.value is OffsetTime }
        }

    @Test
    fun `does not generate unsupported scalar values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                scalar UnsupportedScalar
                type Query { placeholder:Int }
                """.trimIndent()
            )
            val arb = arbitrary { rs ->
                runCatching {
                    Arb.ir(schema, schema.scalar("UnsupportedScalar").nonNullable)
                        .next(rs)
                }
            }
            arb.forAll { result ->
                result.exceptionOrNull() is UnsupportedOperationException
            }
        }

    @Test
    fun `generates list values`(): Unit =
        runBlocking {
            Arb.ir(emptySchema, GraphQLList.list(Scalars.GraphQLInt).nonNullable)
                .forAll { it is IR.Value.List }
        }

    @Test
    fun `generates list values -- ListValueSize`(): Unit =
        runBlocking {
            Arb.int(0..100)
                .flatMap { listSize ->
                    val cfg = Config.default +
                        (ListValueSize to listSize.asIntRange()) +
                        (ExplicitNullValueWeight to 0.0)
                    Arb.ir(emptySchema, GraphQLList.list(Scalars.GraphQLInt), cfg)
                        .map { listSize to it }
                }.forAll { (listSize, ir) ->
                    ir is IR.Value.List && ir.value.size == listSize
                }
        }

    @Test
    fun `generates list values -- nested list values`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (ListValueSize to 2.asIntRange()) +
                (ExplicitNullValueWeight to 0.0)

            val type = GraphQLList.list(GraphQLList.list(Scalars.GraphQLInt))
            Arb.ir(emptySchema, type, cfg)
                .forAll { v ->
                    if (v !is IR.Value.List) return@forAll false
                    if (v.value.size != 2) return@forAll false
                    if (!v.value.all { it is IR.Value.List }) return@forAll false
                    val flattenedItems = v.value.flatMap { (it as IR.Value.List).value }
                    if (flattenedItems.size != 4) return@forAll false
                    if (!flattenedItems.all { it is IR.Value.Number }) return@forAll false
                    true
                }
        }

    @Test
    fun `generates list values -- MaxValueDepth`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (ListValueSize to 1.asIntRange()) +
                (ExplicitNullValueWeight to 0.0) +
                (MaxValueDepth to 0)

            val type = GraphQLList.list(GraphQLList.list(Scalars.GraphQLInt))
            Arb.ir(emptySchema, type, cfg)
                .forAll { it is IR.Value.List && it.value.isEmpty() }
        }

    @Test
    fun `generates nullable values -- ExplicitNullValueWeight`(): Unit =
        runBlocking {
            // never null
            Arb.ir(emptySchema, Scalars.GraphQLInt, Config.default + (ExplicitNullValueWeight to 0.0))
                .forAll { it !is IR.Value.Null }

            // always null
            Arb.ir(emptySchema, Scalars.GraphQLInt, Config.default + (ExplicitNullValueWeight to 1.0))
                .forAll { it is IR.Value.Null }
        }

    @Test
    fun `generates interface values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                interface I {x:Int}
                type A implements I {x:Int}
                type Query { empty:Int }
                """.trimIndent()
            )
            Arb.ir(schema, schema.getType("I").nonNullable).forAll {
                it is IR.Value.Object && it.name == "A"
            }
        }

    @Test
    fun `generates union values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                type A { x:Int }
                union U = A
                type Query { empty:Int }
                """.trimIndent()
            )
            Arb.ir(schema, schema.getType("U").nonNullable).forAll {
                it is IR.Value.Object && it.name == "A"
            }
        }

    @Test
    fun `generates enum values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                enum E { A, B, C }
                type Query { placeholder: Int }
                """.trimIndent()
            )
            Arb.ir(schema, schema.getType("E").nonNullable)
                .forAll { it is IR.Value.String && it.value in setOf("A", "B", "C") }
        }

    @Test
    fun `generates object values`(): Unit =
        runBlocking {
            val schema = mkSchema("type Query {x:Int, y:Int}")
            Arb.ir(schema, schema.queryType.nonNullable)
                .forAll {
                    it is IR.Value.Object &&
                        it.name == "Query" &&
                        setOf("x", "y").containsAll(it.fields.keys)
                }
        }

    @Test
    fun `generates object values -- nested objects`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                type O { x:Int! }
                type Query { o:O! }
                """.trimIndent()
            )
            val cfg = Config.default + (ImplicitNullValueWeight to 0.0)
            Arb.ir(schema, schema.queryType.nonNullable, cfg)
                .forAll {
                    it is IR.Value.Object &&
                        (it.fields["o"] as? IR.Value.Object)?.name == "O"
                }
        }

    @Test
    fun `generates object values -- non-nullable object cycles can be generated without ImplicitNullValueWeight`(): Unit =
        runBlocking {
            val schema = mkSchema("type Query { q:Query! }")

            // even with ImplicitNullValueWeight set to 0, MaxValueDepth should prevent infinite objects
            // It is sufficient to check that this function returns at all.
            val cfg = Config.default + (ImplicitNullValueWeight to 0.0) + (MaxValueDepth to 2)
            Arb.ir(schema, schema.queryType.nonNullable, cfg)
                .forAll { it is IR.Value.Object }
        }

    @Test
    fun `generates object values -- non-nullable object cycles can be generated with ImplicitNullValueWeight`(): Unit =
        runBlocking {
            val schema = mkSchema("type Query { q:Query! }")

            // Even with a high MaxValueDepth, a high ImplicitNullValueWeight should allow
            // non-nullable object cycles to be generated in a reasonable amount of time
            val cfg = Config.default + (ImplicitNullValueWeight to .8) + (MaxValueDepth to 10_000)
            Arb.ir(schema, schema.queryType.nonNullable, cfg)
                .forAll { it is IR.Value.Object }
        }

    @Test
    fun `generates introspection objects`(): Unit =
        runBlocking {
            // introspection value generation enabled
            Arb.objectIR(emptySchema, cfg = Config.default + (IntrospectionObjectValueWeight to 1.0))
                .asSequence(randomSource)
                .take(10_000)
                .any { it.name.startsWith("__") }
                .let(::assertTrue)

            // introspection value generation disabled
            Arb.objectIR(emptySchema, cfg = Config.default + (IntrospectionObjectValueWeight to 0.0))
                .asSequence(randomSource)
                .take(10_000)
                .all { !it.name.startsWith("__") }
                .let(::assertTrue)
        }

    @Test
    fun `generates input object values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inp { x:Int }
                type Query { x:Int }
                """.trimIndent()
            )
            val cfg = Config.default + (OutputObjectValueWeight to 0.0)
            Arb.ir(schema, schema.getType("Inp").nonNullable, cfg)
                .forAll { it is IR.Value.Object && it.name == "Inp" }
        }

    @Test
    fun `generates input object values -- nested input objects`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inner { x:Int }
                input Inp { inner:Inner! }
                type Query { x:Int }
                """.trimIndent()
            )
            Arb.ir(schema, schema.getType("Inp").nonNullable).forAll {
                val fieldValue = (it as IR.Value.Object).fields["inner"] as? IR.Value.Object
                fieldValue?.name == "Inner"
            }
        }

    @Test
    fun `generates input object values -- cyclic input objects`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inp { inp:Inp }
                type Query { x:Int }
                """.trimIndent()
            )

            // ensure that the default configuration can generate an input object in a
            // reasonable amount of time
            Arb.ir(schema, schema.getType("Inp").nonNullable, Config.default)
                .forAll { it is IR.Value.Object }
        }

    @Test
    fun `generates input object values -- unset fields when field has default value`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inp { x:Int! = 0 }
                type Query { x:Int }
                """.trimIndent()
            )

            // When ImplicitNullValueWeight is 0.0, expect that a field value will always be set
            Arb.inputObjectIR(schema, Config.default + (ImplicitNullValueWeight to 0.0))
                .forAll { "x" in it.fields }

            // When ImplicitNullValueWeight is 1.0, expect that a field value will never be set
            Arb.inputObjectIR(schema, Config.default + (ImplicitNullValueWeight to 1.0))
                .forAll { it.fields.isEmpty() }
        }

    @Test
    fun `Arb_objectIR -- returns a mix of input and output objects`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inp {x:Int}
                type Query {x:Int}
                """.trimIndent()
            )
            val seq = Arb.objectIR(schema)
                .asSequence(randomSource)
                .take(1_000)
                .toList()

            val hasOutputObject = seq.any { schema.getType(it.name) is GraphQLObjectType }
            val hasInputObject = seq.any { schema.getType(it.name) is GraphQLInputObjectType }
            assertTrue(hasOutputObject && hasInputObject)
        }

    @Test
    fun `Arb_outputObjectIR -- returns output object values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inp {x:Int}
                type Query {x:Int}
                """.trimIndent()
            )

            Arb.outputObjectIR(schema)
                .forAll { schema.getType(it.name) is GraphQLObjectType }
        }

    @Test
    fun `Arb_inputObjectIR -- returns input object values`(): Unit =
        runBlocking {
            val schema = mkSchema(
                """
                input Inp {x:Int}
                type Query {x:Int}
                """.trimIndent()
            )

            Arb.inputObjectIR(schema)
                .forAll { schema.getType(it.name) is GraphQLInputObjectType }
        }
}

internal val GraphQLType.nonNullable: GraphQLType get() =
    if (this is GraphQLNonNull) {
        this
    } else {
        GraphQLNonNull.nonNull(this)
    }

internal fun GraphQLSchema.scalar(name: String): GraphQLScalarType = getTypeAs(name)

internal fun mkSchema(sdl: String): GraphQLSchema =
    UnExecutableSchemaGenerator.makeUnExecutableSchema(
        SchemaParser().parse(sdl)
    )

internal val emptySchema = mkSchema(
    """
            scalar BackingData
            scalar Byte
            scalar Date
            scalar DateTime
            scalar JSON
            scalar Long
            scalar Short
            scalar Time
            type Query { placeholder:Int }
    """.trimIndent()
)
