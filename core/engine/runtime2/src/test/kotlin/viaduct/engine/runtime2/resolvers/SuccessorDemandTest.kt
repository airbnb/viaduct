package viaduct.engine.runtime2.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandTest {
    @Test
    fun `successor demand distinguishes unknown nested argument paths from known null`() {
        for (knownNull in listOf(false, true)) {
            val fixture = TestWorld.fromDSL(
                """
                extend type Query {
                  start: Int @resolver(
                    of: "relay(input: {nested: ${if (knownNull) "null" else "${'$'}provided"}}, stamp: ${'$'}stamp)"
                    vars: {names: [${if (knownNull) "\"stamp\"" else "\"provided\", \"stamp\""}], script: "throw 'Lookahead must not invoke providers'"}
                    script: "throw 'Lookahead must not execute resolvers'"
                  )
                  relay(input: Envelope, stamp: Int!): Int @resolver(
                    of: "seed(input: ${'$'}nested, stamp: ${'$'}stamp)"
                    variables: [{name: "nested", fromArgument: ["input", "nested"]}]
                    script: "throw 'Lookahead must not execute resolvers'"
                  )
                  seed(input: Payload, stamp: Int!): Int @resolver(
                    of: "consume(value: ${'$'}value)"
                    variables: [{name: "value", fromArgument: ["input", "value"]}]
                    script: "throw 'Lookahead must not execute resolvers'"
                  )
                  consume(value: Int): Int @resolver(of: "backing", script: "throw 'Lookahead must not execute resolvers'")
                  backing: Int
                }
                input Envelope { nested: Payload }
                input Payload { value: Int }
                """.trimIndent(),
            )
            val schema = fixture.schema
            val operation = SharedOperationContext.create(fixture.assumptions)
            val selections = fixture.schemas.fragmentFrom("fragment Input on Query { start }").subselections
            val keys = selections.successorDemand(operation).merge(schema.requireQueryTypeDef()).keys()

            assertTrue(schema.key(schema.requireQueryTypeDef(), "backing") in keys)
            assertEquals(
                knownNull,
                ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "consume"), mapOf("value" to null)) in keys,
                "Unknown input must stay symbolic; only a statically null path demands consume(value: null)",
            )
        }
    }

    @Test
    fun `successor demand lifts nested parent selections through producer fields`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { organization: Organization }
                    type Organization { name: String, company: Company }
                    type Company { parent: Organization @parent, user: User }
                    type User { parent: Company @parent }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val query = schema.loweredSchema.requireQueryTypeDef()
        val organization = schema.loweredSchema.requireType("Organization") as ViaductSchema.Object
        val company = schema.loweredSchema.requireType("Company") as ViaductSchema.Object
        val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
        val selections =
            schema.fragmentFrom(
                "fragment ignored on Query { organization { company { user { parent { parent { name } } } } } }",
            ).subselections

        val completed = SharedOperationContext.create(world).let { resolutionOperation -> selections.successorDemand(resolutionOperation) }
        val organizationSelection = completed.merge(query)[schema.loweredSchema.key(query, "organization")]
        val organizationDemand = organizationSelection.subselections.merge(organization)
        val companySelection = organizationDemand[schema.loweredSchema.key(organization, "company")]
        val companyDemand = companySelection.subselections.merge(company)
        val userSelection = companyDemand[schema.loweredSchema.key(company, "user")]
        val userDemand = userSelection.subselections.merge(user)

        assertEquals(setOf("company", "name"), organizationDemand.keys().objectKeyFieldNames())
        assertEquals(setOf("user", "parent"), companyDemand.keys().objectKeyFieldNames())
        assertEquals(setOf("parent"), userDemand.keys().objectKeyFieldNames())
    }

    @Test
    fun `boundary demand retains resolver paths but omits passive leaves`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query { root: Root @resolver(script: "error") }
                type Root {
                  source: String
                  box: Box
                  consumer: String @resolver(of: "source box { passive computed __typename }", script: "'consumer'")
                }
                type Box {
                  passive: String
                  computed: String @resolver(script: "'computed'")
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val selections =
            schema.fragmentFrom(
                "fragment ignored on Query { root { consumer } }",
            ).subselections

        val full =
            SharedOperationContext.create(world).let {
                    resolutionOperation ->
                selections.successorDemand(resolutionOperation).merge(schema.loweredSchema.requireQueryTypeDef()).instantiateBindings(resolutionOperation)
            }[schema.loweredSchema.key(schema.loweredSchema.requireQueryTypeDef(), "root")]
                .subselections
        val boundaries =
            SharedOperationContext.create(world).let {
                    resolutionOperation ->
                selections.successorBoundaryDemand(resolutionOperation).merge(schema.loweredSchema.requireQueryTypeDef()).instantiateBindings(resolutionOperation)
            }[schema.loweredSchema.key(schema.loweredSchema.requireQueryTypeDef(), "root")]
                .subselections
        val rootType = schema.loweredSchema.requireType("Root") as ViaductSchema.Object
        val fullRoot = SharedOperationContext.create(world).let { resolutionOperation -> full.merge(rootType).instantiateBindings(resolutionOperation) }
        val boundaryRoot = SharedOperationContext.create(world).let { resolutionOperation -> boundaries.merge(rootType).instantiateBindings(resolutionOperation) }

        assertEquals(
            setOf("consumer", "source", "box"),
            fullRoot.groundKeys().fieldNames(),
        )
        assertEquals(
            setOf("consumer", "box"),
            boundaryRoot.groundKeys().fieldNames(),
        )

        val boxType = schema.loweredSchema.requireType("Box") as ViaductSchema.Object
        val fullBox = fullRoot[schema.loweredSchema.key(rootType, "box")]
        val boundaryBox = boundaryRoot[schema.loweredSchema.key(rootType, "box")]
        assertEquals(
            setOf("passive", "computed", "_viaduct_internal__typename"),
            SharedOperationContext.create(world).let { resolutionOperation ->
                fullBox.subselections
                    .merge(boxType)
                    .instantiateBindings(resolutionOperation)
                    .groundKeys()
                    .fieldNames()
            },
        )
        assertEquals(
            setOf("computed", "_viaduct_internal__typename"),
            SharedOperationContext.create(world).let { resolutionOperation ->
                boundaryBox.subselections
                    .merge(boxType)
                    .instantiateBindings(resolutionOperation)
                    .groundKeys()
                    .fieldNames()
            },
        )
    }

    @Test
    fun `grounded successor demand lifts interface parent field after specialization`() {
        val worldFixture = TestWorld.fromDSL(
            """
            extend type Query { root: Root }
            type Root {
              seed: Int
              consumer: Int @resolver(of: "child { ... on HasParent { parent { seed } } }", script: "0")
              child: Child
            }
            type Child implements HasParent { parent: Root @parent }
            interface HasParent { parent: Root }
            """.trimIndent(),
        )
        val schema = worldFixture.schemas
        val root = schema.loweredSchema.requireObjectField("Root", "seed").containingDef
        val selections = schema.fragmentFrom("fragment F on Root { consumer }").subselections
        val completed = SharedOperationContext.create(worldFixture.assumptions).let { op -> selections.successorDemand(op) }
        val rootDemand = completed.merge(root)
        val fieldNames = rootDemand.keys().objectKeyFieldNames()
        assertTrue(
            "seed" in fieldNames,
            "Expected { consumer, child, seed } from interface parent lift but got $fieldNames",
        )
    }

    private fun Set<ObjectEngineResult.GroundKey>.fieldNames(): Set<String> = mapTo(mutableSetOf()) { key -> key.field.name }

    private fun Set<ObjectEngineResult.ObjectKey>.objectKeyFieldNames(): Set<String> = mapTo(mutableSetOf()) { key -> key.field.name }

    private fun ViaductSchema.key(
        type: ViaductSchema.Object,
        fieldName: String,
    ): ObjectEngineResult.GroundKey =
        ObjectEngineResult.GroundKey.of(
            field = requireObjectField(type.name, fieldName),
            arguments = emptyMap(),
        )
}
