package viaduct.engine.runtime2.execution

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime2.bootstrap.ExecutorRegistryInputs
import viaduct.engine.runtime2.bootstrap.executorRegistryInputs
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.selectionsFrom

class ExecutorRegistryInputsTest {
    @Test
    fun `builds and executes using main schema registry and GraphQL APIs`() =
        runTest {
            val fixture = Fixture("type Query { box: Box! } type Box { children: [Child!]! } type Child { value: Int! }")
            val executor = MockFieldUnbatchedResolverExecutor(resolverId = "Query.box") { _, _, _, _, context ->
                assertSame(fixture.context.requestContext, context.requestContext)
                mapOf("children" to listOf(mapOf("value" to 7L)))
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
                    executionContext = ResolutionExecutionContext.Unsupported,
                ),
            )
            assertEquals("authoritative-id", output.get("id"))
            assertFalse(output.isPresent("name"))
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
                    executionContext = ResolutionExecutionContext.Unsupported,
                ),
            )
            assertEquals("Node executor User omitted its selector", error.cause?.message)
        }

    @Test
    fun `does not invent ids for inline Node objects`() =
        runTest {
            val fixture = Fixture("type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! name: String }")
            val viewer = MockFieldUnbatchedResolverExecutor(resolverId = "Query.user") { _, _, _, _, _ ->
                mapOf("name" to "Ada")
            }
            val node = MockNodeUnbatchedResolverExecutor(typeName = "User")
            val registry = fixture.registry(fixture.adapt(listOf(("Query" to "user") to viewer), listOf("User" to node)))
            assertThrows<viaduct.errors.UnsetFieldException> { fixture.invoke(registry, "user") }
        }

    private class Fixture(val sdl: String) {
        val engineSchema = EngineSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)))
        val schemas = ViaductAndGJSchema.fromGraphQLSchema(engineSchema.schema)
        val context = ContextMocks(myFullSchema = engineSchema, myRequestContext = Any()).engineExecutionContext

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
                variableProviders = inputs.variableProviders,
            )

        suspend fun invoke(
            registry: ResolverRegistry,
            name: String
        ): Any? {
            val field = schemas.loweredSchema.requireObjectField("Query", name)
            val root = registry.createRootQueryInput()
            return registry.resolver(field)(
                input = root,
                queryValue = root,
                arguments = Arguments.Resolved.of(field, emptyMap()),
                selections = selectionForestOf(),
                selectiveResolvers = true,
                executionContext = ResolutionExecutionContext.Unsupported,
            )
        }
    }

    private companion object {
        const val NODE_SCHEMA = "type Query { node(id: ID!): Node user: User } interface Node { id: ID! } type User implements Node { id: ID! } type Other implements Node { id: ID! }"
    }
}
