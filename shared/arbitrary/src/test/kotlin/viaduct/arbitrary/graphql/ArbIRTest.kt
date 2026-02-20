@file:Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.Scalars
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.forAll
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.asSequence
import viaduct.engine.api.ViaductSchema
import viaduct.mapping.graphql.IR

class ArbIRTest : KotestPropertyBase() {
    @Test
    fun `generates scalar values -- BackingData`(): Unit =
        runBlocking {
            // There's not a straightforward way to generate values for BackingData types.
            // For now, ensure that we always generate null
            Arb
                .ir(emptySchema, emptySchema.schema.getType("BackingData"))
                .forAll { it == IR.Value.Null }
        }

    @Test
    fun `generates scalar values -- Boolean`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, Scalars.GraphQLBoolean.nonNullable)
                .forAll { it is IR.Value.Boolean }
        }

    @Test
    fun `generates scalar values -- Byte`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, emptySchema.schema.scalar("Byte").nonNullable)
                .forAll { it is IR.Value.Number && it.value is Byte }
        }

    @Test
    fun `generates scalar values -- Date`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, emptySchema.schema.scalar("Date").nonNullable)
                .forAll { it is IR.Value.Time && it.value is LocalDate }
        }

    @Test
    fun `generates scalar values -- DateTime`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, emptySchema.schema.scalar("DateTime").nonNullable)
                .forAll { it is IR.Value.Time && it.value is Instant }
        }

    @Test
    fun `generates scalar values -- Float`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, Scalars.GraphQLFloat.nonNullable)
                .forAll { it is IR.Value.Number && it.value is Double }
        }

    @Test
    fun `generates scalar values -- ID`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, Scalars.GraphQLID.nonNullable)
                .forAll { it is IR.Value.String }
        }

    @Test
    fun `generates scalar values -- Int`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, Scalars.GraphQLInt.nonNullable)
                .forAll { it is IR.Value.Number && it.value is Int }
        }

    @Disabled("https://app.asana.com/1/150975571430/project/1211295233988904/task/1211549203021234")
    @Test
    fun `generates scalar values -- JSON`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, emptySchema.schema.scalar("JSON").nonNullable)
                .forAll { false }
        }

    @Test
    fun `generates scalar values -- Short`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, emptySchema.schema.scalar("Short").nonNullable)
                .forAll { it is IR.Value.Number && it.value is Short }
        }

    @Test
    fun `generates scalar values -- String`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, Scalars.GraphQLString.nonNullable)
                .forAll { it is IR.Value.String }
        }

    @Test
    fun `generates scalar values -- Time`(): Unit =
        runBlocking {
            Arb
                .ir(emptySchema, emptySchema.schema.scalar("Time").nonNullable)
                .forAll { it is IR.Value.Time && it.value is OffsetTime }
        }

    @Test
    fun `generates scalar values -- ScalarValueOverrides`(): Unit =
        runBlocking {
            val value = IR.Value.Number(1)
            val overrides = mapOf("Int" to Arb.constant(value))
            val cfg = Config.default + (ScalarValueOverrides to overrides)

            Arb
                .ir(emptySchema, emptySchema.schema.scalar("Int").nonNullable, cfg)
                .forAll { it == value }
        }

    @Test
    fun `does not generate unsupported scalar values`(): Unit =
        runBlocking {
            val schema = ViaductSchema(
                """
                    scalar UnsupportedScalar
                    type Query { x: UnsupportedScalar }
                """.trimIndent().asSchema
            )
            val arb = arbitrary { rs ->
                runCatching {
                    Arb
                        .ir(schema, schema.schema.scalar("UnsupportedScalar").nonNullable)
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
            Arb
                .ir(emptySchema, GraphQLList.list(Scalars.GraphQLInt).nonNullable)
                .forAll { it is IR.Value.List }
        }

    @Test
    fun `generates list values -- ListValueSize`(): Unit =
        runBlocking {
            Arb
                .int(0..100)
                .flatMap { listSize ->
                    val cfg = Config.default +
                        (ListValueSize to listSize.asIntRange()) +
                        (ExplicitNullValueWeight to 0.0)
                    Arb
                        .ir(emptySchema, GraphQLList.list(Scalars.GraphQLInt), cfg)
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
            Arb
                .ir(emptySchema, type, cfg)
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
            Arb
                .ir(emptySchema, type, cfg)
                .forAll { it is IR.Value.List && it.value.isEmpty() }
        }

    @Test
    fun `generates nullable values -- ExplicitNullValueWeight`(): Unit =
        runBlocking {
            // never null
            Arb
                .ir(emptySchema, Scalars.GraphQLInt, Config.default + (ExplicitNullValueWeight to 0.0))
                .forAll { it !is IR.Value.Null }

            // always null
            Arb
                .ir(emptySchema, Scalars.GraphQLInt, Config.default + (ExplicitNullValueWeight to 1.0))
                .forAll { it is IR.Value.Null }
        }

    @Test
    fun `generates interface values`(): Unit =
        runBlocking {
            val schema =
                """
                    interface I {x:Int}
                    type A implements I {x:Int}
                """.asViaductSchema
            Arb.ir(schema, schema.schema.getType("I").nonNullable)
                .forAll {
                    it is IR.Value.Object && it.name == "A"
                }
        }

    @Test
    fun `generates interface values -- no impls`(): Unit =
        runBlocking {
            val schema = "interface I {x:Int}".asViaductSchema
            val type = schema.schema.getType("I").nonNullable

            val arb = arbitrary {
                try {
                    Result.success(Arb.ir(schema, type).bind())
                } catch (err: Exception) {
                    Result.failure(err)
                }
            }

            arb.forAll { result ->
                result.isFailure &&
                    result.exceptionOrNull()!!.message!!.contains("no implementations")
            }
        }

    @Test
    fun `generates union values`(): Unit =
        runBlocking {
            val schema =
                """
                    type A { x:Int }
                    union U = A
                """.asViaductSchema
            Arb.ir(schema, schema.schema.getType("U").nonNullable).forAll {
                it is IR.Value.Object && it.name == "A"
            }
        }

    @Test
    fun `generates enum values`(): Unit =
        runBlocking {
            val schema = "enum E { A, B, C }".asViaductSchema
            Arb
                .ir(schema, schema.schema.getType("E").nonNullable)
                .forAll { it is IR.Value.String && it.value in setOf("A", "B", "C") }
        }

    @Test
    fun `generates object values`(): Unit =
        runBlocking {
            val schema = "extend type Query {x:Int, y:Int}".asViaductSchema
            Arb
                .ir(schema, schema.schema.queryType.nonNullable)
                .forAll {
                    it is IR.Value.Object &&
                        it.name == "Query" &&
                        setOf("x", "y", "__typename").containsAll(it.fields.keys)
                }
        }

    @Test
    fun `generates object values -- TypenameValueWeight`(): Unit =
        runBlocking {
            val schema = "extend type Query {x:Int}".asViaductSchema
            val arb = arbitrary {
                val genTypename = Arb.boolean().bind()
                val implicitNullValueWeight = Arb.double(0.0, 1.0, includeNonFiniteEdgeCases = false).bind()
                val cfg = Config.default +
                    (ImplicitNullValueWeight to implicitNullValueWeight) +
                    (TypenameValueWeight to (if (genTypename) 1.0 else 0.0))

                val ir = Arb.ir(schema, schema.schema.queryType.nonNullable, cfg).bind()
                ir to genTypename
            }

            arb.forAll { (ir, genTypename) ->
                ir as IR.Value.Object
                if (genTypename) {
                    ir.fields["__typename"] == IR.Value.String("Query")
                } else {
                    "__typename" !in ir.fields
                }
            }
        }

    @Test
    fun `generates object values -- nested objects`(): Unit =
        runBlocking {
            val schema =
                """
                    type O { x:Int! }
                    extend type Query { o:O! }
                """.asViaductSchema
            val cfg = Config.default + (ImplicitNullValueWeight to 0.0)
            Arb
                .ir(schema, schema.schema.queryType.nonNullable, cfg)
                .forAll {
                    it is IR.Value.Object &&
                        (it.fields["o"] as? IR.Value.Object)?.name == "O"
                }
        }

    @Test
    fun `generates object values -- non-nullable object cycles can be generated without ImplicitNullValueWeight`(): Unit =
        runBlocking {
            val schema = "extend type Query { q:Query! }".asViaductSchema

            // even with ImplicitNullValueWeight set to 0, MaxValueDepth should prevent infinite objects
            // It is sufficient to check that this function returns at all.
            val cfg = Config.default + (ImplicitNullValueWeight to 0.0) + (MaxValueDepth to 2)
            Arb
                .ir(schema, schema.schema.queryType.nonNullable, cfg)
                .forAll { it is IR.Value.Object }
        }

    @Test
    fun `generates object values -- non-nullable object cycles can be generated with ImplicitNullValueWeight`(): Unit =
        runBlocking {
            val schema = "extend type Query { q:Query! }".asViaductSchema

            // Even with a high MaxValueDepth, a high ImplicitNullValueWeight should allow
            // non-nullable object cycles to be generated in a reasonable amount of time
            val cfg = Config.default + (ImplicitNullValueWeight to .8) + (MaxValueDepth to 10_000)
            Arb
                .ir(schema, schema.schema.queryType.nonNullable, cfg)
                .forAll { it is IR.Value.Object }
        }

    @Test
    fun `generates introspection objects`(): Unit =
        runBlocking {
            val introspectionTypes = emptySchema.schema.allTypesAsList
                .mapNotNull {
                    when {
                        it is GraphQLObjectType && it.name.startsWith("__") -> it.name
                        else -> null
                    }
                }.toSet()

            // introspection value generation enabled
            Arb
                .objectIR(emptySchema, cfg = Config.default + (IntrospectionObjectValueWeight to 1.0))
                .asSequence(randomSource)
                .take(1_000)
                .toList()
                .mapNotNull { it.name.takeIf { it.startsWith("__") } }
                .let { names ->
                    assertTrue(names.size > 0)
                    assertTrue(names.all { it in introspectionTypes })
                }

            // introspection value generation disabled
            Arb
                .objectIR(emptySchema, cfg = Config.default + (IntrospectionObjectValueWeight to 0.0))
                .forAll { it.name !in introspectionTypes }
        }

    @Test
    fun `generates input object values`(): Unit =
        runBlocking {
            val schema = "input Inp { x:Int }".asViaductSchema
            val cfg = Config.default + (OutputObjectValueWeight to 0.0)
            Arb
                .ir(schema, schema.schema.getType("Inp").nonNullable, cfg)
                .forAll { it is IR.Value.Object && it.name == "Inp" }
        }

    @Test
    fun `generates input object values -- oneof`(): Unit =
        runBlocking {
            val schema = "input Inp @oneOf { x:Int, y:Int }".asViaductSchema
            Arb
                .ir(schema, schema.schema.getType("Inp").nonNullable)
                .forAll {
                    it is IR.Value.Object &&
                        it.name == "Inp" &&
                        it.fields.size == 1 &&
                        it.fields.values.none { it == IR.Value.Null }
                }
        }

    @Test
    fun `generates input object values -- nested input objects`(): Unit =
        runBlocking {
            val schema =
                """
                    input Inner { x:Int }
                    input Inp { inner:Inner! }
                """.asViaductSchema
            Arb.ir(schema, schema.schema.getType("Inp").nonNullable).forAll {
                val fieldValue = (it as IR.Value.Object).fields["inner"] as? IR.Value.Object
                fieldValue?.name == "Inner"
            }
        }

    @Test
    fun `generates input object values -- cyclic input objects`(): Unit =
        runBlocking {
            val schema = "input Inp { inp:Inp }".asViaductSchema
            // ensure that the default configuration can generate an input object in a
            // reasonable amount of time
            Arb
                .ir(schema, schema.schema.getType("Inp").nonNullable, Config.default)
                .forAll { it is IR.Value.Object }
        }

    @Test
    fun `generates input object values -- cyclic input objects with defaults`(): Unit =
        /**
         * NB: this test case will start failing in the next version of graphql-java, after this PR is landed:
         *   https://github.com/graphql-java/graphql-java/pull/4253
         *
         * If this test is failing during a graphql-java upgrade, then it can be removed without replacement,
         * coverage will be handled automatically by the test cases in [GraphQLSchemasTest] that generate
         * arbitrary schemas.
         */
        runBlocking {
            val schema = """
                input Inp { inp:Inp = {} }

                input A { b:B = {} }
                input B { a:A = {} }
            """.asViaductSchema

            val cfg = Config.default + (ImplicitNullValueWeight to 1.0)

            // self-recursing input
            Arb
                .ir(schema, schema.schema.getType("Inp").nonNullable, cfg)
                .forAll { it is IR.Value.Object && "inp" in it.fields }

            // co-recursive inputs
            Arb
                .ir(schema, schema.schema.getType("A").nonNullable, cfg)
                .forAll { it is IR.Value.Object && "b" in it.fields }
            Arb
                .ir(schema, schema.schema.getType("B").nonNullable, cfg)
                .forAll { it is IR.Value.Object && "a" in it.fields }
        }

    @Test
    fun `generates input object values -- unset fields when field has default value`(): Unit =
        runBlocking {
            val schema = "input Inp { x:Int! = 0 }".asViaductSchema

            // When ImplicitNullValueWeight is 0.0, expect that a field value will always be set
            Arb
                .inputObjectIR(schema, Config.default + (ImplicitNullValueWeight to 0.0))
                .forAll { "x" in it.fields }

            // When ImplicitNullValueWeight is 1.0, expect that a field value will never be set
            Arb
                .inputObjectIR(schema, Config.default + (ImplicitNullValueWeight to 1.0))
                .forAll { it.fields.isEmpty() }
        }

    @Test
    fun `Arb_objectIR -- returns a mix of input and output objects`(): Unit =
        runBlocking {
            val schema = "input Inp {x:Int}".asViaductSchema
            val seq = Arb
                .objectIR(schema)
                .asSequence(randomSource)
                .take(1_000)
                .toList()

            val hasOutputObject = seq.any { schema.schema.getType(it.name) is GraphQLObjectType }
            val hasInputObject = seq.any { schema.schema.getType(it.name) is GraphQLInputObjectType }
            assertTrue(hasOutputObject && hasInputObject)
        }

    @Test
    fun `Arb_outputObjectIR -- returns output object values`(): Unit =
        runBlocking {
            val schema = "input Inp {x:Int}".asViaductSchema
            Arb
                .outputObjectIR(schema)
                .forAll {
                    schema.schema.getType(it.name) is GraphQLObjectType
                }
        }

    @Test
    fun `Arb_inputObjectIR -- returns input object values`(): Unit =
        runBlocking {
            val schema = "input Inp {x:Int}".asViaductSchema
            Arb
                .inputObjectIR(schema)
                .forAll {
                    schema.schema.getType(it.name) is GraphQLInputObjectType
                }
        }

    @Test
    fun `mkCyclicInputSCCs -- empty`() {
        assertTrue(mkCyclicInputSCCs(emptySchema.schema).isEmpty())
    }

    @Test
    fun `mkCyclicInputSCCs -- acyclic input types`() {
        val schema = """
            input A { x: Int }
            input B { a: A }
        """.asViaductSchema.schema

        assertTrue(mkCyclicInputSCCs(schema).isEmpty())
    }

    @Test
    fun `mkCyclicInputSCCs -- self-loop`() {
        val schema = "input Inp { inp: Inp }".asViaductSchema.schema
        val result = mkCyclicInputSCCs(schema)

        assertEquals(mapOf("Inp" to setOf("Inp")), result)
    }

    @Test
    fun `mkCyclicInputSCCs -- two-type cycle`() {
        val schema = """
            input A { b: B }
            input B { a: A }
        """.asViaductSchema.schema
        val result = mkCyclicInputSCCs(schema)

        assertEquals(
            mapOf("A" to setOf("A", "B"), "B" to setOf("A", "B")),
            result
        )
    }

    @Test
    fun `mkCyclicInputSCCs -- cycle with acyclic type`() {
        val schema = """
            input A { b: B }
            input B { a: A }
            input C { a: A }
        """.asViaductSchema.schema
        val result = mkCyclicInputSCCs(schema)

        assertEquals(
            mapOf("A" to setOf("A", "B"), "B" to setOf("A", "B")),
            result
        )
    }

    @Test
    fun `mkCyclicInputSCCs -- wrapped types are unwrapped`() {
        val schema = """
            input A { b: [B!]! }
            input B { a: A }
        """.asViaductSchema.schema
        val result = mkCyclicInputSCCs(schema)
        assertEquals(
            mapOf("A" to setOf("A", "B"), "B" to setOf("A", "B")),
            result
        )
    }
}

internal val GraphQLType.nonNullable: GraphQLType get() =
    if (this is GraphQLNonNull) {
        this
    } else {
        GraphQLNonNull.nonNull(this)
    }

internal fun GraphQLSchema.scalar(name: String): GraphQLScalarType = getTypeAs(name)

internal val emptySchema = "extend type Query { placeholder:Int }".asViaductSchema
