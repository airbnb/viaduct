package semantics.contract

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import model.ListEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.operationSelectionsFrom
import model.outputValue
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

/** Contract for engine-provided parent backedges and transitive ancestor demand. */
interface ParentFieldResolverContract : ResolverContract {
    val usesSingularQueryOERForParentDemand: Boolean
        get() = false

    @Test
    fun `Query-rooted parent demand activates one ancestor resolver and its Query input`() {
        assumeTrue(usesSingularQueryOERForParentDemand)
        val invocations = linkedMapOf<String, Int>()
        val observer = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                invocations.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { container: Container!, token: String!, result: String! }
                    type Container { child: Child!, ancestor: String! }
                    type Child { parent: Container! @parent, value: String! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val emptyQuery = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.requireObjectField("Query", "container") to
                            fieldResolverOf(emptyQuery) { _, _ -> schema.objectOf("Container") },
                        schema.requireObjectField("Query", "token") to
                            fieldResolverOf(emptyQuery) { _, _ -> "ready" },
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(
                                objectFragment = emptyQuery,
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ResultQuery on Query { " +
                                            "container { child { value } } }",
                                    ),
                            ) { _, queryValue, _ ->
                                val container =
                                    assertIs<EngineObjectData.Sync>(
                                        queryValue.outputValue("container"),
                                    )
                                val child =
                                    assertIs<EngineObjectData.Sync>(container.outputValue("child"))
                                child.outputValue("value")
                            },
                        schema.requireObjectField("Container", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ ->
                                schema.objectOf("Child")
                            },
                        schema.requireObjectField("Container", "ancestor") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Container"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment AncestorQuery on Query { token }",
                                    ),
                            ) { _, queryValue, _ -> queryValue.outputValue("token") },
                        schema.requireObjectField("Child", "value") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ValueInput on Child { parent { ancestor } }",
                                ),
                            ) { input, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                parent.outputValue("ancestor")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")

        val result =
            resolveAndValidate(
                world,
                "query { result }",
                resolverObserver = observer,
            )

        assertEquals("ready", result.getCell(resultKey).get())
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
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Root }
                    type Root { child: Child, ancestorValue: Int }
                    type Child { parent: Root @parent, grandchild: Grandchild, marker: String }
                    type Grandchild { parent: Child @parent, greatGrandchild: GreatGrandchild }
                    type GreatGrandchild { parent: Grandchild @parent, result: Int }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "root") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Root")
                            },
                        schema.requireObjectField("Root", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Root")) { _, _ ->
                                schema.objectOf("Child")
                            },
                        schema.requireObjectField("Child", "grandchild") to
                            fieldResolverOf(schema.emptyFragmentOf("Child")) { _, _ ->
                                schema.objectOf("Grandchild")
                            },
                        schema.requireObjectField("Child", "marker") to
                            fieldResolverOf(schema.emptyFragmentOf("Child")) { _, _ -> "child" },
                        schema.requireObjectField("Grandchild", "greatGrandchild") to
                            fieldResolverOf(schema.emptyFragmentOf("Grandchild")) { _, _ ->
                                schema.objectOf("GreatGrandchild")
                            },
                        schema.requireObjectField("Root", "ancestorValue") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Root { " +
                                        "child { parent { child { marker } } } }",
                                ),
                            ) { input, _ ->
                                val child =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("child"))
                                val parent =
                                    assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                                val revisitedChild =
                                    assertIs<EngineObjectData.Sync>(parent.outputValue("child"))
                                assertEquals("child", revisitedChild.outputValue("marker"))
                                42
                            },
                        schema.requireObjectField("GreatGrandchild", "result") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on GreatGrandchild { " +
                                        "parent { parent { parent { ancestorValue } } } }",
                                ),
                            ) { input, _ ->
                                val grandchild =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                val child =
                                    assertIs<EngineObjectData.Sync>(grandchild.outputValue("parent"))
                                val root =
                                    assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                                root.outputValue("ancestorValue")
                            },
                    )
                },
            ).assumptions

        val result =
            resolveAndValidate(
                world,
                "query { root { child { grandchild { greatGrandchild { result } } } } }",
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
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Root }
                    type Root { child: Child }
                    type Child { parent: Root @parent, result: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "root") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Root")
                            },
                        schema.requireObjectField("Root", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Root")) { _, _ ->
                                schema.objectOf("Child")
                            },
                        schema.requireObjectField("Child", "result") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Child { " +
                                        "parent { child { parent { __typename } } } }",
                                ),
                            ) { input, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                val child =
                                    assertIs<EngineObjectData.Sync>(parent.outputValue("child"))
                                val revisitedParent =
                                    assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                                revisitedParent.outputValue("V_A_typename")
                            },
                    )
                },
            ).assumptions

        val result = resolveAndValidate(world, "query { root { child { result } } }")
        val root =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "root")).get(),
            )
        val child =
            assertIs<ObjectEngineResult>(
                root.getCell(world.schema.contractKey("Root", "child")).get(),
            )

        assertEquals(
            "Root",
            child.getCell(world.schema.contractKey("Child", "result")).get(),
        )
    }

    @Test
    fun `recursive same-type parent chain retains immediate occurrence identity`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Link }
                    type Link { name: String, child: Link, parent: Link @parent }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "root") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Link") { "name" setTo "root" }
                            },
                        schema.requireObjectField("Link", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Link")) { _, _ ->
                                schema.objectOf("Link") { "name" setTo "child" }
                            },
                    )
                },
            ).assumptions

        val result =
            resolveAndValidate(
                world,
                "query { root { child { child { parent { parent { name } } } } } }",
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
        val parentKey = world.schema.contractKey("Link", "parent")

        assertSame(firstChild, secondChild.getCell(parentKey).get())
        assertSame(root, firstChild.getCell(parentKey).get())
    }

    @Test
    fun `resolver produced parent field cannot replace the structural parent`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { company: Company }
                    type Company { name: String, user: User }
                    type User { parent: Company @parent, companyName: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val unrelatedCompany =
                        schema.objectOf("Company") {
                            "name" setTo "unrelated"
                        }
                    mapOf(
                        schema.requireObjectField("Query", "company") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Company") {
                                    "name" setTo "structural"
                                    "user" setTo
                                        schema.objectOf("User") {
                                            "parent" setTo unrelatedCompany
                                        }
                                }
                            },
                        schema.requireObjectField("User", "companyName") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { parent { name } }",
                                ),
                            ) { input, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(
                                        input.outputValue("parent"),
                                    )
                                parent.outputValue("name")
                            },
                    )
                },
            ).assumptions

        val result =
            resolveAndValidate(
                world,
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
            "structural",
            user.getCell(world.schema.contractKey("User", "companyName")).get(),
        )
    }

    @Test
    fun `resolver produced parent field is not traversed as a passive child`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Root }
                    type Root { child: Child }
                    type Child { parent: Root @parent, grandchild: Grandchild }
                    type Grandchild { parent: Child @parent }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "root") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Root")
                            },
                        schema.requireObjectField("Root", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Root")) { _, _ ->
                                schema.objectOf("Child") {
                                    "parent" setTo schema.objectOf("Root")
                                }
                            },
                        schema.requireObjectField("Child", "grandchild") to
                            fieldResolverOf(schema.emptyFragmentOf("Child")) { _, _ ->
                                schema.objectOf("Grandchild") {
                                    "parent" setTo schema.objectOf("Child")
                                }
                            },
                    )
                },
            ).assumptions

        val result =
            resolveAndValidate(
                world,
                "query { root { child { grandchild { parent { parent { __typename } } } } } }",
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
    }

    @Test
    fun `resolver input closes sibling demand reached through a child parent`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { child: Child, sibling: Sibling, result: String }
                    type Child { parent: Query @parent }
                    type Sibling { label: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Child")
                            },
                        schema.requireObjectField("Query", "sibling") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Sibling") { "label" setTo "ready" }
                            },
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { child { parent { sibling { __typename } } } }",
                                ),
                            ) { input, _ ->
                                val child = assertIs<EngineObjectData.Sync>(input.outputValue("child"))
                                val parent = assertIs<EngineObjectData.Sync>(child.outputValue("parent"))
                                val sibling =
                                    assertIs<EngineObjectData.Sync>(parent.outputValue("sibling"))
                                sibling.outputValue("V_A_typename")
                            },
                    )
                },
            ).assumptions

        val result = resolveAndValidate(world, "query { result }")

        assertEquals("Sibling", result.getCell(world.schema.contractKey("Query", "result")).get())
    }

    @Test
    fun `child resolver parent input closes active ancestor demand`() {
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { child: Child, sibling: String }
                    type Child { parent: Query @parent, value: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "child") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Child")
                            },
                        schema.requireObjectField("Query", "sibling") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> "ready" },
                        schema.requireObjectField("Child", "value") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Child { parent { sibling } }",
                                ),
                            ) { input, _ ->
                                val parent = assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                parent.outputValue("sibling")
                            },
                    )
                },
            ).assumptions

        val result = resolveAndValidate(world, "query { child { value } }")
        val child =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "child")).get(),
            )

        assertEquals("ready", child.getCell(world.schema.contractKey("Child", "value")).get())
    }

    @Test
    fun `materialized parent EOD is a copy of the ancestor value`() {
        lateinit var sourceParent: EngineObjectData.Sync
        val world =
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
                    sourceParent = schema.objectOf("Company") { "name" setTo "Airbnb" }
                    mapOf(
                        schema.requireObjectField("Query", "company") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                sourceParent
                            },
                        schema.requireObjectField("Company", "user") to
                            fieldResolverOf(schema.emptyFragmentOf("Company")) { _, _ ->
                                schema.objectOf("User")
                            },
                        schema.requireObjectField("User", "label") to
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
            ).assumptions

        val result = resolveAndValidate(world, "query { company { user { label } } }")
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
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { organization: Organization }
                    type Organization { name: String, company: Company }
                    type Company { parent: Organization @parent, users: [[User]] }
                    type User { parent: Company @parent, organizationName: String }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "organization") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Organization") { "name" setTo "Engineering" }
                            },
                        schema.requireObjectField("Organization", "company") to
                            fieldResolverOf(schema.emptyFragmentOf("Organization")) { _, _ ->
                                schema.objectOf("Company")
                            },
                        schema.requireObjectField("Company", "users") to
                            fieldResolverOf(schema.emptyFragmentOf("Company")) { _, _ ->
                                listOf(listOf(schema.objectOf("User"), schema.objectOf("User")))
                            },
                        schema.requireObjectField("User", "organizationName") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { parent { parent { name } } }",
                                ),
                            ) { input, _ ->
                                val company = assertIs<EngineObjectData.Sync>(input.outputValue("parent"))
                                val organization =
                                    assertIs<EngineObjectData.Sync>(company.outputValue("parent"))
                                organization.outputValue("name")
                            },
                    )
                },
            ).assumptions
        val selections =
            world.operationSelectionsFrom(
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
                "Engineering",
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
