package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/** Contract for engine-provided parent backedges and transitive ancestor demand. */
interface ParentFieldResolverContract : ResolverContract {
    val usesSingularQueryOERForParentDemand: Boolean
        get() = false

    @TestFactory
    fun `parent demand retains argument ownership on a selection spine`() =
        listOf(false, true).map { composed ->
            dynamicTest(if (composed) "composed consumer" else "direct consumer") {
                val invocations = linkedMapOf<String, Int>()
                val observer = object : CorrectnessResolverObserver() {
                    override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                        super.onResolverInvocation(observation)
                        invocations.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
                        if (observation.field.name == "branch") {
                            assertEquals(3, observation.arguments.fieldValues.getValue("x"))
                        }
                    }
                }
                val fixture = TestWorld.fromDSL(
                    selectiveResolvers = selectiveResolvers,
                    schemaSDL = """
                        extend type Query { root: Root @resolver(result: {}) }
                        type Root {
                          middle(seed: Int): Int
                            @resolver(of: "branch(x: ${'$'}seed) { child { leaf } }", result: "value(branch.child.leaf)")
                          composed: Int @resolver(of: "middle(seed: 3)", result: "value(middle)")
                          branch(x: Int): Branch @resolver(result: {seed: 11})
                        }
                        type Branch { seed: Int, child: Child @resolver(result: {}) }
                        type Child {
                          parent: Branch @parent
                          leaf: Int @resolver(of: "parent { seed }", result: "value(parent.seed)")
                        }
                    """.trimIndent(),
                )
                val field = if (composed) "composed" else "middle"
                val selection = if (composed) field else "middle(seed: 3)"
                val result = resolveAndValidate(fixture, "{ root { $selection } }", resolutionObserver = observer, record = false)
                val root = assertIs<ObjectEngineResult>(result.getCell(fixture.schema.contractKey("Query", "root")).get())
                val key = if (composed) fixture.schema.contractKey("Root", field) else ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Root", field), mapOf("seed" to 3))
                assertEquals(11, root.getCell(key).get())
                assertEquals(
                    mapOf("root" to 1, "middle" to 1, "branch" to 1, "child" to 1, "leaf" to 1) + if (composed) mapOf("composed" to 1) else emptyMap(),
                    invocations,
                )
            }
        }

    @Test
    fun `parent demand binds an ancestor provider for an unsupplied field`() {
        val resolution = resolveAncestorProvider(supplied = false)

        assertEquals(18, resolution.value)
        assertEquals(1, resolution.providerCalls)
        assertEquals(
            mapOf("root" to 1, "child" to 1, "result" to 1, "lifted" to 1, "seed" to 1, "marker" to 1, "leaf" to 1),
            resolution.invocations,
        )
    }

    @Test
    fun `parent demand bypasses an ancestor provider for a supplied field`() {
        val resolution = resolveAncestorProvider(supplied = true)

        assertEquals(99, resolution.value)
        assertEquals(0, resolution.providerCalls)
        assertEquals(mapOf("root" to 1, "child" to 1, "result" to 1), resolution.invocations)
    }

    private fun resolveAncestorProvider(supplied: Boolean): AncestorProviderResolution {
        var providerCalls = 0
        val invocations = linkedMapOf<String, Int>()
        val observer = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                invocations.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
            }
        }
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL =
                """
                directive @parent on FIELD_DEFINITION
                type Query { root: Root marker: Int leaf: Int }
                type Root { child: Child lifted: Int seed(value: Int): Int }
                type Child { parent: Root @parent result: Int }
                """.trimIndent(),
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "root") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        schema.objectOf("Root") { if (supplied) "lifted" setTo 99 }
                    },
                    schema.requireObjectField("Root", "child") to fieldResolverOf(schema.emptyFragmentOf("Root")) { _, _ -> schema.objectOf("Child") },
                    schema.requireObjectField("Child", "result") to fieldResolverOf(
                        schemas.fragmentFrom("fragment Input on Child { parent { lifted } }"),
                    ) { input, _ -> assertIs<EngineObjectData.Sync>(input.outputValue("parent")).outputValue("lifted") },
                    schema.requireObjectField("Root", "lifted") to fieldResolverOf(
                        objectFragment = schemas.fragmentFrom("fragment Input on Root { seed(value: ${'$'}provided) }"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { marker }"),
                    ) { input, query, _ -> (input.outputValue("seed") as Int) + (query.outputValue("marker") as Int) }
                        .withVariablesProvider(setOf("provided")) {
                            providerCalls++
                            mapOf("provided" to 7)
                        },
                    schema.requireObjectField("Root", "seed") to fieldResolverOf(schema.emptyFragmentOf("Root")) { _, arguments -> arguments.fieldValues.getValue("value") },
                    schema.requireObjectField("Query", "marker") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { leaf }"),
                    ) { _, query, _ -> query.outputValue("leaf") },
                    schema.requireObjectField("Query", "leaf") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                )
            },
        )

        val result = resolveAndValidate(fixture, "{ root { child { result } } }", resolutionObserver = observer)
        val root = assertIs<ObjectEngineResult>(result.getCell(fixture.schema.contractKey("Query", "root")).get())
        val child = assertIs<ObjectEngineResult>(root.getCell(fixture.schema.contractKey("Root", "child")).get())
        return AncestorProviderResolution(
            value = child.getCell(fixture.schema.contractKey("Child", "result")).get(),
            providerCalls = providerCalls,
            invocations = invocations.toMap(),
        )
    }

    @Test
    fun `Query-rooted parent demand activates one ancestor resolver and its Query input`() {
        assumeTrue(usesSingularQueryOERForParentDemand)
        val invocations = linkedMapOf<String, Int>()
        val observer = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                invocations.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container! @resolver(result: {})
                      token: Int! @resolver(result: 7)
                      result: Int! @resolver(qf: "container { child { value } }", result: "value(query:container.child.value)")
                    }
                    type Container {
                      child: Child! @resolver(result: {})
                      ancestor: Int! @resolver(qf: "token", result: "value(query:token)")
                    }
                    type Child {
                      parent: Container! @parent
                      value: Int! @resolver(of: "parent { ancestor }", result: "value(parent.ancestor)")
                    }
                    """.trimIndent(),
            )
        val resultKey = testWorld.schema.contractKey("Query", "result")

        val result =
            resolveAndValidate(
                testWorld,
                "query { result }",
                resolutionObserver = observer,
            )

        assertEquals(7, result.getCell(resultKey).get())
        assertEquals(setOf(resultKey), result.keys)
        assertEquals(
            mapOf(
                "result" to 1,
                "container" to 1,
                "child" to 1,
                "value" to 1,
                "ancestor" to 1,
                "token" to 1,
            ),
            invocations,
        )
    }

    @Test
    fun `parent demand waits for a revisited active child field`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "ancestorValue") {
                    val input = observation.input
                    val child = assertIs<EngineObjectData.Sync>(input.outputValue("child"))
                    val parent = assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                    val revisitedChild = assertIs<EngineObjectData.Sync>(parent.outputValue("child"))
                    assertEquals(7, revisitedChild.outputValue("marker"))
                }
            }
        }
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { root: Root @resolver(result: {}) }
                    type Root {
                      child: Child @resolver(result: {})
                      ancestorValue: Int
                        @resolver(of: "child { parent { child { marker } } }", result: 42)
                    }
                    type Child {
                      parent: Root @parent
                      grandchild: Grandchild @resolver(result: {})
                      marker: Int @resolver(result: 7)
                    }
                    type Grandchild {
                      parent: Child @parent
                      greatGrandchild: GreatGrandchild @resolver(result: {})
                    }
                    type GreatGrandchild {
                      parent: Grandchild @parent
                      result: Int
                        @resolver(of: "parent { parent { parent { ancestorValue } } }", result: "value(parent.parent.parent.ancestorValue)")
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result =
            resolveAndValidate(
                worldFixture,
                "query { root { child { grandchild { greatGrandchild { result } } } } }",
                resolutionObserver = invocationObserver,
            )
        val root =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "root")).get(),
            )
        val child =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Root", "child")).get(),
            )
        val grandchild =
            assertIs<ObjectEngineResult>(
                child.getCell(world.schema.contractKey("Child", "grandchild")).get(),
            )
        val greatGrandchild =
            assertIs<ObjectEngineResult>(
                grandchild
                    .getCell(world.schema.contractKey("Grandchild", "greatGrandchild"))
                    .get(),
            )

        assertEquals(
            42,
            greatGrandchild
                .getCell(world.schema.contractKey("GreatGrandchild", "result"))
                .get(),
        )
    }

    @Test
    fun `parent demand can revisit its child before deepening the same parent`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "result") {
                    val input = observation.input
                    val parent = assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                    val child = assertIs<EngineObjectData.Sync>(parent.outputValue("child"))
                    val revisitedParent = assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                    assertEquals("Root", revisitedParent.outputValue("V_A_typename"))
                }
            }
        }
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { root: Root @resolver(result: {}) }
                    type Root { child: Child @resolver(result: {}) }
                    type Child {
                      parent: Root @parent
                      result: Int @resolver(of: "parent { child { parent { __typename } } }", result: 1)
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result = resolveAndValidate(worldFixture, "query { root { child { result } } }", resolutionObserver = invocationObserver)
        val root =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "root")).get(),
            )
        val child =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Root", "child")).get(),
            )

        assertEquals(
            1,
            child.getCell(world.schema.contractKey("Child", "result")).get(),
        )
    }

    @Test
    fun `recursive same-type parent chain retains immediate occurrence identity`() {
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { root: Link @resolver(result: {name: 1}) }
                    type Link {
                      name: Int
                      child: Link @resolver(result: {name: 2})
                      parent: Link @parent
                      ancestorName: Int @resolver(of: "parent { parent { name } }", result: "value(parent.parent.name)")
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result =
            resolveAndValidate(
                worldFixture,
                "query { root { child { child { ancestorName } } } }",
            )
        val root =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "root")).get(),
            )
        val firstChild =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Link", "child")).get(),
            )
        val secondChild =
            assertIs<ObjectEngineResult>(
                firstChild.getCell(world.schema.contractKey("Link", "child")).get(),
            )
        val parentKey = worldFixture.schema.contractKey("Link", "parent")

        assertSame(firstChild, secondChild.getCell(parentKey).get())
        assertSame(root, firstChild.getCell(parentKey).get())
        assertEquals(1, secondChild.getCell(world.schema.contractKey("Link", "ancestorName")).get())
    }

    @Test
    fun `resolver produced parent field cannot replace the structural parent`() {
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      company: Company @resolver(result: {name: 1, user: {parent: {name: 2}}})
                    }
                    type Company { name: Int, user: User }
                    type User {
                      parent: Company @parent
                      companyName: Int @resolver(of: "parent { name }", result: "value(parent.name)")
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result =
            resolveAndValidate(
                worldFixture,
                "query { company { user { companyName } } }",
            )
        val company =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "company")).get(),
            )
        val user =
            assertIs<ObjectEngineResult>(
                company.getCell(world.schema.contractKey("Company", "user")).get(),
            )

        assertSame(
            company,
            user.getCell(world.schema.contractKey("User", "parent")).get(),
        )
        assertEquals(
            1,
            user.getCell(world.schema.contractKey("User", "companyName")).get(),
        )
    }

    @Test
    fun `resolver produced parent field is not traversed as a passive child`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "lineage") {
                    val input = observation.input
                    val parent = assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                    assertEquals("Root", assertIs<EngineObjectData.Sync>(parent.outputValue("parent")).type.name)
                }
            }
        }
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { root: Root @resolver(result: {}) }
                    type Root { child: Child @resolver(result: {parent: {}}) }
                    type Child {
                      parent: Root @parent
                      grandchild: Grandchild @resolver(result: {parent: {}})
                    }
                    type Grandchild {
                      parent: Child @parent
                      lineage: Int @resolver(of: "parent { parent { __typename } }", result: 1)
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result =
            resolveAndValidate(
                worldFixture,
                "query { root { child { grandchild { lineage } } } }",
                resolutionObserver = invocationObserver,
            )
        val root =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "root")).get(),
            )
        val child =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Root", "child")).get(),
            )
        val grandchild =
            assertIs<ObjectEngineResult>(
                child.getCell(world.schema.contractKey("Child", "grandchild")).get(),
            )
        val grandchildParent =
            assertIs<ObjectEngineResult>(
                grandchild.getCell(world.schema.contractKey("Grandchild", "parent")).get(),
            )

        assertSame(child, grandchildParent)
        assertSame(
            root,
            grandchildParent.getCell(world.schema.contractKey("Child", "parent")).get(),
        )
        assertEquals(1, grandchild.getCell(world.schema.contractKey("Grandchild", "lineage")).get())
    }

    @Test
    fun `resolver input closes sibling demand reached through a child parent`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "result") {
                    val input = observation.input
                    val child = assertIs<EngineObjectData.Sync>(input.outputValue("child"))
                    val parent = assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                    val sibling = assertIs<EngineObjectData.Sync>(parent.outputValue("sibling"))
                    assertEquals("Sibling", sibling.outputValue("V_A_typename"))
                }
            }
        }
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { root: Root @resolver(result: {}) }
                    type Root {
                      child: Child @resolver(result: {})
                      sibling: Sibling @resolver(result: {label: 7})
                      result: Int @resolver(of: "child { parent { sibling { __typename } } }", result: 1)
                    }
                    type Child { parent: Root @parent }
                    type Sibling { label: Int }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result = resolveAndValidate(worldFixture, "query { root { result } }", resolutionObserver = invocationObserver)
        val root = assertIs<ObjectEngineResult>(result.getCell(world.schema.contractKey("Query", "root")).get())

        assertEquals(1, root.getCell(world.schema.contractKey("Root", "result")).get())
    }

    @Test
    fun `child resolver parent input closes active ancestor demand`() {
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { root: Root @resolver(result: {}) }
                    type Root {
                      child: Child @resolver(result: {})
                      sibling: Int @resolver(result: 7)
                    }
                    type Child {
                      parent: Root @parent
                      value: Int @resolver(of: "parent { sibling }", result: "value(parent.sibling)")
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions

        val result = resolveAndValidate(worldFixture, "query { root { child { value } } }")
        val root = assertIs<ObjectEngineResult>(result.getCell(world.schema.contractKey("Query", "root")).get())
        val child =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Root", "child")).get(),
            )

        assertEquals(7, child.getCell(world.schema.contractKey("Child", "value")).get())
    }

    @Test
    fun `materialized parent EOD is a copy of the ancestor value`() {
        lateinit var sourceParent: EngineObjectData.Sync
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { company: Company }
                    type Company { name: String, user: User }
                    type User { parent: Company @parent, label: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    sourceParent = schema.loweredSchema.objectOf("Company") { "name" setTo "Airbnb" }
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "company") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                sourceParent
                            },
                        schema.loweredSchema.requireObjectField("Company", "user") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Company")) { _, _ ->
                                schema.loweredSchema.objectOf("User")
                            },
                        schema.loweredSchema.requireObjectField("User", "label") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { parent { name } }",
                                ),
                            ) { input, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                assertNotSame(sourceParent, parent)
                                parent.outputValue("name")
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveAndValidate(worldFixture, "query { company { user { label } } }")
        val company =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "company")).get(),
            )
        val user =
            assertIs<ObjectEngineResult>(
                company.getCell(world.schema.contractKey("Company", "user")).get(),
            )

        assertEquals("Airbnb", user.getCell(world.schema.contractKey("User", "label")).get())
        assertSame(company, user.getCell(world.schema.contractKey("User", "parent")).get())
    }

    @Test
    fun `parent demand crosses nested-list child fields and reaches grandparents`() {
        val worldFixture =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query { organization: Organization @resolver(result: {name: 7}) }
                    type Organization { name: Int, company: Company @resolver(result: {}) }
                    type Company {
                      parent: Organization @parent
                      users: [[User]] @resolver(result: [[{}, {}]])
                    }
                    type User {
                      parent: Company @parent
                      organizationName: Int
                        @resolver(of: "parent { parent { name } }", result: "value(parent.parent.name)")
                    }
                    """.trimIndent(),
            )
        val world = worldFixture.assumptions
        val selections =
            worldFixture.schemas.operationSelectionsFrom(
                "query { organization { company { users { organizationName } } } }",
            )
        val result = resolveAndValidate(world, selections)
        val organization =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "organization")).get(),
            )
        val company =
            assertIs<ObjectEngineResult>(
                organization.getCell(world.schema.contractKey("Organization", "company")).get(),
            )
        val outer =
            assertIs<ListEngineResult>(
                company.getCell(world.schema.contractKey("Company", "users")).get(),
            )
        val inner = assertIs<ListEngineResult>(outer.single().get())

        inner.forEach { userCell ->
            val user = assertIs<ObjectEngineResult>(userCell.get())
            assertEquals(
                7,
                user.getCell(world.schema.contractKey("User", "organizationName")).get(),
            )
            assertSame(
                company,
                user.getCell(world.schema.contractKey("User", "parent")).get(),
            )
        }
        assertSame(
            organization,
            company.getCell(world.schema.contractKey("Company", "parent")).get(),
        )
    }
}

private data class AncestorProviderResolution(
    val value: EngineResult?,
    val providerCalls: Int,
    val invocations: Map<String, Int>,
)
