package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

class ParentConstructionDemandTest {
    @Test
    fun `selections without parent demand contribute nothing`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  organization: Organization @resolver(result: {})
                }

                type Organization {
                  company: Company @resolver(result: {})
                }

                type Company {
                  parent: Organization @parent
                  title: String
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val input =
            worldFixture.schemas
                .fragmentFrom("fragment F on Query { organization { company { title } } }")
                .subselections

        // The schema supports parents, but this demand neither selects one nor reaches one
        // through a resolver input.
        val expected = input.liftParentConstructionDemand(world)

        // Expected lifted demand: <empty>
        assertTrue(expected.isEmpty())
    }

    @Test
    fun `a direct parent selection lifts demand across its producer edge`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  organization: Organization @resolver(result: {})
                }

                type Organization {
                  name: String
                  company: Company @resolver(result: {})
                }

                type Company {
                  parent: Organization @parent
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val organization = schema.loweredSchema.requireObjectField("Organization", "company").containingDef
        val input =
            schema
                .fragmentFrom(
                    "fragment F on Query { organization { company { parent { name } } } }",
                ).subselections

        // `Company.parent` points back across the `Organization.company` producer edge.
        // The returned addition therefore asks the Organization OER itself for `name`.
        val expected = input.liftParentConstructionDemand(world).merge(schema.loweredSchema.requireQueryTypeDef())

        // Expected lifted demand:
        //
        // fragment Lifted on Query {
        //   organization { name }
        // }
        assertEquals(setOf("organization"), expected.fieldNames())
        assertEquals(
            setOf("name"),
            expected.field("organization").subselections.merge(organization).fieldNames(),
        )
    }

    @Test
    fun `a resolver object fragment can introduce parent demand`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  organization: Organization @resolver(result: {})
                }

                type Organization {
                  name: String
                  company: Company @resolver(result: {})
                }

                type Company {
                  parent: Organization @parent
                  displayName: String
                    @resolver(of: "parent { name }", result: null)
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val organization = schema.loweredSchema.requireObjectField("Organization", "company").containingDef
        val input =
            schema
                .fragmentFrom("fragment F on Query { organization { company { displayName } } }")
                .subselections

        // The client never selected `parent`. It selected `displayName`, whose fixed resolver
        // input selects `parent { name }`; that hidden input still lifts `name` to Organization.
        val expected = input.liftParentConstructionDemand(world).merge(schema.loweredSchema.requireQueryTypeDef())

        // Expected lifted demand:
        //
        // fragment Lifted on Query {
        //   organization { name }
        // }
        assertEquals(setOf("organization"), expected.fieldNames())
        assertEquals(
            setOf("name"),
            expected.field("organization").subselections.merge(organization).fieldNames(),
        )
    }

    @Test
    fun `lifted active demand can introduce another parent request`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  grand: Grand @resolver(result: {})
                }

                type Grand {
                  grandValue: Int
                  parentNode: Parent @resolver(result: {})
                }

                type Parent {
                  parent: Grand @parent
                  bridge: Int
                    @resolver(of: "parent { grandValue }", result: 1)
                  child: Child @resolver(result: {})
                }

                type Child {
                  parent: Parent @parent
                  result: Int
                    @resolver(of: "parent { bridge }", result: 1)
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val grand = schema.loweredSchema.requireObjectField("Grand", "parentNode").containingDef
        val parent = schema.loweredSchema.requireObjectField("Parent", "child").containingDef
        val input =
            schema
                .fragmentFrom("fragment F on Query { grand { parentNode { child { result } } } }")
                .subselections

        // This is the central intermediate-OER case:
        // 1. `Child.result` requires `parent { bridge }`, so Parent must resolve `bridge`.
        // 2. `Parent.bridge` is itself active and requires `parent { grandValue }`.
        // 3. That second parent request makes Grand resolve `grandValue` as well.
        val expected = input.liftParentConstructionDemand(world).merge(schema.loweredSchema.requireQueryTypeDef())

        // Expected lifted demand:
        //
        // fragment Lifted on Query {
        //   grand {
        //     grandValue
        //     parentNode { bridge }
        //   }
        // }
        assertEquals(setOf("grand"), expected.fieldNames())
        val expectedGrand = expected.field("grand").subselections.merge(grand)
        assertEquals(setOf("grandValue", "parentNode"), expectedGrand.fieldNames())
        assertEquals(
            setOf("bridge"),
            expectedGrand.field("parentNode").subselections.merge(parent).fieldNames(),
        )
    }

    @Test
    fun `parent demand can cross two producer edges`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  organization: Organization @resolver(result: {})
                }

                type Organization {
                  name: String
                  company: Company @resolver(result: {})
                }

                type Company {
                  parent: Organization @parent
                  user: User @resolver(result: {})
                }

                type User {
                  parent: Company @parent
                  organizationName: String
                    @resolver(of: "parent { parent { name } }", result: null)
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val organization = schema.loweredSchema.requireObjectField("Organization", "company").containingDef
        val company = schema.loweredSchema.requireObjectField("Company", "user").containingDef
        val input =
            schema
                .fragmentFrom(
                    "fragment F on Query { organization { company { user { organizationName } } } }",
                ).subselections

        // The first lift makes `Company.parent { name }` construction demand. An intermediate
        // Company OER therefore needs its parent backedge populated. The second lift places the
        // same `name` demand directly on the ancestor Organization OER.
        val expected = input.liftParentConstructionDemand(world).merge(schema.loweredSchema.requireQueryTypeDef())

        // Expected lifted demand:
        //
        // fragment Lifted on Query {
        //   organization {
        //     name
        //     company { parent { name } }
        //   }
        // }
        assertEquals(setOf("organization"), expected.fieldNames())
        val expectedOrganization =
            expected.field("organization").subselections.merge(organization)
        assertEquals(setOf("company", "name"), expectedOrganization.fieldNames())
        val expectedCompany = expectedOrganization.field("company").subselections.merge(company)
        assertEquals(setOf("parent"), expectedCompany.fieldNames())
        assertEquals(
            setOf("name"),
            expectedCompany.field("parent").subselections.merge(organization).fieldNames(),
        )
    }

    @Test
    fun `lifted demand retains client inclusion conditions`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  organization: Organization @resolver(result: {})
                }

                type Organization {
                  name: String
                  company: Company @resolver(result: {})
                }

                type Company {
                  parent: Organization @parent
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val organizationField = schema.loweredSchema.requireObjectField("Query", "organization")
        val organization = schema.loweredSchema.requireObjectField("Organization", "company").containingDef
        val input =
            schema
                .fragmentFrom(
                    """
                    fragment F on Query {
                      organization {
                        company @include(if: ${'$'}enabled) { parent { name } }
                      }
                    }
                    """.trimIndent(),
                    variableField = organizationField,
                ).subselections

        val expected = input.liftParentConstructionDemand(world).merge(schema.loweredSchema.requireQueryTypeDef())

        // Expected lifted demand:
        //
        // fragment Lifted on Query {
        //   organization { name @include(if: ${'$'}enabled) }
        // }
        val expectedName =
            expected
                .field("organization")
                .subselections
                .merge(organization)
                .field("name")

        // The condition guarding the producer edge also guards the demand lifted across it.
        for (enabled in listOf(false, true)) {
            assertEquals(
                enabled,
                expectedName.inclusionCondition.includeWith { variable ->
                    check(variable.variableName == "enabled")
                    enabled
                },
            )
        }
    }

    @Test
    fun `resolver-local conditions cannot suppress parent demand during lifting`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query {
                  root: Root @resolver(result: {})
                }

                type Root {
                  expensive: Int @resolver(result: 7)
                  child: Child @resolver(result: {})
                }

                type Child {
                  parent: Root @parent
                  result(enabled: Boolean!): Int
                    @resolver(
                      of: "parent @include(if: ${'$'}enabled) { expensive }"
                      result: 1
                    )
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val root = schema.loweredSchema.requireObjectField("Root", "child").containingDef
        val input =
            schema
                .fragmentFrom("fragment F on Query { root { child { result(enabled: false) } } }")
                .subselections

        val expected = input.liftParentConstructionDemand(world).merge(schema.loweredSchema.requireQueryTypeDef())

        // Expected lifted demand:
        //
        // fragment Lifted on Query {
        //   root { expensive }
        // }
        val expectedExpensive =
            expected
                .field("root")
                .subselections
                .merge(root)
                .field("expensive")

        // Fixed resolver inputs are analyzed before an occurrence's argument bindings exist and
        // are cached by resolver field. A dynamic condition in that input is therefore treated
        // conservatively: even this `enabled: false` occurrence lifts `expensive` unconditionally.
        assertSame(InclusionCondition.Always, expectedExpensive.inclusionCondition)
    }

    @Test
    fun `mixed parent and ordinary field selection retains ordinary resolver parent demand`() {
        val worldFixture =
            TestWorld.fromDSL(
                """
            extend type Query { children: [Child] }

            interface Child { edge: Parent }

            type ParentChild implements Child {
              edge: Parent @parent
            }

            type OrdinaryChild implements Child {
              edge: Parent @resolver(of: "child { parent { required } }", result: null)
              child: NestedChild
              required: Int
            }

            type Parent {
              value: Int
              child: ParentChild
            }

            type NestedChild { parent: OrdinaryChild @parent }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val schema = worldFixture.schemas
        val ordinaryChild = schema.loweredSchema.requireObjectField("OrdinaryChild", "edge").containingDef

        // On ParentChild, `edge` is a parent backedge. On OrdinaryChild, the same interface field
        // is an ordinary resolver whose input eventually lifts `required` back from NestedChild.
        // Seeing the parent implementation must not make analysis skip the ordinary one.
        for (selectionSet in listOf(
            "children { ... on OrdinaryChild { edge { value } } }",
            "children { edge { value } }",
        )) {
            val input = schema.fragmentFrom("fragment F on Query { $selectionSet }").subselections
            val expected = input + input.liftParentConstructionDemand(world)
            val expectedOrdinaryChild = expected.merge(schema.loweredSchema.requireQueryTypeDef())
                .single().subselections.merge(ordinaryChild)

            // Expected combined demand after specializing to OrdinaryChild:
            //
            // fragment Combined on OrdinaryChild {
            //   edge { value }
            //   required
            // }
            assertEquals(
                setOf("edge", "required"),
                expectedOrdinaryChild.groundKeys().map { it.field.name }.toSet(),
                "Construction demand for OrdinaryChild with $selectionSet",
            )
        }
    }

    private fun ObjectSelectionForest.field(name: String) = byKey().values.single { selection -> selection.key.field.name == name }

    private fun ObjectSelectionForest.fieldNames(): Set<String> = keys().mapTo(linkedSetOf()) { key -> key.field.name }
}
