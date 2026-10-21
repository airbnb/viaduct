package viaduct.engine.runtime2.resolution

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.bootstrap.ParentConstructionDemandCompiler
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.liftParentConstructionDemand as liftDynamicParentDemand
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23
import viaduct.graphql.schema.ViaductSchema

class ParentConstructionDemandTest : ResolutionDispatcherResource {
    @Test
    fun `shared field and type checkers keep each registry's parent requirements`() =
        runTest {
            assertSharedCheckerOwnership { resolve23(it) }
        }

    @Test
    fun `production shared checkers keep each registry's parent requirements`() =
        runTest {
            assertSharedCheckerOwnership { resolveWithTestDispatcher(it) }
        }

    private suspend fun assertSharedCheckerOwnership(executeResolution: suspend SharedOperationContext<*>.(SelectionForest) -> ObjectEngineResult) {
        for (fieldChecker in listOf(false, true)) {
            val fixture = TestWorld.fromSDL(
                """
            directive @parent on FIELD_DEFINITION
            type Query { root: Container }
            type Container { branch: Root checked: Int }
            type Root { a: Int b: Int child: Child }
            type Child { parent: Root @parent demand: Int }
                """.trimIndent(),
            )
            val schema = fixture.schema
            val query = schema.requireQueryTypeDef()
            val rootType = schema.requireType("Container") as ViaductSchema.Object
            val checked = schema.requireObjectField("Container", "checked")
            val templates = mapOf(
                "input" to ResolverFragmentTemplates(
                    objectFragmentTemplate = fixture.schemas.fragmentFrom("fragment F on Container { branch { child { demand } } }").materializeSelections,
                    queryFragmentTemplate = materializeSelectionForestOf(),
                ),
            )
            val checkerCalls = mutableListOf<Int>()

            fun check(inputs: Map<String, CheckerInput>): CheckerResult {
                val branch = inputs.getValue("input").objectValue.get("branch") as EngineObjectData.Sync
                val child = branch.get("child") as EngineObjectData.Sync
                checkerCalls += child.get("demand") as Int
                return CheckerResult.Success
            }
            val sharedFieldChecker = FieldCheckerResolver.of(checked, query, templates) { _, inputs, _ -> check(inputs) }
            val sharedTypeChecker = TypeCheckerResolver.of(rootType, query, templates) { inputs, _ -> check(inputs) }

            fun registry(required: String): Pair<ResolverRegistry, MutableList<String>> {
                val calls = mutableListOf<String>()
                val definitions = buildMap<ViaductSchema.Field, FieldResolverDefinition> {
                    for ((type, field, value) in listOf(Triple("Root", "a", 10), Triple("Root", "b", 20), Triple("Container", "checked", 1))) {
                        put(
                            schema.requireObjectField(type, field),
                            fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ ->
                                calls += "$type.$field"
                                value
                            }
                        )
                    }
                    for ((type, field, output) in listOf(Triple("Query", "root", "Container"), Triple("Container", "branch", "Root"), Triple("Root", "child", "Child"))) {
                        put(
                            schema.requireObjectField(type, field),
                            fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ ->
                                calls += "$type.$field"
                                engineObjectDataOf(schema.requireType(output) as ViaductSchema.Object)
                            }
                        )
                    }
                    put(
                        schema.requireObjectField("Child", "demand"),
                        fieldResolverOf(fixture.schemas.fragmentFrom("fragment F on Child { parent { $required } }")) { input, _ ->
                            calls += "Child.demand"
                            (input.get("parent") as EngineObjectData.Sync).get(required)
                        },
                    )
                }
                return resolverRegistryOf(
                    fixture.schemas,
                    emptyMap(),
                    definitions,
                    fieldCheckers = if (fieldChecker) mapOf(checked to sharedFieldChecker) else emptyMap(),
                    typeCheckers = if (fieldChecker) emptyMap() else mapOf(rootType to sharedTypeChecker),
                    variableProviders = emptyMap(),
                ) to calls
            }

            suspend fun execute(
                registry: ResolverRegistry,
                calls: MutableList<String>,
                required: String,
                precomputed: Boolean
            ) {
                calls.clear()
                checkerCalls.clear()
                val operation = SharedOperationContext.create(Assumptions.of(schema, registry, usePrecomputedConstructionDemand = precomputed))
                val result = executeResolution(operation, fixture.schemas.fragmentFrom("fragment F on Query { root { checked } }").subselections)

                fun ObjectEngineResult.value(
                    type: String,
                    field: String
                ) = getCell(ObjectEngineResult.GroundKey.of(schema.requireObjectField(type, field), emptyMap())).value.get()
                val rootResult = result.value("Query", "root") as ObjectEngineResult
                assertEquals(1, rootResult.value("Container", "checked"))
                assertEquals(listOf("Container.checked", "Container.branch", "Child.demand", "Query.root", "Root.$required", "Root.child").sorted(), calls.sorted())
                assertEquals(listOf(if (required == "a") 10 else 20), checkerCalls)
            }

            for (precomputed in listOf(false, true)) {
                val (registryA, callsA) = registry("a")
                execute(registryA, callsA, "a", precomputed)
                val (registryB, callsB) = registry("b")
                execute(registryA, callsA, "a", precomputed)
                execute(registryB, callsB, "b", precomputed)
                execute(registryA, callsA, "a", precomputed)
            }
        }
    }

    @Test
    fun `request matching prunes unrelated recursive branches in a schema with parent demand`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query {
              root: Root @resolver(result: {})
              tree: Tree @resolver(result: {})
            }
            type Root { seed: Int child: Child @resolver(result: {}) }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { seed }", result: 1)
              plain: Int
            }
            type Tree { next: Tree value: Int }
            """.trimIndent(),
        )
        val registry = fixture.resolverRegistry
        val query = fixture.schema.requireQueryTypeDef()
        assertEquals(setOf("root"), registry.parentSelectionDemand(query).keys)
        val tree = fixture.schema.requireObjectField("Tree", "value").containingDef
        assertTrue(registry.parentSelectionDemand(tree).isEmpty())

        val visitedTypes = mutableListOf<ViaductSchema.Object>()
        val preparedOnly = object : ResolverRegistry by registry {
            override fun parentSelectionDemand(type: ViaductSchema.Object) =
                registry.parentSelectionDemand(type).also {
                    assertFalse(type == tree, "Unrelated recursive branches must not be traversed")
                    visitedTypes += type
                }

            override fun contains(field: ViaductSchema.ObjectField): Boolean = error("Runtime resolver lookup: $field")

            override fun resolver(field: ViaductSchema.ObjectField) = error("Runtime resolver lookup: $field")

            override fun fieldChecker(field: ViaductSchema.ObjectField) = error("Runtime checker lookup: $field")

            override fun typeChecker(type: ViaductSchema.Object) = error("Runtime checker lookup: $type")
        }
        val world = Assumptions.of(fixture.schema, preparedOnly)
        val unrelated = fixture.schemas.fragmentFrom("fragment F on Query { tree { next { next { value } } } }").subselections
        assertTrue(Demand.checked(unrelated).liftPrecomputedParentConstructionDemand(world).values.isEmpty())
        assertEquals(listOf(query), visitedTypes)

        for (field in listOf("plain", "derived")) {
            val input = fixture.schemas.fragmentFrom("fragment F on Query { root { child { $field } } }").subselections
            for (checked in listOf(false, true)) {
                val demand = if (checked) Demand.checked(input) else Demand.unchecked(input)
                val actual = demand.liftPrecomputedParentConstructionDemand(world)
                assertSameDemand(demand.liftDynamicParentDemand(fixture.assumptions), actual)
                assertEquals(field == "plain", actual.values.isEmpty())
            }
        }
    }

    @Test
    fun `eager registry summaries compose parent demand without reading fixed resolver fragments`() {
        val fixture = parentSummaryFixture()
        val registry = fixture.resolverRegistry
        val result = fixture.schema.requireObjectField("Child", "result")
        val parent = fixture.schema.requireObjectField("Child", "parent")
        val template = registry.resolver(result).objectParentDemand
        assertTrue(template.usesParent)
        assertFalse(registry.resolver(result).queryParentDemand.usesParent)
        val summary = template.summary
        assertTrue(summary.localDemand.values.isEmpty())
        assertEquals(setOf(parent), summary.requests.keys)
        val parentType = fixture.schema.requireObjectField("Parent", "bridge").containingDef
        assertEquals(setOf("bridge"), summary.requests.getValue(parent).demand.checkedDemand.merge(parentType).fieldNames())

        val consumer = fixture.schema.requireObjectField("Query", "consumer")
        val consumerResolver = registry.resolver(consumer)
        assertFalse(consumerResolver.objectParentDemand.usesParent)
        assertTrue(consumerResolver.objectParentDemand.mayInduceParentDemand)
        assertFalse(consumerResolver.queryParentDemand.mayInduceParentDemand)
        val static = requireNotNull(requireNotNull(registry.precomputedConstructionDemand(consumer)).queryRootDemand)
        val expectedStatic = Demand.checked(static.reusableDemand).liftParentConstructionDemand(fixture.assumptions)
        assertSameDemand(expectedStatic, static.reusableParentDemand.parentDemand())
        parentSummaryInputs(fixture).forEach { input ->
            for (checked in listOf(false, true)) {
                val demand = if (checked) Demand.checked(input) else Demand.unchecked(input)
                val expected = demand.liftParentConstructionDemand(fixture.assumptions)
                val actual = demand.liftPrecomputedParentConstructionDemand(fixture.assumptions)
                assertSameDemand(expected, actual)
            }
        }
    }

    @Test
    fun `parent summaries are immutable across concurrent worlds and incoming provenance`() {
        val fixture = parentSummaryFixture()
        val result = fixture.schema.requireObjectField("Child", "result")
        val template = fixture.resolverRegistry.resolver(result).objectParentDemand
        val demands = parentSummaryInputs(fixture).flatMap { listOf(Demand.checked(it), Demand.unchecked(it)) }
        val expected = demands.map { it.liftParentConstructionDemand(fixture.assumptions) }
        val executor = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        try {
            val tasks = (0 until 4).map { worker ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(24) { iteration ->
                        val index = (iteration + worker) % demands.size
                        val world = fixture.newAssumptions()
                        assertSame(template, world.resolverRegistry.resolver(result).objectParentDemand)
                        assertSameDemand(expected[index], demands[index].liftPrecomputedParentConstructionDemand(world))
                    }
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `object and Query fragments retain independent parent-demand metadata`() {
        val fixture = TestWorld.fromSDL(
            """
            directive @parent on FIELD_DEFINITION
            type Query { objectConsumer: Int queryConsumer: Int root: Root }
            type Root { seed: Int child: Child }
            type Child { parent: Root @parent }
            """.trimIndent(),
            fieldResolvers = { schemas ->
                val fragment = schemas.fragmentFrom("fragment F on Query { root { child { parent { seed } } } }")
                val empty = schemas.loweredSchema.emptyFragmentOf("Query")
                mapOf(
                    schemas.loweredSchema.requireObjectField("Query", "objectConsumer") to fieldResolverOf(fragment) { _, _ -> 1 },
                    schemas.loweredSchema.requireObjectField("Query", "queryConsumer") to fieldResolverOf(empty, fragment) { _, _, _ -> 1 },
                )
            },
        )
        val objectConsumer = fixture.resolverRegistry.resolver(fixture.schema.requireObjectField("Query", "objectConsumer"))
        val queryConsumer = fixture.resolverRegistry.resolver(fixture.schema.requireObjectField("Query", "queryConsumer"))
        assertTrue(objectConsumer.objectParentDemand.usesParent)
        assertFalse(objectConsumer.queryParentDemand.mayInduceParentDemand)
        assertFalse(queryConsumer.objectParentDemand.mayInduceParentDemand)
        assertTrue(queryConsumer.queryParentDemand.usesParent)
        val lifted = queryConsumer.queryParentDemand.parentDemand()
        val root = fixture.schema.requireObjectField("Root", "seed").containingDef
        assertEquals(setOf("seed"), lifted.checked.single().subselections.merge(root).fieldNames())
        assertSameDemand(objectConsumer.objectParentDemand.parentDemand(), lifted)
    }

    @Test
    fun `rejects potential type checker cycles through parents even with source supplied values`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  root: Root @resolver(result: {seed: 1})
                  consumer: Int @resolver(of: "root { child { leaf } }", result: 1)
                }
                type Root {
                  child: Child @resolver(result: {})
                  seed: Int @resolver(of: "child { leaf }", result: 1)
                }
                type Child { parent: Root @parent leaf: Int }
                """.trimIndent(),
                typeCheckers = { schemas ->
                    val type = schemas.loweredSchema.requireObjectField("Child", "leaf").containingDef
                    mapOf(
                        type to TypeCheckerResolver.of(
                            type,
                            schemas.loweredSchema.requireQueryTypeDef(),
                            fragmentTemplates = mapOf(
                                "input" to ResolverFragmentTemplates(
                                    objectFragmentTemplate = schemas.fragmentFrom("fragment F on Child { parent { seed } }").materializeSelections,
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                ),
                            ),
                        ) { _, _ -> CheckerResult.Success },
                    )
                },
            )
        }
        assertTrue(failure.message.orEmpty().contains("demand cycle"))
    }

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

    private fun Demand<SelectionForest>.liftParentConstructionDemand(world: Assumptions): Demand<SelectionForest> {
        val expected = liftDynamicParentDemand(world)
        val compiler = ParentConstructionDemandCompiler(world)
        val checkedTemplate = compiler.compile(checked, checked = true)
        val uncheckedTemplate = compiler.compile(unchecked, checked = false)
        compiler.finish()
        val actual = ParentDemandNormalizer().combine(checkedTemplate.parentDemand(), uncheckedTemplate.parentDemand())
        assertSameDemand(expected, actual)
        if (!checked.hasParentSelections() && !unchecked.hasParentSelections()) {
            assertSameDemand(expected, liftPrecomputedParentConstructionDemand(world))
        }
        return expected
    }

    private fun SelectionForest.liftParentConstructionDemand(world: Assumptions): SelectionForest {
        val expected = liftDynamicParentDemand(world)
        val actual = Demand.checked(this).liftParentConstructionDemand(world)
        assertEquals(expected.shape(), actual.checked.shape())
        return expected
    }

    private fun SelectionForest.hasParentSelections(): Boolean = !all { !it.key.field.isParentField() && !it.subselections.hasParentSelections() }

    private fun ObjectSelectionForest.field(name: String) = byKey().values.single { selection -> selection.key.field.name == name }

    private fun ObjectSelectionForest.fieldNames(): Set<String> = keys().mapTo(linkedSetOf()) { key -> key.field.name }

    private fun parentSummaryFixture(): TestWorld =
        TestWorld.fromDSL(
            """
            extend type Query {
              grand: Grand @resolver(result: {})
              consumer: Int @resolver(of: "grand { middle { child { result } } }", result: 1)
            }
            type Grand {
              grandValue: Int @resolver(result: 1)
              middle: Parent @resolver(result: {})
            }
            type Parent {
              parent: Grand @parent
              bridge: Int @resolver(of: "parent { grandValue }", result: 1)
              child: Child @resolver(result: {})
            }
            type Child {
              parent: Parent @parent
              result: Int @resolver(of: "parent { bridge }", result: 1)
            }
            """.trimIndent(),
        )

    private fun parentSummaryInputs(fixture: TestWorld): List<SelectionForest> {
        val consumer = fixture.schema.requireObjectField("Query", "consumer")
        val static = requireNotNull(requireNotNull(fixture.resolverRegistry.precomputedConstructionDemand(consumer)).queryRootDemand).reusableDemand
        val requested = fixture.schemas.fragmentFrom("fragment F on Query { grand { middle { child { result } } } }").subselections
        return listOf(static, requested, requested.guardedBy(InclusionCondition.Never))
    }

    private fun assertSameDemand(
        expected: Demand<SelectionForest>,
        actual: Demand<SelectionForest>,
    ) {
        assertEquals(expected.checked.shape(), actual.checked.shape())
        assertEquals(expected.unchecked.shape(), actual.unchecked.shape())
        assertEquals(expected.typeCheckDemanded, actual.typeCheckDemanded)
        assertEquals(expected.typeCheckCondition, actual.typeCheckCondition)
    }

    private data class ParentSelectionShape(
        val condition: InclusionCondition,
        val children: Map<ViaductSchema.Object, Map<ObjectEngineResult.ObjectKey, ParentSelectionShape>>,
    )

    private fun SelectionForest.shape(): Map<ViaductSchema.Object, Map<ObjectEngineResult.ObjectKey, ParentSelectionShape>> {
        val types = linkedSetOf<ViaductSchema.Object>()
        forEach { types += it.possibleTypes }
        return types.associateWith { type ->
            merge(type).byKey().mapValues { (_, selection) -> ParentSelectionShape(selection.inclusionCondition, selection.subselections.shape()) }
        }
    }
}
