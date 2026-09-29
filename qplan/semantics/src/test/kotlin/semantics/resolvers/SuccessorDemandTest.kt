package semantics.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import model.EngineErrorData
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.SharedOperationContext
import semantics.shared.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandTest {
    @Test
    fun `successor demand lifts nested parent selections through producer fields`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { organization: Organization }
                    type Organization { name: String, company: Company }
                    type Company { parent: Organization @parent, user: User }
                    type User { parent: Company @parent }
                    """.trimIndent(),
            ).assumptions
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val organization = schema.requireType("Organization") as ViaductSchema.Object
        val company = schema.requireType("Company") as ViaductSchema.Object
        val user = schema.requireType("User") as ViaductSchema.Object
        val selections =
            schema.fragmentFrom(
                "fragment ignored on Query { organization { company { user { parent { parent { name } } } } } }",
            ).subselections

        val completed = SharedOperationContext.create(world).let { resolutionOperation -> selections.successorDemand(resolutionOperation) }
        val organizationSelection = completed.merge(query)[schema.key(query, "organization")]
        val organizationDemand = organizationSelection.subselections.merge(organization)
        val companySelection = organizationDemand[schema.key(organization, "company")]
        val companyDemand = companySelection.subselections.merge(company)
        val userSelection = companyDemand[schema.key(company, "user")]
        val userDemand = userSelection.subselections.merge(user)

        assertEquals(setOf("company", "name"), organizationDemand.keys().objectKeyFieldNames())
        assertEquals(setOf("user", "parent"), companyDemand.keys().objectKeyFieldNames())
        assertEquals(setOf("parent"), userDemand.keys().objectKeyFieldNames())
    }

    @Test
    fun `boundary demand retains resolver paths but omits passive leaves`() {
        val world =
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
                        schema.requireField("Query", "root") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                EngineErrorData.of()
                            },
                        schema.requireField("Root", "consumer") to
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
                        schema.requireField("Box", "computed") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Box"),
                            ) { _, _ ->
                                "computed"
                            },
                    )
                },
            ).assumptions
        val schema = world.schema
        val selections =
            schema.fragmentFrom(
                "fragment ignored on Query { root { consumer } }",
            ).subselections

        val full =
            SharedOperationContext.create(world).let {
                    resolutionOperation ->
                selections.successorDemand(resolutionOperation).merge(schema.requireQueryTypeDef()).instantiateBindings(resolutionOperation)
            }[schema.key(schema.requireQueryTypeDef(), "root")]
                .subselections
        val boundaries =
            SharedOperationContext.create(world).let {
                    resolutionOperation ->
                selections.successorBoundaryDemand(resolutionOperation).merge(schema.requireQueryTypeDef()).instantiateBindings(resolutionOperation)
            }[schema.key(schema.requireQueryTypeDef(), "root")]
                .subselections
        val rootType = schema.requireType("Root") as ViaductSchema.Object
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

        val boxType = schema.requireType("Box") as ViaductSchema.Object
        val fullBox = fullRoot[schema.key(rootType, "box")]
        val boundaryBox = boundaryRoot[schema.key(rootType, "box")]
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
