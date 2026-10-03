package viaduct.engine.runtime2.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandTest {
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
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      passive: String
                      computed: String
                    }

                    type Root {
                      source: String
                      box: Box
                      consumer: String
                    }

                    type Query {
                      root: Root
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "root") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                EngineErrorData.of()
                            },
                        schema.loweredSchema.requireField("Root", "consumer") to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Root {
                                          source
                                          box {
                                            passive
                                            computed
                                            __typename
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, _ ->
                                "consumer"
                            },
                        schema.loweredSchema.requireField("Box", "computed") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Box"),
                            ) { _, _ ->
                                "computed"
                            },
                    )
                },
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
            setOf("passive", "computed", "V_A_typename"),
            SharedOperationContext.create(world).let { resolutionOperation ->
                fullBox.subselections
                    .merge(boxType)
                    .instantiateBindings(resolutionOperation)
                    .groundKeys()
                    .fieldNames()
            },
        )
        assertEquals(
            setOf("computed", "V_A_typename"),
            SharedOperationContext.create(world).let { resolutionOperation ->
                boundaryBox.subselections
                    .merge(boxType)
                    .instantiateBindings(resolutionOperation)
                    .groundKeys()
                    .fieldNames()
            },
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
