package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.Demand

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

    @Test
    fun `joined ancestor requirements preserve guards arguments and concrete descendants`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Parent @resolver(result: {}) }
            type Parent { child: Child data(id: Int!): Data }
            type Child { parent: Parent @parent }
            interface Data { common: Int }
            type A implements Data { common: Int left: Int right: Int }
            type B implements Data { common: Int other: Int }
            """.trimIndent(),
        )
        val input = fixture.schemas.fragmentFrom(
            """
            fragment F on Query {
              root {
                child @include(if: ${'$'}a) {
                  parent { data(id: 1) { common ... on A { left } ... on B { other } } }
                }
                child @include(if: ${'$'}b) {
                  parent { data(id: 1) { ... on A { common right } } data(id: 2) { ... on B { other } } }
                }
              }
            }
            """.trimIndent(),
        ).subselections
        val world = fixture.assumptions
        val lifted = Demand.checked(input).liftParentConstructionDemand(world)
        assertTrue(lifted.unchecked.isEmpty())
        val parent = world.schema.requireObjectField("Parent", "child").containingDef
        val data = lifted.checked.single().subselections.merge(parent)
        assertEquals(2, data.size, "Different ancestor argument tuples must stay separate")
        val byId = data.byKey().values.associateBy { (it.key.arguments as Arguments.Resolved).fieldValues["id"] }
        val aType = world.schema.requireObjectField("A", "left").containingDef
        val bType = world.schema.requireObjectField("B", "other").containingDef
        val first = byId.getValue(1)
        val second = byId.getValue(2)
        assertEquals(setOf("common", "left", "right"), first.subselections.merge(aType).fieldNames())
        assertEquals(setOf("common", "other"), first.subselections.merge(bType).fieldNames())
        assertTrue(second.subselections.merge(aType).isEmpty())
        for (a in listOf(false, true)) for (b in listOf(false, true)) {
            fun included(condition: InclusionCondition) =
                condition.includeWith { variable ->
                    when (variable.variableName) {
                        "a" -> a
                        "b" -> b
                        else -> error("Unexpected binding $variable")
                    }
                }
            assertEquals(a || b, included(first.inclusionCondition))
            assertEquals(b, included(second.inclusionCondition))
            assertEquals(a || b, included(first.subselections.merge(aType).field("common").inclusionCondition))
            assertEquals(a, included(first.subselections.merge(bType).field("common").inclusionCondition))
            assertEquals(a, included(first.subselections.merge(aType).field("left").inclusionCondition))
            assertEquals(b, included(first.subselections.merge(aType).field("right").inclusionCondition))
            assertEquals(a, included(first.subselections.merge(bType).field("other").inclusionCondition))
            assertEquals(b, included(second.subselections.merge(bType).field("other").inclusionCondition))
        }
    }

    @Test
    fun `repeated parent joins do not read excluded sibling guards`() {
        for (checked in listOf(true, false)) {
            val descendants = repeatedSiblingDemand(checked)
            for ((field, guard) in listOf("common" to "a", "special" to "b")) {
                val visited = mutableListOf<String>()
                assertFalse(
                    descendants.field(field).inclusionCondition.includeWith { variable ->
                        visited += variable.variableName
                        if (variable.variableName == guard) false else error("Unneeded sibling ${variable.variableName}")
                    }
                )
                assertEquals(listOf(guard), visited)
            }
            assertTrue(descendants.field("other").inclusionCondition.includeWith { error("Unconditional descendant read a binding") })
        }
    }

    @Test
    fun `repeated parent joins exclude descendants despite failed sibling activation`() =
        runTest {
            for (checked in listOf(true, false)) {
                val descendants = repeatedSiblingDemand(checked)
                for ((field, guard) in listOf("common" to "a", "special" to "b")) {
                    assertFalse(
                        descendants.field(field).inclusionCondition.includeAnyReadyAlternative { variable ->
                            if (variable.variableName == guard) false else error("Unneeded sibling ${variable.variableName}")
                        }
                    )
                }
                assertTrue(descendants.field("other").inclusionCondition.includeAnyReadyAlternative { error("Unconditional descendant read a binding") })
            }
        }

    @Test
    fun `repeated parent joins retain binding order in equivalent composite guards`() =
        runTest {
            val query = """
            fragment F on Parent {
              child @include(if: ${'$'}a) { parent @include(if: ${'$'}b) { item { common } } }
              child @include(if: ${'$'}b) { parent @include(if: ${'$'}a) { item { special } } }
              child { parent { item { other } } }
            }
            """.trimIndent()
            for (checked in listOf(true, false)) {
                val special = repeatedSiblingDemand(checked, query).field("special")
                val visited = mutableListOf<String>()
                assertFalse(
                    special.inclusionCondition.includeWith { variable ->
                        visited += variable.variableName
                        if (variable.variableName == "b") false else error("Excluded descendant read ${variable.variableName}")
                    }
                )
                assertEquals(listOf("b"), visited)
                assertFalse(
                    special.inclusionCondition.includeAnyReadyAlternative { variable ->
                        if (variable.variableName == "b") false else error("Excluded descendant read ${variable.variableName}")
                    }
                )
            }
        }

    private fun repeatedSiblingDemand(
        checked: Boolean,
        query: String = """
            fragment F on Parent {
              child @include(if: ${'$'}a) { parent { item { common } } }
              child @include(if: ${'$'}b) { parent { item { special } } }
              child { parent { item { other } } }
            }
        """.trimIndent(),
    ): ObjectSelectionForest {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Parent @resolver(result: {}) }
            type Parent { child: Child item: Item }
            type Child { parent: Parent @parent }
            type Item { common: Int special: Int other: Int }
            """.trimIndent(),
        )
        val input = fixture.schemas.fragmentFrom(query).subselections
        val demand = if (checked) Demand.checked(input) else Demand.unchecked(input)
        val lifted = demand.liftParentConstructionDemand(fixture.assumptions)
        val parent = fixture.assumptions.schema.requireObjectField("Parent", "child").containingDef
        val itemType = fixture.assumptions.schema.requireObjectField("Item", "common").containingDef
        val component = if (checked) lifted.checked else lifted.unchecked
        val item = component.merge(parent).field("item")
        assertSame(InclusionCondition.Always, item.inclusionCondition)
        return item.subselections.merge(itemType)
    }

    @Test
    fun `joining parent requirements keeps shared interface selections compact`() {
        val implementations = (0 until 128).joinToString("\n") { "type Item$it implements Item { common(extra: Int = $it): Int }" }
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Parent @resolver(result: {}) }
            type Parent { child: Child item: Item }
            type Child { parent: Parent @parent }
            interface Item { common: Int }
            $implementations
            """.trimIndent(),
        )
        val input = fixture.schemas.fragmentFrom(
            "fragment F on Parent { child { first: parent { item { common } } second: parent { item { common } } } }",
        ).subselections
        val parent = fixture.schemas.loweredSchema.requireObjectField("Parent", "child").containingDef
        val lifted = Demand.checked(input).liftParentConstructionDemand(fixture.assumptions)
        val item = lifted.checked.merge(parent).single()
        assertTrue(item.subselections.size <= 2, "A shared interface field expanded to ${item.subselections.size} concrete copies")
        val original = fixture.schemas.fragmentFrom("fragment Original on Item { common }").subselections
        for (index in 0 until 128) {
            val type = fixture.schemas.loweredSchema.requireObjectField("Item$index", "common").containingDef
            val concrete = item.subselections.merge(type)
            assertEquals(setOf("common"), concrete.fieldNames())
            assertEquals(original.merge(type).keys(), concrete.keys(), "Concrete argument defaults changed for Item$index")
        }
    }

    @Test
    fun `shared parent paths preserve the first failing alternative`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Parent @resolver(result: {}) }
            type Parent { child: Child seed: Int }
            type Child { parent: Parent @parent field0: Int @resolver(of: "parent { seed }", result: 1) }
            """.trimIndent(),
        )
        val input = fixture.schemas.fragmentFrom(
            """
            fragment F on Query {
              root { child {
                first: field0 @include(if: ${'$'}a)
                parent @include(if: ${'$'}b) { seed }
                last: field0 @include(if: ${'$'}c)
              } }
            }
            """.trimIndent(),
        ).subselections
        val guard = Demand.checked(input).liftParentConstructionDemand(fixture.assumptions)
            .checked.single().subselections.single().inclusionCondition
        val visited = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            guard.includeWith {
                visited += it.variableName
                if (it.variableName == "a") false else error(it.variableName)
            }
        }
        assertEquals("b", failure.message)
        assertEquals(listOf("a", "b"), visited)
        assertTrue(guard.includeWith { if (it.variableName == "a") true else error("Unneeded sibling") })
    }

    @Test
    fun `joining empty parent projections preserves guarded root type check provenance`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Parent @resolver(result: {}) }
            type Parent { child: Child }
            type Child { parent: Parent @parent }
            """.trimIndent(),
        )
        val parent = fixture.schemas.loweredSchema.requireObjectField("Parent", "child").containingDef
        val parsed = fixture.schemas.fragmentFrom(
            "fragment F on Parent { child @include(if: ${'$'}a) { parent { __typename } } child @include(if: ${'$'}b) { parent { __typename } } }",
        ).subselections
        val emptyProjections = parsed.flatMap { child ->
            val backedge = child.subselections.single()
            selectionForestOf(
                Selection.of(
                    child.key,
                    child.possibleTypes,
                    selectionForestOf(Selection.of(backedge.key, backedge.possibleTypes, selectionForestOf(), backedge.inclusionCondition)),
                    child.inclusionCondition,
                )
            )
        }
        val lifted = Demand.checked(emptyProjections, typeCheckDemanded = false).liftParentConstructionDemand(fixture.assumptions)
        assertTrue(lifted.values.isEmpty())
        assertTrue(lifted.typeCheckDemanded, "An empty checked projection still demands the ancestor type check")
        assertTrue(lifted.checked.merge(parent).isEmpty())
        for (a in listOf(false, true)) for (b in listOf(false, true)) {
            assertEquals(a || b, lifted.typeCheckCondition.includeWith { if (it.variableName == "a") a else b })
        }
        val raw = Demand.unchecked(emptyProjections).liftParentConstructionDemand(fixture.assumptions)
        assertTrue(raw.values.isEmpty())
        assertEquals(false, raw.typeCheckDemanded)
        assertSame(InclusionCondition.Never, raw.typeCheckCondition)
    }

    private fun ObjectSelectionForest.field(name: String) = byKey().values.single { selection -> selection.key.field.name == name }

    private fun ObjectSelectionForest.fieldNames(): Set<String> = keys().mapTo(linkedSetOf()) { key -> key.field.name }
}
