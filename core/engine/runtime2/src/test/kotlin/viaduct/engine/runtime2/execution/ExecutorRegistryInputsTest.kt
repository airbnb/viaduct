package viaduct.engine.runtime2.execution

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherExecutionContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldDataLoader
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeDataLoader
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime2.bootstrap.ExecutorRegistryInputs
import viaduct.engine.runtime2.bootstrap.dispatcherRegistryInputs
import viaduct.engine.runtime2.bootstrap.executorRegistryInputs
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.RootFieldReferenceDataCarrier
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.additionalContextForResolver
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.rootFieldReferenceDataOrNull
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.selectionsFrom
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.runtime.jvm.OverlayEngineObjectData

class ExecutorRegistryInputsTest {
    @Test
    fun `selective node projection preserves opaque context and stored errors`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val type = fixture.schemas.loweredSchema.requireType("User") as ViaductSchema.Object
            val context = Any()
            val error = EngineErrorData.of(IllegalStateException("name failed"))
            val source = engineObjectDataOf(type, mapOf("id" to "id", "name" to error), additionalContextForResolver = context)
            val executor = MockNodeUnbatchedResolverExecutor(typeName = "User", isSelective = true) { _, _, _ -> source }
            val inputs = fixture.adapt(nodes = listOf("User" to executor))
            val demand = fixture.schemas.selectionsFrom("fragment Demand on User { name }").second

            val projected = inputs.nodeResolvers.getValue(type)("id", demand, fixture.resolutionContext) as EngineObjectData.Sync

            assertSame(context, projected.additionalContextForResolver)
            assertSame(error, projected.outputValue("name"))
            assertEquals(listOf("name"), projected.getSelections().toList())
        }

    @Test
    fun `child resolvers receive the original parent context for each list occurrence`() =
        runTest {
            val fixture = Fixture("type Query { items: [Item] } type Item { value: String }")
            val schema = fixture.schemas.loweredSchema
            val itemType = schema.requireType("Item") as ViaductSchema.Object
            val contexts = listOf(Any(), Any())
            val observed = mutableListOf<Any?>()
            val inputs = ExecutorRegistryInputs(
                fieldResolvers = mapOf(
                    schema.requireObjectField("Query", "items") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        contexts.map { engineObjectDataOf(itemType, additionalContextForResolver = it) }
                    },
                    schema.requireObjectField("Item", "value") to fieldResolverOf(
                        schema.emptyFragmentOf("Item"),
                        schema.emptyFragmentOf("Query"),
                    ) { input, _, _, context, _ ->
                        assertTrue(input.getSelections().none())
                        observed.add(context)
                        if (context === contexts[0]) "first" else "second"
                    },
                ),
                nodeResolvers = emptyMap(),
                variableProviders = emptyMap(),
            )

            val result = fixture.execute(inputs, "{ items { value } }")

            assertEquals(emptyList<Any>(), result.errors)
            assertEquals(mapOf("items" to listOf(mapOf("value" to "first"), mapOf("value" to "second"))), result.getData())
            assertEquals(2, observed.size)
            contexts.forEach { expected -> assertEquals(1, observed.count { it === expected }) }
        }

    @Test
    fun `executor and dispatcher adapters preserve canonical extended scalar outputs`() =
        runTest {
            val fixture = Fixture(
                "scalar Long scalar DateTime scalar JSON scalar BackingData type Query { long: Long dateTime: DateTime json: JSON backing: BackingData }",
            )
            val outputs = mapOf(
                "long" to Long.MAX_VALUE,
                "dateTime" to Instant.parse("2024-10-29T14:30:00.123456789Z"),
                "json" to listOf(mapOf("value" to 1), null),
                "backing" to Any(),
            )
            val executors = outputs.map { (name, value) ->
                ("Query" to name) to MockFieldUnbatchedResolverExecutor(resolverId = "Query.$name", isSelective = true) { _, _, _, _, _ -> value }
            }
            val dispatchers = DispatcherRegistry.Impl(
                fieldResolverDispatchers = executors.associate { (coordinate, executor) -> coordinate to FieldResolverDispatcherImpl(executor) },
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers = emptyMap(),
                typeCheckerDispatchers = emptyMap(),
            )
            val inputs = listOf(
                fixture.adapt(executors),
                dispatcherRegistryInputs(fixture.engineSchema, fixture.schemas, dispatchers),
            )

            inputs.forEach { adapted ->
                val registry = fixture.registry(adapted)
                outputs.forEach { (name, value) ->
                    assertSame(value, fixture.invoke(registry, name))
                }
            }
        }

    @Test
    fun `node references carry canonical identity at creation and expose only their id`() =
        runTest {
            val fixture = Fixture(NODE_SCHEMA)
            val context = QPlanEngineExecutionContext(fixture.context, fixture.schemas, fixture.resolutionContext)
            val userType = fixture.engineSchema.schema.getObjectType("User")

            val reference = context.createNodeReference("original-id", userType)

            assertSame(userType, reference.type)
            assertEquals("original-id", reference.id)
            assertFalse(reference is EngineObjectData.Sync)
            val data = assertInstanceOf(RootFieldReferenceDataCarrier::class.java, reference).referenceData
            assertSame(fixture.schemas.loweredSchema.requireObjectField("Query", "node"), data.targetField)
            val identity = requireNotNull(data.nodeReferenceIdentityOrNull())
            assertSame(fixture.schemas.loweredSchema.requireType("User"), identity.type)
            assertEquals("original-id", identity.id)
            val handle = assertInstanceOf(NodeEngineObjectData::class.java, reference)
            assertEquals("original-id", handle.fetch("id"))
            assertEquals("original-id", handle.fetchOrNull("id"))
            assertEquals(null, handle.fetchOrNull("name"))
            assertEquals(listOf("id"), handle.fetchSelections().toList())
            assertThrows<UnsetFieldException> { handle.fetch("name") }
        }

    @Test
    fun `root references snapshot their path and canonical arguments at creation`() =
        runTest {
            val fixture = Fixture(
                "type Query { factory: Factory } type Factory { get(id: ID!, limit: Int = 3, ids: [ID!]): Product } type Product { name: String }",
            )
            val context = QPlanEngineExecutionContext(fixture.context, fixture.schemas, fixture.resolutionContext)
            val type = fixture.engineSchema.schema.getObjectType("Product")
            val path = mutableListOf("factory", "get")
            val ids = mutableListOf("first")
            val args = mutableMapOf<String, Any?>("id" to "p1", "ids" to ids)

            val reference = context.createRootFieldReference(path, type, args)
            val data = assertInstanceOf(RootFieldReferenceDataCarrier::class.java, reference).referenceData
            path.clear()
            args["id"] = "changed"
            ids += "second"

            assertSame(type, reference.type)
            assertEquals(listOf("factory", "get"), reference.rootFieldPath)
            assertEquals("p1", reference.args["id"])
            assertEquals(listOf("factory", "get"), data.path.map { it.name })
            assertSame(fixture.schemas.loweredSchema.requireObjectField("Factory", "get"), data.targetField)
            assertEquals(mapOf("id" to "p1", "limit" to 3, "ids" to listOf("first")), data.arguments.fieldValues)
            assertFalse(reference is EngineObjectData.Sync)
            val handle = assertInstanceOf(EngineObjectData::class.java, reference)
            assertEquals(emptyList<String>(), handle.fetchSelections().toList())
            assertEquals(null, handle.fetchOrNull("name"))
            assertThrows<UnsetFieldException> { handle.fetch("name") }
        }

    @Test
    fun `invalid references fail during creation`() {
        val fixture = Fixture(
            "type Query { node(id: ID!): Node factory: Factory } interface Node { id: ID! } type Product implements Node { id: ID! } type Factory { get(id: ID!): Product list: [Product] }",
        )
        val context = QPlanEngineExecutionContext(fixture.context, fixture.schemas, fixture.resolutionContext)
        val product = fixture.engineSchema.schema.getObjectType("Product")
        assertThrows<IllegalArgumentException> { context.createNodeReference("id", fixture.engineSchema.schema.queryType) }
        assertThrows<IllegalArgumentException> { context.createRootFieldReference(listOf("missing"), product, emptyMap()) }
        assertThrows<IllegalArgumentException> { context.createRootFieldReference(listOf("factory", "list"), product, emptyMap()) }
        assertThrows<IllegalArgumentException> {
            context.createRootFieldReference(listOf("factory", "get"), fixture.engineSchema.schema.getObjectType("Factory"), mapOf("id" to "p1"))
        }
        assertThrows<ClassCastException> { context.createRootFieldReference(listOf("factory", "get"), product, emptyMap()) }
    }

    @Test
    fun `executor outputs retain reference handles in nested object lists`() =
        runTest {
            val fixture = Fixture(
                "type Query { node(id: ID!): Node factory: Factory shelves: [Shelf] } interface Node { id: ID! } type User implements Node { id: ID! } type Factory { get: User } type Shelf { user: User }",
            )
            val userType = fixture.engineSchema.schema.getObjectType("User")
            val shelfType = fixture.engineSchema.schema.getObjectType("Shelf")
            lateinit var nodeData: RootFieldReferenceData
            lateinit var rootData: RootFieldReferenceData
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.shelves") { _, _, _, _, context ->
                val node = context.createNodeReference("u1", userType)
                val root = context.createRootFieldReference(listOf("factory", "get"), userType, emptyMap())
                nodeData = (node as RootFieldReferenceDataCarrier).referenceData
                rootData = (root as RootFieldReferenceDataCarrier).referenceData
                listOf(
                    ResolvedEngineObjectData(shelfType, mapOf("user" to node)),
                    ResolvedEngineObjectData(shelfType, mapOf("user" to root)),
                )
            }
            val factory = MockFieldUnbatchedResolverExecutor(resolverId = "Query.factory")
            val node = MockNodeUnbatchedResolverExecutor(typeName = "User")
            val registry = fixture.registry(
                fixture.adapt(listOf(("Query" to "shelves") to executor, ("Query" to "factory") to factory), listOf("User" to node)),
            )

            val field = fixture.schemas.loweredSchema.requireObjectField("Query", "shelves")
            val query = registry.createRootQueryInput()
            val output = registry.resolver(field)(
                input = query,
                queryValue = query,
                arguments = Arguments.Resolved.of(field, emptyMap()),
                selections = fixture.schemas.selectionsFrom("fragment Demand on Shelf { user { id } }").second,
                selectiveResolvers = true,
                executionContext = fixture.resolutionContext,
            ) as List<*>

            assertSame(nodeData, (output[0] as EngineObjectData.Sync).outputValue("user").rootFieldReferenceDataOrNull())
            assertSame(rootData, (output[1] as EngineObjectData.Sync).outputValue("user").rootFieldReferenceDataOrNull())
        }

    @Test
    fun `field executor and dispatcher outputs preserve compliant object and list identities`() =
        runTest {
            val fixture = Fixture("type Query { boxes: [Box] } type Box { children: [Child] missing: String } type Child { value: Int }")
            val child = fixture.resolved("Child", mapOf("value" to 7))
            val children = listOf(child, null)
            val resolved = fixture.resolved("Box", mapOf("children" to children))
            val owned = engineObjectDataOf(fixture.schemas.loweredSchema.requireType("Box") as ViaductSchema.Object, mapOf("children" to children))
            val output = listOf(resolved, owned, null)
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.boxes", isSelective = true) { _, _, _, _, _ -> output }
            val dispatchers = DispatcherRegistry.Impl(
                fieldResolverDispatchers = mapOf(("Query" to "boxes") to FieldResolverDispatcherImpl(executor)),
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers = emptyMap(),
                typeCheckerDispatchers = emptyMap(),
            )
            val inputs = listOf(
                fixture.adapt(listOf(("Query" to "boxes") to executor)),
                dispatcherRegistryInputs(fixture.engineSchema, fixture.schemas, dispatchers),
            )

            inputs.forEach { adapted ->
                val value = fixture.invoke(
                    fixture.registry(adapted),
                    "boxes",
                    fixture.schemas.selectionsFrom("fragment Demand on Box { children { value } }").second,
                )
                assertSame(output, value)
                assertSame(children, resolved.outputValue("children"))
                assertSame(children, owned.outputValue("children"))
                assertEquals(7, child.outputValue("value"))
                assertFalse(resolved.isPresent("missing"))
            }
        }

    @Test
    fun `resolution consumes tenant overlays from executors and dispatchers`() =
        runTest {
            val fixture = Fixture("type Query { box: Box } type Box { name: String nickname: String age: Int scores: [Int] }")
            val scores = listOf(1, null)
            val base = fixture.resolved("Box", mapOf("name" to "Ada", "nickname" to "Countess", "age" to 36, "scores" to scores))
            val overlay = fixture.resolved("Box", mapOf("name" to "Grace", "nickname" to null, "age" to 42))
            val output = OverlayEngineObjectData(overlay, base)
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.box", isSelective = true) { _, _, _, _, _ -> output }
            val dispatchers = DispatcherRegistry.Impl(
                fieldResolverDispatchers = mapOf(("Query" to "box") to FieldResolverDispatcherImpl(executor)),
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers = emptyMap(),
                typeCheckerDispatchers = emptyMap(),
            )
            val inputs = listOf(
                fixture.adapt(listOf(("Query" to "box") to executor)),
                dispatcherRegistryInputs(fixture.engineSchema, fixture.schemas, dispatchers),
            )

            inputs.forEach { adapted ->
                assertSame(output, fixture.invoke(fixture.registry(adapted), "box"))
                val result = fixture.execute(adapted, "{ box { name nickname age scores } }")

                assertEquals(
                    mapOf("box" to mapOf("name" to "Grace", "nickname" to null, "age" to 42, "scores" to listOf(1, null))),
                    result.getData(),
                )
                assertTrue(result.errors.isEmpty(), result.errors.toString())
                assertSame(scores, output.outputValue("scores"))
                assertEquals(42, output.outputValue("age"))
            }
        }

    @Test
    fun `resolution rejects legacy object maps and noncompliant nested scalar representations`() =
        runTest {
            val fixture = Fixture("type Query { box: Box } type Box { value: Int }")
            val outputs = listOf(mapOf("value" to 7), fixture.resolved("Box", mapOf("value" to 7L)))

            outputs.forEach { output ->
                val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.box") { _, _, _, _, _ -> output }
                val inputs = fixture.adapt(listOf(("Query" to "box") to executor))

                val result = fixture.execute(inputs, "{ box { value } }")

                assertTrue(result.errors.isNotEmpty())
                assertEquals(mapOf("box" to null), result.getData())
            }
        }

    @Test
    fun `production field dispatchers receive the runtime2 invocation context`() =
        runTest {
            val fixture = Fixture("type Query { value: Int! }")
            val executor =
                MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, context ->
                    assertInstanceOf(QPlanEngineExecutionContext::class.java, context)
                    assertSame(fixture.context.requestContext, context.requestContext)
                    7
                }
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers = mapOf(("Query" to "value") to FieldResolverDispatcherImpl(executor)),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers = emptyMap(),
                    typeCheckerDispatchers = emptyMap(),
                )
            val inputs =
                dispatcherRegistryInputs(
                    fullSchema = fixture.engineSchema,
                    schemas = fixture.schemas,
                    dispatcherRegistry = dispatcherRegistry,
                )

            assertEquals(7, fixture.invoke(fixture.registry(inputs), "value"))
        }

    @Test
    fun `production field dispatchers use immediate data loaders for batching executors`() =
        runTest {
            val fixture = Fixture("type Query { value: Int! }")
            val batchingExecutor =
                MockFieldBatchResolverExecutor(
                    resolverId = "Query.value",
                    batchResolveFn = { selectors, _ ->
                        assertEquals(1, selectors.size)
                        mapOf(selectors.single() to Result.success(7))
                    },
                )
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers =
                        mapOf(
                            ("Query" to "value") to
                                FieldResolverDispatcherImpl(batchingExecutor),
                        ),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers = emptyMap(),
                    typeCheckerDispatchers = emptyMap(),
                )

            val inputs =
                dispatcherRegistryInputs(
                    fullSchema = fixture.engineSchema,
                    schemas = fixture.schemas,
                    dispatcherRegistry = dispatcherRegistry,
                )

            assertEquals(7, fixture.invoke(fixture.registry(inputs), "value"))
        }

    @Test
    fun `production field and type checker dispatchers receive runtime2 invocation context`() =
        runTest {
            val fixture = Fixture("type Query { value: Int } type Item { value: Int }")
            val fieldCalls = AtomicInteger()
            val typeCalls = AtomicInteger()

            fun checker(
                expectedType: CheckerExecutor.CheckerType,
                calls: AtomicInteger,
            ) = CheckerDispatcherImpl(
                object : CheckerExecutor {
                    override suspend fun execute(
                        arguments: Map<String, Any?>,
                        objectDataMap: Map<String, EngineObjectData.Sync>,
                        context: EngineExecutionContext,
                        checkerType: CheckerExecutor.CheckerType,
                    ): CheckerResult {
                        assertEquals(expectedType, checkerType)
                        assertInstanceOf(QPlanEngineExecutionContext::class.java, context)
                        assertSame(fixture.context.requestContext, context.requestContext)
                        calls.incrementAndGet()
                        return CheckerResult.Success
                    }
                },
            )
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers = emptyMap(),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers =
                        mapOf(("Query" to "value") to checker(CheckerExecutor.CheckerType.FIELD, fieldCalls)),
                    typeCheckerDispatchers =
                        mapOf("Item" to checker(CheckerExecutor.CheckerType.TYPE, typeCalls)),
                )
            val inputs =
                dispatcherRegistryInputs(
                    fullSchema = fixture.engineSchema,
                    schemas = fixture.schemas,
                    dispatcherRegistry = dispatcherRegistry,
                )
            val field = fixture.schemas.loweredSchema.requireObjectField("Query", "value")
            val type = fixture.schemas.loweredSchema.requireType("Item") as ViaductSchema.Object

            assertEquals(
                CheckerResult.Success,
                inputs.fieldCheckers.getValue(field)(
                    Arguments.Resolved.of(field, emptyMap()),
                    emptyMap(),
                    fixture.resolutionContext,
                ),
            )
            assertEquals(
                CheckerResult.Success,
                inputs.typeCheckers.getValue(type)(emptyMap(), fixture.resolutionContext),
            )
            assertEquals(1, fieldCalls.get())
            assertEquals(1, typeCalls.get())
        }

    @Test
    fun `production checker inputs preserve object and Query roots`() =
        runTest {
            val fixture =
                Fixture(
                    "type Query { item: Item policy: String } " +
                        "type Item { checked: Int owner: String }",
                )
            val objectRss = createRSS("Item", "owner", forChecker = true)
            val queryRss = createRSS("Query", "policy", forChecker = true)
            val dispatcher =
                CheckerDispatcherImpl(
                    MockCheckerExecutor(
                        requiredSelectionSets =
                            linkedMapOf(
                                "object" to objectRss,
                                "query" to queryRss,
                                "empty" to null,
                            ),
                        executeFn = { _, values ->
                            assertEquals("object", values.getValue("object").get("marker"))
                            assertEquals("query", values.getValue("query").get("marker"))
                            assertEquals("empty-object", values.getValue("empty").get("marker"))
                        },
                    ),
                )
            val inputs =
                dispatcherRegistryInputs(
                    fullSchema = fixture.engineSchema,
                    schemas = fixture.schemas,
                    dispatcherRegistry =
                        DispatcherRegistry.Impl(
                            fieldResolverDispatchers = emptyMap(),
                            nodeResolverDispatchers = emptyMap(),
                            fieldCheckerDispatchers = mapOf(("Item" to "checked") to dispatcher),
                            typeCheckerDispatchers = emptyMap(),
                        ),
                )
            val field = fixture.schemas.loweredSchema.requireObjectField("Item", "checked")
            val checker = inputs.fieldCheckers.getValue(field)
            assertEquals(1, checker.fragmentTemplates.getValue("object").objectFragmentTemplate.size)
            assertEquals(0, checker.fragmentTemplates.getValue("object").queryFragmentTemplate.size)
            assertEquals(0, checker.fragmentTemplates.getValue("query").objectFragmentTemplate.size)
            assertEquals(1, checker.fragmentTemplates.getValue("query").queryFragmentTemplate.size)

            val itemType = fixture.engineSchema.schema.getObjectType("Item")
            val queryType = fixture.engineSchema.schema.queryType

            fun value(
                type: GraphQLObjectType,
                marker: String
            ) = ResolvedEngineObjectData(type, mapOf("marker" to marker))
            checker(
                Arguments.Resolved.of(field, emptyMap()),
                mapOf(
                    "object" to CheckerInput(value(itemType, "object"), value(queryType, "wrong")),
                    "query" to CheckerInput(value(itemType, "wrong"), value(queryType, "query")),
                    "empty" to CheckerInput(value(itemType, "empty-object"), value(queryType, "wrong")),
                ),
                fixture.resolutionContext,
            )
        }

    @Test
    fun `production checker inputs lower canonical argument and field variables`() {
        val fixture =
            Fixture(
                "type Query { item: Item } " +
                    "type Item { checked(arg: Int!): Int owner: String policy(arg: Int, owner: String): Boolean }",
            )
        val dependency = createRSS("Item", "owner", forChecker = true)
        val required =
            createRSS(
                typeName = "Item",
                selectionString = "policy(arg: \$argument, owner: \$owner)",
                variableProviders =
                    listOf(
                        FromArgument("argument", listOf("arg")),
                        FromFieldVariablesResolver("owner", listOf("owner"), dependency),
                    ),
                forChecker = true,
            )
        val dispatcherRegistry =
            DispatcherRegistry.Impl(
                fieldResolverDispatchers = emptyMap(),
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers =
                    mapOf(
                        ("Item" to "checked") to
                            CheckerDispatcherImpl(
                                MockCheckerExecutor(requiredSelectionSets = mapOf("input" to required)),
                            ),
                    ),
                typeCheckerDispatchers = emptyMap(),
            )

        val inputs =
            dispatcherRegistryInputs(
                fullSchema = fixture.engineSchema,
                schemas = fixture.schemas,
                dispatcherRegistry = dispatcherRegistry,
            )
        val checked = fixture.schemas.loweredSchema.requireObjectField("Item", "checked")
        val templates = inputs.fieldCheckers.getValue(checked).fragmentTemplates.getValue("input")
        assertEquals(2, templates.objectFragmentTemplate.size)
        assertEquals(2, templates.variables.size)
        assertInstanceOf(
            VariableDefinition.FromArgument::class.java,
            templates.variables.entries.single { it.key.variableName == "argument" }.value,
        )
        val fromField =
            assertInstanceOf(
                VariableDefinition.FromField::class.java,
                templates.variables.entries.single { it.key.variableName == "owner" }.value,
            )
        assertEquals(ProviderFragment.OBJECT, fromField.providerFragment)
        assertEquals(listOf("owner"), fromField.responsePath)
    }

    @Test
    fun `production checker inputs execute supported variable sources`() =
        runTest {
            val fixture =
                Fixture(
                    "type Query { item: Item viewer: String } " +
                        "type Item { " +
                        "checked(arg: Int!): String owner: String " +
                        "policy(argument: Int, owner: String, root: String, provided: String): String " +
                        "}",
                )
            val providerCalls = AtomicInteger()
            val checkerCalls = AtomicInteger()
            val objectOwner = createRSS("Item", "owner", forChecker = true)
            val queryViewer = createRSS("Query", "viewer", forChecker = true)
            val provider =
                MockVariablesResolver("provided") { invocation, context ->
                    assertEquals(7, invocation.arguments.getValue("arg"))
                    assertSame(fixture.context.requestContext, context.requestContext)
                    providerCalls.incrementAndGet()
                    mapOf("provided" to "provider-7")
                }
            val checkerInput =
                createRSS(
                    typeName = "Item",
                    selectionString =
                        "policy(" +
                            "argument: \$argument, " +
                            "owner: \$owner, " +
                            "root: \$root, " +
                            "provided: \$provided" +
                            ")",
                    variableProviders =
                        listOf(
                            FromArgument("argument", listOf("arg")),
                            FromFieldVariablesResolver("owner", listOf("owner"), objectOwner),
                            FromFieldVariablesResolver("root", listOf("viewer"), queryViewer),
                            provider,
                        ),
                    forChecker = true,
                )
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers =
                        mapOf(
                            ("Query" to "item") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.item") { _, _, _, _, _ ->
                                        fixture.resolved("Item", mapOf("owner" to "object-owner"))
                                    },
                                ),
                            ("Query" to "viewer") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.viewer") { _, _, _, _, _ ->
                                        "query-viewer"
                                    },
                                ),
                            ("Item" to "checked") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Item.checked") { _, _, _, _, _ ->
                                        "checked"
                                    },
                                ),
                            ("Item" to "policy") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Item.policy") { arguments, _, _, _, _ ->
                                        listOf("argument", "owner", "root", "provided")
                                            .joinToString("|") { arguments.getValue(it).toString() }
                                    },
                                ),
                        ),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers =
                        mapOf(
                            ("Item" to "checked") to
                                CheckerDispatcherImpl(
                                    MockCheckerExecutor(
                                        requiredSelectionSets = mapOf("policy" to checkerInput),
                                    ) { arguments, inputs ->
                                        assertEquals(mapOf("arg" to 7), arguments)
                                        assertEquals(
                                            "7|object-owner|query-viewer|provider-7",
                                            inputs.getValue("policy").get("policy"),
                                        )
                                        checkerCalls.incrementAndGet()
                                    },
                                ),
                        ),
                    typeCheckerDispatchers = emptyMap(),
                )

            val result =
                fixture.execute(
                    dispatcherRegistryInputs(
                        fullSchema = fixture.engineSchema,
                        schemas = fixture.schemas,
                        dispatcherRegistry = dispatcherRegistry,
                    ),
                    "{ item { checked(arg: 7) } }",
                )

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("item" to mapOf("checked" to "checked")), result.getData())
            assertEquals(1, providerCalls.get())
            assertEquals(1, checkerCalls.get())
        }

    @Test
    fun `production checker input conditions control selection work but not the checker`() =
        runTest {
            val fixture = Fixture("type Query { value: Int skippedPolicy: String includedPolicy: String }")
            val conditionCalls = AtomicInteger()
            val skippedPolicyCalls = AtomicInteger()
            val includedPolicyCalls = AtomicInteger()
            val checkerCalls = AtomicInteger()
            val skippedInput =
                createRSS(
                    typeName = "Query",
                    selectionString = "skippedPolicy",
                    forChecker = true,
                    executionCondition =
                        QueryPlanExecutionCondition {
                            conditionCalls.incrementAndGet()
                            false
                        },
                )
            val includedInput =
                createRSS(
                    typeName = "Query",
                    selectionString = "includedPolicy",
                    forChecker = true,
                    executionCondition =
                        QueryPlanExecutionCondition {
                            conditionCalls.incrementAndGet()
                            true
                        },
                )
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers =
                        mapOf(
                            ("Query" to "value") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, _ -> 7 },
                                ),
                            ("Query" to "skippedPolicy") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.skippedPolicy") { _, _, _, _, _ ->
                                        skippedPolicyCalls.incrementAndGet()
                                        "must not execute"
                                    },
                                ),
                            ("Query" to "includedPolicy") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.includedPolicy") { _, _, _, _, _ ->
                                        includedPolicyCalls.incrementAndGet()
                                        "did execute"
                                    },
                                ),
                        ),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers =
                        mapOf(
                            ("Query" to "value") to
                                CheckerDispatcherImpl(
                                    MockCheckerExecutor(
                                        requiredSelectionSets =
                                            mapOf(
                                                "skipped" to skippedInput,
                                                "included" to includedInput,
                                            ),
                                    ) { _, inputs ->
                                        assertTrue(inputs.getValue("skipped").getSelections().none())
                                        assertEquals("did execute", inputs.getValue("included").get("includedPolicy"))
                                        checkerCalls.incrementAndGet()
                                    },
                                ),
                        ),
                    typeCheckerDispatchers = emptyMap(),
                )

            val result =
                fixture.execute(
                    dispatcherRegistryInputs(
                        fullSchema = fixture.engineSchema,
                        schemas = fixture.schemas,
                        dispatcherRegistry = dispatcherRegistry,
                    ),
                    "{ value }",
                )

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("value" to 7), result.getData())
            assertEquals(2, conditionCalls.get())
            assertEquals(0, skippedPolicyCalls.get())
            assertEquals(1, includedPolicyCalls.get())
            assertEquals(1, checkerCalls.get())
        }

    @Test
    fun `production checker inputs reject opaque providers with required selections`() {
        val fixture =
            Fixture(
                "type Query { item: Item } " +
                    "type Item { checked: Int owner: String policy(value: String): Boolean }",
            )
        val provider =
            MockVariablesResolver(
                "provided",
                requiredSelectionSet = createRSS("Item", "owner", forChecker = true),
            ) { _, _ -> mapOf("provided" to "value") }
        val required =
            createRSS(
                typeName = "Item",
                selectionString = "policy(value: \$provided)",
                variableProviders = listOf(provider),
                forChecker = true,
            )
        val dispatcherRegistry =
            DispatcherRegistry.Impl(
                fieldResolverDispatchers = emptyMap(),
                nodeResolverDispatchers = emptyMap(),
                fieldCheckerDispatchers =
                    mapOf(
                        ("Item" to "checked") to
                            CheckerDispatcherImpl(
                                MockCheckerExecutor(requiredSelectionSets = mapOf("input" to required)),
                            ),
                    ),
                typeCheckerDispatchers = emptyMap(),
            )

        val failure =
            assertThrows<IllegalArgumentException> {
                dispatcherRegistryInputs(
                    fullSchema = fixture.engineSchema,
                    schemas = fixture.schemas,
                    dispatcherRegistry = dispatcherRegistry,
                )
            }

        assertTrue(failure.message.orEmpty().contains("opaque variables resolver with its own required selection set"))
    }

    @Test
    fun `production field checker denials are enforced by runtime2 execution`() =
        runTest {
            val fixture = Fixture("type Query { value: Int }")
            val checkerCalls = AtomicInteger()
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers =
                        mapOf(
                            ("Query" to "value") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, _ -> 7 },
                                ),
                        ),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers =
                        mapOf(
                            ("Query" to "value") to
                                CheckerDispatcherImpl(
                                    MockCheckerExecutor { _, _ ->
                                        checkerCalls.incrementAndGet()
                                        throw IllegalAccessException("denied")
                                    },
                                ),
                        ),
                    typeCheckerDispatchers = emptyMap(),
                )
            val result =
                fixture.execute(
                    dispatcherRegistryInputs(
                        fullSchema = fixture.engineSchema,
                        schemas = fixture.schemas,
                        dispatcherRegistry = dispatcherRegistry,
                    ),
                    "{ value }",
                )

            assertEquals(1, checkerCalls.get(), result.toSpecification().toString())
            assertEquals(mapOf("value" to null), result.getData())
            assertEquals(1, result.errors.size)
        }

    @Test
    fun `production type checker error results are enforced by runtime2 execution`() =
        runTest {
            val fixture = Fixture("type Query { item: Item } type Item { value: Int }")
            val checkerCalls = AtomicInteger()
            val dispatcherRegistry =
                DispatcherRegistry.Impl(
                    fieldResolverDispatchers =
                        mapOf(
                            ("Query" to "item") to
                                FieldResolverDispatcherImpl(
                                    MockFieldUnbatchedResolverExecutor(resolverId = "Query.item") { _, _, _, _, _ ->
                                        fixture.resolved("Item", mapOf("value" to 7))
                                    },
                                ),
                        ),
                    nodeResolverDispatchers = emptyMap(),
                    fieldCheckerDispatchers = emptyMap(),
                    typeCheckerDispatchers =
                        mapOf(
                            "Item" to
                                CheckerDispatcherImpl(
                                    object : CheckerExecutor {
                                        override suspend fun execute(
                                            arguments: Map<String, Any?>,
                                            objectDataMap: Map<String, EngineObjectData.Sync>,
                                            context: EngineExecutionContext,
                                            checkerType: CheckerExecutor.CheckerType,
                                        ): CheckerResult {
                                            assertTrue(arguments.isEmpty())
                                            assertTrue(objectDataMap.isEmpty())
                                            assertEquals(CheckerExecutor.CheckerType.TYPE, checkerType)
                                            assertInstanceOf(QPlanEngineExecutionContext::class.java, context)
                                            checkerCalls.incrementAndGet()
                                            return MockCheckerErrorResult(SecurityException("type denied"))
                                        }
                                    },
                                ),
                        ),
                )
            val result =
                fixture.execute(
                    dispatcherRegistryInputs(
                        fullSchema = fixture.engineSchema,
                        schemas = fixture.schemas,
                        dispatcherRegistry = dispatcherRegistry,
                    ),
                    "{ item { value } }",
                )

            assertEquals(1, checkerCalls.get(), result.toSpecification().toString())
            assertEquals(mapOf("item" to null), result.getData())
            assertEquals(1, result.errors.size)
            assertTrue(result.errors.single().message.contains("type denied"))
        }

    @Test
    fun `builds and executes using main schema registry and GraphQL APIs`() =
        runTest {
            val fixture = Fixture("type Query { box: Box! } type Box { children: [Child!]! } type Child { value: Int! }")
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.box") { _, _, _, _, context ->
                assertSame(fixture.context.requestContext, context.requestContext)
                fixture.resolved("Box", mapOf("children" to listOf(fixture.resolved("Child", mapOf("value" to 7)))))
            }
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "box") to executor)))
            val graphQLSchema = SchemaGenerator().makeExecutableSchema(
                SchemaParser().parse(fixture.sdl),
                RuntimeWiring.newRuntimeWiring().wiringFactory(QPlanWiringFactory(fixture.schemas.loweredSchema)).build(),
            )
            val graphQL = GraphQL.newGraphQL(graphQLSchema)
                .queryExecutionStrategy(QPlanExecutionStrategy(Assumptions.of(fixture.schemas.loweredSchema, registry), fixture.schemas.graphQLSchema, coroutineContext))
                .instrumentation(QPlanInstrumentation())
                .build()

            val result = graphQL.executeAsync(ExecutionInput.newExecutionInput().query("{ renamed: box { children { value } } }").build()).await()

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("renamed" to mapOf("children" to listOf(mapOf("value" to 7)))), result.getData())
        }

    @Test
    fun `does not fill missing Query registrations or unavailable node types`() {
        val fixture = Fixture(NODE_SCHEMA)
        val user = MockNodeUnbatchedResolverExecutor(typeName = "User")
        val inputs = fixture.adapt(nodes = listOf("User" to user))

        assertEquals(setOf("User"), inputs.nodeResolvers.keys.map { it.name }.toSet())
        assertEquals(setOf("node"), inputs.fieldResolvers.keys.map { it.name }.toSet())
        val failure = assertThrows<IllegalArgumentException> { fixture.registry(inputs) }
        assertTrue(failure.message.orEmpty().contains("Query fields without field resolvers"))
        assertTrue(fixture.adapt(includeBuiltIns = false).fieldResolvers.isEmpty())
    }

    @Test
    fun `rejects duplicates and batching before compiling registrations`() {
        val fixture = Fixture("type Query { value: Int }")
        val field = ("Query" to "value") to MockFieldUnbatchedResolverExecutor(resolverId = "Query.value")
        val node = "User" to MockNodeUnbatchedResolverExecutor(typeName = "User")
        assertTrue(assertThrows<IllegalArgumentException> { fixture.adapt(listOf(field, field)) }.message.orEmpty().contains("unique field"))
        assertTrue(assertThrows<IllegalArgumentException> { fixture.adapt(nodes = listOf(node, node)) }.message.orEmpty().contains("unique node"))
        assertThrows<NotImplementedError> {
            fixture.adapt(listOf(("Query" to "value") to MockFieldBatchResolverExecutor(resolverId = "Query.value")))
        }
        assertThrows<NotImplementedError> { fixture.adapt(nodes = listOf("User" to MockNodeBatchResolverExecutor(typeName = "User"))) }
    }

    @Test
    fun `preserves executor failures and rejects omitted field selectors`() =
        runTest {
            val fixture = Fixture("type Query { value: Int }")
            val cause = IllegalStateException("tenant failure")
            val failing = MockFieldUnbatchedResolverExecutor(resolverId = "Query.value") { _, _, _, _, _ -> throw cause }
            val omitted = object : FieldResolverExecutor by failing {
                override suspend fun batchResolve(
                    selectors: List<FieldResolverExecutor.Selector>,
                    context: EngineExecutionContext,
                ): Map<FieldResolverExecutor.Selector, Result<Any?>> = emptyMap()
            }
            val failure = assertInstanceOf(EngineErrorData::class.java, fixture.invoke(fixture.registry(fixture.adapt(listOf(("Query" to "value") to failing))), "value"))
            assertSame(cause, failure.cause)
            val missing = assertInstanceOf(EngineErrorData::class.java, fixture.invoke(fixture.registry(fixture.adapt(listOf(("Query" to "value") to omitted))), "value"))
            assertEquals("Field executor Query.value omitted its selector", missing.cause?.message)
        }

    @Test
    fun `node adaptation preserves absence and reference identity without synthetic values`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val userType = fixture.engineSchema.schema.getObjectType("User")
            val viewer = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, context ->
                context.createNodeReference("authoritative-id", userType)
            }
            val node = MockNodeUnbatchedResolverExecutor(typeName = "User", isSelective = true) { id, _, _ ->
                assertEquals("authoritative-id", id)
                ResolvedEngineObjectData(userType, emptyMap())
            }
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "user") to viewer), listOf("User" to node)))
            val reference = assertInstanceOf(RootFieldReferenceData::class.java, fixture.invoke(registry, "user"))
            val root = registry.createRootQueryInput()
            val output = assertInstanceOf(
                EngineObjectData.Sync::class.java,
                registry.resolver(reference.targetField)(
                    input = root,
                    queryValue = root,
                    arguments = reference.arguments,
                    selections = fixture.schemas.selectionsFrom("fragment Demand on User { id name }").second,
                    selectiveResolvers = true,
                    executionContext = fixture.resolutionContext,
                ),
            )
            assertEquals("authoritative-id", output.get("id"))
            assertFalse(output.isPresent("name"))
        }

    @Test
    fun `node projection preserves stored error markers while restoring the reference id`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val type = fixture.schemas.loweredSchema.requireType("User") as ViaductSchema.Object
            val error = EngineErrorData.of(IllegalStateException("name failed"))
            val source = engineObjectDataOf(type, mapOf("id" to "payload-id", "name" to error))
            val viewer = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, context ->
                context.createNodeReference("reference-id", source.type)
            }
            val node = MockNodeUnbatchedResolverExecutor(typeName = "User", isSelective = true) { _, _, _ -> source }
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "user") to viewer), listOf("User" to node)))
            val reference = assertInstanceOf(RootFieldReferenceData::class.java, fixture.invoke(registry, "user"))
            val root = registry.createRootQueryInput()

            val output = assertInstanceOf(
                EngineObjectData.Sync::class.java,
                registry.resolver(reference.targetField)(
                    input = root,
                    queryValue = root,
                    arguments = reference.arguments,
                    selections = fixture.schemas.selectionsFrom("fragment Demand on User { id name }").second,
                    selectiveResolvers = true,
                    executionContext = fixture.resolutionContext,
                ),
            )

            assertEquals("reference-id", output.get("id"))
            assertSame(error, output.outputValue("name"))
        }

    @Test
    fun `selective node projection accepts delegating Sync objects`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val user = fixture.resolved("User", mapOf("name" to "Ada"))
            val delegated = object : EngineObjectData.Sync by user {}
            val viewer = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, context ->
                context.createNodeReference("id", user.type)
            }
            val node = MockNodeUnbatchedResolverExecutor(typeName = "User", isSelective = true) { _, _, _ -> delegated }
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "user") to viewer), listOf("User" to node)))
            val reference = assertInstanceOf(RootFieldReferenceData::class.java, fixture.invoke(registry, "user"))
            val root = registry.createRootQueryInput()

            val output = assertInstanceOf(
                EngineObjectData.Sync::class.java,
                registry.resolver(reference.targetField)(
                    input = root,
                    queryValue = root,
                    arguments = reference.arguments,
                    selections = fixture.schemas.selectionsFrom("fragment Demand on User { name }").second,
                    selectiveResolvers = true,
                    executionContext = fixture.resolutionContext,
                ),
            )
            assertEquals("Ada", output.outputValue("name"))
        }

    @Test
    fun `missing node selectors remain executor errors`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val viewer = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, context ->
                context.createNodeReference("id", fixture.engineSchema.schema.getObjectType("User"))
            }
            val node = object : NodeResolverExecutor by MockNodeUnbatchedResolverExecutor(typeName = "User", isSelective = true) {
                override suspend fun resolve(
                    selectors: List<NodeResolverExecutor.Selector>,
                    context: EngineExecutionContext,
                ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> = emptyMap()
            }
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "user") to viewer), listOf("User" to node)))
            val reference = assertInstanceOf(RootFieldReferenceData::class.java, fixture.invoke(registry, "user"))
            val root = registry.createRootQueryInput()
            val error = assertInstanceOf(
                EngineErrorData::class.java,
                registry.resolver(reference.targetField)(
                    input = root,
                    queryValue = root,
                    arguments = reference.arguments,
                    selections = fixture.schemas.selectionsFrom("fragment Demand on User { name }").second,
                    selectiveResolvers = true,
                    executionContext = fixture.resolutionContext,
                ),
            )
            assertEquals("Node executor User omitted its selector", error.cause?.message)
        }

    @Test
    fun `does not invent ids for inline Node objects`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val viewer = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, _ ->
                fixture.resolved("User", mapOf("name" to "Ada"))
            }
            val node = MockNodeUnbatchedResolverExecutor(typeName = "User")
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "user") to viewer), listOf("User" to node)))
            assertFalse((fixture.invoke(registry, "user") as EngineObjectData.Sync).isPresent("id"))
        }

    private class Fixture(val sdl: String) {
        val engineSchema = EngineSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)))
        val schemas = ViaductAndGJSchema.fromGraphQLSchema(engineSchema.schema)
        val context = ContextMocks(myFullSchema = engineSchema, myRequestContext = Any()).engineExecutionContextImpl
        val dispatcherContext: DispatcherExecutionContext =
            object : DispatcherExecutionContext, EngineExecutionContext by context {
                private val fieldLoaders = ConcurrentHashMap<String, FieldDataLoader>()
                private val nodeLoaders = ConcurrentHashMap<String, NodeDataLoader>()

                override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader =
                    fieldLoaders.computeIfAbsent(resolver.resolverId) { FieldDataLoader(resolver, dispatchStrategy = Runtime2DispatchStrategy) }

                override fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader =
                    nodeLoaders.computeIfAbsent(resolver.typeName) { NodeDataLoader(resolver, dispatchStrategy = Runtime2DispatchStrategy) }
            }
        val resolutionContext =
            object : ResolutionExecutionContext by ResolutionExecutionContext.Unsupported {
                override val engineExecutionContext: EngineExecutionContext = dispatcherContext
            }

        fun resolved(
            typeName: String,
            fields: Map<String, Any?>
        ): ResolvedEngineObjectData = ResolvedEngineObjectData(engineSchema.schema.getObjectType(typeName), fields)

        fun adapt(
            fields: List<Pair<Pair<String, String>, FieldResolverExecutor>> = emptyList(),
            nodes: List<Pair<String, NodeResolverExecutor>> = emptyList(),
            includeBuiltIns: Boolean = true,
        ): ExecutorRegistryInputs =
            executorRegistryInputs(
                fullSchema = engineSchema,
                schemas = schemas,
                fieldExecutors = fields,
                nodeExecutors = nodes,
                context = context,
                includeDefaultQueryNodeResolvers = includeBuiltIns,
            )

        fun registry(inputs: ExecutorRegistryInputs): ResolverRegistry =
            resolverRegistryOf(
                schema = schemas,
                fieldResolvers = inputs.fieldResolvers,
                nodeResolvers = inputs.nodeResolvers,
                fieldCheckers = inputs.fieldCheckers,
                typeCheckers = inputs.typeCheckers,
                variableProviders = inputs.variableProviders,
            )

        suspend fun execute(
            inputs: ExecutorRegistryInputs,
            query: String,
        ): ExecutionResult {
            val graphQLSchema =
                SchemaGenerator().makeExecutableSchema(
                    SchemaParser().parse(sdl),
                    RuntimeWiring.newRuntimeWiring()
                        .wiringFactory(QPlanWiringFactory(schemas.loweredSchema))
                        .build(),
                )
            return GraphQL.newGraphQL(graphQLSchema)
                .queryExecutionStrategy(
                    QPlanExecutionStrategy(
                        world = Assumptions.of(schemas.loweredSchema, registry(inputs)),
                        sourceSchema = schemas.graphQLSchema,
                        resolverCoroutineContext = Dispatchers.Unconfined,
                        engineExecutionContextFactory = { dispatcherContext },
                    ),
                ).instrumentation(QPlanInstrumentation())
                .build()
                .executeAsync(ExecutionInput.newExecutionInput().query(query).build())
                .await()
        }

        suspend fun invoke(
            registry: ResolverRegistry,
            name: String,
            selections: SelectionForest = selectionForestOf(),
        ): Any? {
            val field = schemas.loweredSchema.requireObjectField("Query", name)
            val root = registry.createRootQueryInput()
            return registry.resolver(field)(
                input = root,
                queryValue = root,
                arguments = Arguments.Resolved.of(field, emptyMap()),
                selections = selections,
                selectiveResolvers = true,
                executionContext = resolutionContext,
            )
        }
    }

    private companion object {
        const val NODE_SCHEMA = "type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! } type Other implements Node { id: ID! }"
    }
}
