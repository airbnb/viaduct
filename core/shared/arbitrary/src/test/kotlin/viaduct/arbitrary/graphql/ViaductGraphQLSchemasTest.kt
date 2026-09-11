@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.removeEdgecases
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase

class ViaductGraphQLSchemasTest : KotestPropertyBase() {
    /** Every shape off, so each test enables only the pass it exercises. */
    private val noShapes: Config = Config.default +
        (IdOfFieldWeight to 0.0) +
        (ConnectionCount to 0..0) +
        (DeclaredFieldResolverWeight to 0.0) +
        (DeclaredNodeResolverWeight to 0.0)

    /**
     * Seeds `Node` so the generic generator gives some objects a `Node` super, which is what the
     * `@idOf` and Connection passes need to have anything to attach to. Small schemas keep the
     * property tests quick while still producing enough objects to sample from.
     */
    private val nodeSeededConfig: Config = noShapes +
        (IncludeTypes to viaductNodeTypes()) +
        (SchemaSize to 40) +
        (ObjectImplementsInterface to CompoundingWeight(1.0, 4)) +
        (DescriptionLength to 0..0) +
        (DefaultValueWeight to 0.0) +
        (DirectiveHasArgs to CompoundingWeight.Never)

    private val oneNodeSdl = """
        | type Query { foo: Foo }
        | interface Node { id: ID! }
        | type Foo implements Node { id: ID!, ownerId: ID, name: String }
    """.trimMargin()

    /** A Node implementor, a non-Node object, ID fields to annotate, and non-ID fields to host a Connection. */
    private val everyShapeSdl = """
        | type Query { foo: Foo, bar: Bar }
        | interface Node { id: ID! }
        | type Foo implements Node { id: ID!, ownerId: ID, name: String }
        | type Bar { fooId: ID, label: String }
    """.trimMargin()

    @Test
    fun `Arb_viaductDirectiveSchema`(): Unit =
        runBlocking {
            Arb.viaductDirectiveSchema(nodeSeededConfig).removeEdgecases().checkAll(10) {
                markSuccess()
            }
        }

    /**
     * Every pass is a no-op unless its config key is enabled and the types it needs are present, so a
     * test that only checked SDL validity would still pass if all four silently did nothing.
     */
    @Test
    fun `AddViaductDirectives -- applies every Viaduct directive shape`(): Unit =
        runBlocking {
            val cfg = noShapes +
                (IdOfFieldWeight to 1.0) +
                (ConnectionCount to 1..1) +
                (DeclaredFieldResolverWeight to 1.0) +
                (DeclaredNodeResolverWeight to 1.0)

            Arb.viaductDirectiveSchema(everyShapeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                schema.appliedDirectiveNames() shouldContainAll setOf("idOf", "edge", "connection", "resolver")
            }
        }

    /**
     * graphql-java validates interface conformance when parsing SDL but not when a schema is assembled
     * programmatically, so a pass that retyped an inherited field would hand back a schema that only
     * fails later, in whatever consumer reparses the fragment.
     */
    @Test
    fun `AddViaductDirectives -- objects still satisfy their interfaces`(): Unit =
        runBlocking {
            val cfg = nodeSeededConfig +
                (IdOfFieldWeight to 1.0) +
                (ConnectionCount to 2..2)

            Arb.viaductDirectiveSchema(cfg).removeEdgecases().checkInvariants(10) { schema, check ->
                schema.objects().forEach { obj ->
                    obj.interfaces.filterIsInstance<GraphQLInterfaceType>().forEach { iface ->
                        iface.fieldDefinitions.forEach { ifaceField ->
                            val objField = obj.getFieldDefinition(ifaceField.name)
                            check.isTrue(
                                objField != null && objField.type.typeName() == ifaceField.type.typeName(),
                                "{0} implements {1} but its {2} field is {3}, not {4}",
                                arrayOf(
                                    obj.name,
                                    iface.name,
                                    ifaceField.name,
                                    objField?.type?.typeName() ?: "absent",
                                    ifaceField.type.typeName()
                                )
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `IdOfFieldWeight -- disabled`(): Unit =
        runBlocking {
            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, noShapes).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                assertNull(schema.field("Foo", "ownerId").idOfTarget())
            }
        }

    @Test
    fun `IdOfFieldWeight -- enabled`(): Unit =
        runBlocking {
            val cfg = noShapes + (IdOfFieldWeight to 1.0)

            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                // "Foo" is the only Node implementor, so the target is deterministic.
                schema.field("Foo", "ownerId").idOfTarget() shouldBe "Foo"

                // A Node's own `id` is off-limits, and @idOf only applies to ID-typed fields.
                assertNull(schema.field("Foo", "id").idOfTarget())
                assertNull(schema.field("Foo", "name").idOfTarget())
            }
        }

    @Test
    fun `IdOfFieldWeight -- skips fields inherited from an interface`(): Unit =
        runBlocking {
            val sdl = """
                | type Query { foo: Foo }
                | interface Node { id: ID! }
                | interface HasOwner { ownerId: ID }
                | type Foo implements Node & HasOwner { id: ID!, ownerId: ID, ownId: ID }
            """.trimMargin()
            val cfg = noShapes + (IdOfFieldWeight to 1.0)

            Arb.viaductDirectiveSchema(sdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                // @idOf changes the mapped Kotlin return type, which would break the override
                // contract against an interface that lacks it.
                assertNull(schema.field("Foo", "ownerId").idOfTarget())
                schema.field("Foo", "ownId").idOfTarget() shouldBe "Foo"
            }
        }

    /**
     * Documents a deliberate narrowing: `@idOf` on a list-of-`ID` trips a pre-existing divergence
     * between the two GRT codegen backends (invariant `GlobalID<Foo>` from kotlingen vs contravariant
     * `GlobalID<out Foo>` from the direct-to-bytecode path). Widen this once that is fixed.
     */
    @Test
    fun `IdOfFieldWeight -- skips list-of-ID fields`(): Unit =
        runBlocking {
            val sdl = """
                | type Query { foo: Foo }
                | interface Node { id: ID! }
                | type Foo implements Node {
                |   id: ID!
                |   one: ID
                |   many: [ID]
                |   nested: [[ID]]
                |   nonNullMany: [ID!]!
                | }
            """.trimMargin()
            val cfg = noShapes + (IdOfFieldWeight to 1.0)

            Arb.viaductDirectiveSchema(sdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                schema.field("Foo", "one").idOfTarget() shouldBe "Foo"
                assertNull(schema.field("Foo", "many").idOfTarget())
                assertNull(schema.field("Foo", "nested").idOfTarget())
                assertNull(schema.field("Foo", "nonNullMany").idOfTarget())
            }
        }

    @Test
    fun `IdOfFieldWeight -- no-op without a Node implementor`(): Unit =
        runBlocking {
            val sdl = """
                | type Query { foo: Foo }
                | interface Node { id: ID! }
                | type Foo { ownerId: ID }
            """.trimMargin()
            val cfg = noShapes + (IdOfFieldWeight to 1.0)

            Arb.viaductDirectiveSchema(sdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                assertNull(schema.field("Foo", "ownerId").idOfTarget())
            }
        }

    @Test
    fun `ConnectionCount -- disabled`(): Unit =
        runBlocking {
            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, noShapes).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                schema.objectsWith("edge").shouldBeEmpty()
                schema.objectsWith("connection").shouldBeEmpty()
            }
        }

    @Test
    fun `ConnectionCount -- enabled`(): Unit =
        runBlocking {
            val cfg = noShapes + (ConnectionCount to 1..1)

            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                val edge = schema.objectsWith("edge").single()
                val connection = schema.objectsWith("connection").single()

                edge.name shouldBe "FooEdge"
                edge.getFieldDefinition("node").type.typeName() shouldBe "Foo"
                connection.name shouldBe "FooConnection"
                connection.getFieldDefinition("edges").type.typeName() shouldBe "FooEdge"

                // Exactly one existing field is retyped to the Connection, and the first shape of the
                // round-robin is forward-only pagination.
                val (_, host) = schema.fieldsReturning("FooConnection").single()
                host.arguments.map { it.name } shouldContainAll setOf("first", "after")
            }
        }

    @Test
    fun `ConnectionCount -- cycles through pagination arguments`(): Unit =
        runBlocking {
            val cfg = noShapes + (ConnectionCount to 3..3)

            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                val arguments = listOf("FooConnection", "FooConnection2", "FooConnection3").map { name ->
                    val (_, host) = schema.fieldsReturning(name).single()
                    host.arguments.map { it.name }
                }

                arguments shouldContainExactly listOf(
                    listOf("first", "after"),
                    listOf("last", "before"),
                    listOf("first", "after", "last", "before"),
                )
            }
        }

    @Test
    fun `ConnectionCount -- never hosts a Connection on an @idOf field`(): Unit =
        runBlocking {
            // More shapes than there are eligible host fields, so the pass has to reject candidates
            // rather than run out of them.
            val cfg = noShapes + (IdOfFieldWeight to 1.0) + (ConnectionCount to 5..5)

            Arb.viaductDirectiveSchema(everyShapeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                // Retyping only replaces a field's type and arguments, so an @idOf left behind on a
                // field that no longer returns an ID would be invalid.
                val connectionNames = schema.objectsWith("connection").map { it.name }.toSet()
                connectionNames.shouldNotBeEmpty()
                schema.objects().flatMap { it.fieldDefinitions }.forEach { field ->
                    assertFalse(
                        field.idOfTarget() != null && field.type.typeName() in connectionNames,
                        "${field.name} carries @idOf but returns a Connection"
                    )
                }
            }
        }

    @Test
    fun `DeclaredResolverWeights -- disabled`(): Unit =
        runBlocking {
            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, noShapes).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                assertFalse(schema.obj("Foo").hasAppliedDirective("resolver"))
                assertFalse(schema.field("Foo", "name").hasAppliedDirective("resolver"))
            }
        }

    @Test
    fun `DeclaredFieldResolverWeight -- enabled`(): Unit =
        runBlocking {
            val cfg = noShapes + (DeclaredFieldResolverWeight to 1.0)

            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                assertTrue(schema.field("Foo", "name").hasAppliedDirective("resolver"))
                assertTrue(schema.field("Query", "foo").hasAppliedDirective("resolver"))
                // The field weight alone leaves the type itself alone.
                assertFalse(schema.obj("Foo").hasAppliedDirective("resolver"))
            }
        }

    @Test
    fun `DeclaredNodeResolverWeight -- enabled`(): Unit =
        runBlocking {
            val sdl = """
                | type Query { foo: Foo }
                | interface Node { id: ID! }
                | type Foo implements Node { id: ID! }
                | type Bar { x: Int }
            """.trimMargin()
            val cfg = noShapes + (DeclaredNodeResolverWeight to 1.0)

            Arb.viaductDirectiveSchema(sdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                assertTrue(schema.obj("Foo").hasAppliedDirective("resolver"))
                assertFalse(schema.obj("Bar").hasAppliedDirective("resolver"))
                assertFalse(schema.obj("Query").hasAppliedDirective("resolver"))
            }
        }

    @Test
    fun `SelectiveResolverWeight and BatchingResolverWeight drive the @resolver arguments`(): Unit =
        runBlocking {
            val base = noShapes +
                (DeclaredFieldResolverWeight to 1.0) +
                (DeclaredNodeResolverWeight to 1.0)

            Arb.viaductDirectiveSchema(
                oneNodeSdl.asSchema,
                base + (SelectiveResolverWeight to 0.0) + (BatchingResolverWeight to 0.0)
            ).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                schema.field("Foo", "name").resolverFlags() shouldBe (false to false)
                schema.obj("Foo").resolverFlags() shouldBe (false to false)
            }

            Arb.viaductDirectiveSchema(
                oneNodeSdl.asSchema,
                base + (SelectiveResolverWeight to 1.0) + (BatchingResolverWeight to 1.0)
            ).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                schema.field("Foo", "name").resolverFlags() shouldBe (true to true)
                schema.obj("Foo").resolverFlags() shouldBe (true to true)
            }
        }

    @Test
    fun `printAsSDLFragment -- applies Viaduct's directives without redeclaring them`(): Unit =
        runBlocking {
            val cfg = noShapes + (IdOfFieldWeight to 1.0) + (DeclaredNodeResolverWeight to 1.0)

            Arb.viaductDirectiveSchema(oneNodeSdl.asSchema, cfg).checkAll(FIXED_SCHEMA_ITERATIONS) { schema ->
                val sdl = schema.printAsSDLFragment()

                sdl shouldContain "@idOf("
                sdl shouldContain "@resolver("
                // A Viaduct schema declares these itself; redeclaring them collides on merge.
                sdl shouldNotContain "directive @idOf"
                sdl shouldNotContain "directive @resolver"
                sdl shouldNotContain "interface Node"
            }
        }
}

/**
 * Tests over a hand-written schema with weights pinned to 0 or 1 are deterministic, so the default
 * iteration count only buys runtime. A handful of iterations still covers the sampling that remains.
 */
private const val FIXED_SCHEMA_ITERATIONS = 20

private fun GraphQLSchema.objects(): List<GraphQLObjectType> = allTypesAsList.filterIsInstance<GraphQLObjectType>().filterNot { it.name.startsWith("__") }

private fun GraphQLSchema.obj(name: String): GraphQLObjectType = getObjectType(name)

private fun GraphQLSchema.field(
    objName: String,
    fieldName: String
): GraphQLFieldDefinition = obj(objName).getFieldDefinition(fieldName)

private fun GraphQLSchema.objectsWith(directiveName: String): List<GraphQLObjectType> = objects().filter { it.hasAppliedDirective(directiveName) }

/** Every applied directive name in the schema, across objects and their fields. */
private fun GraphQLSchema.appliedDirectiveNames(): Set<String> =
    objects()
        .flatMapTo(mutableSetOf()) { obj ->
            obj.appliedDirectives.map { it.name } + obj.fieldDefinitions.flatMap { field -> field.appliedDirectives.map { it.name } }
        }

/** Object-name-to-field pairs for every field whose base type is [typeName]. */
private fun GraphQLSchema.fieldsReturning(typeName: String): List<Pair<String, GraphQLFieldDefinition>> =
    objects()
        .flatMap { obj -> obj.fieldDefinitions.map { obj.name to it } }
        .filter { (_, field) -> field.type.typeName() == typeName }

private fun GraphQLDirectiveContainer.resolverFlags(): Pair<Boolean, Boolean> {
    val applied = getAppliedDirective("resolver")!!
    return applied.getArgument("isSelective").getValue<Boolean>() to applied.getArgument("isBatching").getValue<Boolean>()
}

private fun GraphQLFieldDefinition.idOfTarget(): String? = getAppliedDirective("idOf")?.getArgument("type")?.getValue()

private fun GraphQLType.typeName(): String? = (GraphQLTypeUtil.unwrapAll(this) as? GraphQLNamedType)?.name
