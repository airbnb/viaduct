package viaduct.api.testing

import kotlin.reflect.KClass
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor
import viaduct.api.FieldValue
import viaduct.api.context.ExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.mocks.MockSelectionSetFactory
import viaduct.api.mocks.PrebakedResults
import viaduct.api.mocks.mkSchema
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Internal implementation of [NodeResolverTester].
 *
 * This class is package-private and should not be used directly.
 * Use [NodeResolverTester.create] instead.
 */
internal class NodeResolverTesterImpl<T : NodeObject>(
    override val config: ResolverTester.TesterConfig
) : NodeResolverTester<T> {
    private val schema = ViaductSchema(mkSchema(config.schemaSDL))
    private val reflectionLoader = mockReflectionLoader(config.grtPackage, config.classLoader)
    private val internalContext = MockInternalContext(schema, GlobalIDCodecDefault, reflectionLoader)

    override val context: ExecutionContext = MockExecutionContext(internalContext)

    private val selectionSetFactory: SelectionSetFactory = MockSelectionSetFactory(schema)

    override suspend fun test(
        resolver: NodeResolverBase<T>,
        block: NodeResolverTester.NodeTestConfig<T>.() -> Unit
    ): T {
        val testConfig = NodeResolverTester.NodeTestConfig<T>().apply(block)

        // Validate required id field - lateinit will throw if not initialized
        val id = try {
            testConfig.id
        } catch (e: UninitializedPropertyAccessException) {
            throw IllegalArgumentException(
                "id must be set in test configuration. " +
                    "Example: tester.test(resolver) { id = tester.globalIDFor(MyType.Reflection, \"id-123\") }"
            )
        }

        val selections = testConfig.selections ?: (SelectionSet.NoSelections as SelectionSet<T>)

        val ctx = createNodeContext(
            id = id,
            requestContext = testConfig.requestContext,
            selections = selections,
            contextQueryValues = testConfig.contextQueryValues
        )

        return invokeResolver(resolver, ctx)
    }

    override suspend fun testBatch(
        resolver: NodeResolverBase<T>,
        block: NodeResolverTester.BatchNodeTestConfig<T>.() -> Unit
    ): List<FieldValue<T>> {
        val testConfig = NodeResolverTester.BatchNodeTestConfig<T>().apply(block)

        require(testConfig.ids.isNotEmpty()) {
            "ids must not be empty for batch testing. " +
                "Example: tester.testBatch(resolver) { ids = listOf(id1, id2, id3) }"
        }

        val selections = testConfig.selections ?: (SelectionSet.NoSelections as SelectionSet<T>)

        val contexts = testConfig.ids.map { id ->
            createNodeContext(
                id = id,
                requestContext = testConfig.requestContext,
                selections = selections,
                contextQueryValues = testConfig.contextQueryValues
            )
        }

        return invokeBatchResolver(resolver, contexts)
    }

    private fun createNodeContext(
        id: GlobalID<T>,
        requestContext: Any?,
        selections: SelectionSet<T>,
        contextQueryValues: List<Query>
    ): NodeExecutionContext<T> {
        val queryResults = buildContextQueryMap(contextQueryValues)

        return MockNodeExecutionContext(
            id = id,
            requestContext = requestContext,
            selectionsValue = selections,
            internalContext = internalContext,
            queryResults = queryResults,
            selectionSetFactory = selectionSetFactory
        )
    }

    private suspend fun invokeResolver(
        resolver: NodeResolverBase<T>,
        ctx: NodeExecutionContext<T>
    ): T {
        // Get the Context nested class from the resolver
        val contextClass = getContextClass(resolver)

        // Instantiate the wrapper context
        val wrappedCtx = contextClass.primaryConstructor?.call(ctx)
            ?: throw IllegalStateException(
                "Context class ${contextClass.qualifiedName} must have a primary constructor"
            )

        // Call resolve method
        // Note: callSuspend automatically unwraps InvocationTargetException
        @Suppress("UNCHECKED_CAST")
        return resolver::class.declaredFunctions
            .first { it.name == "resolve" }
            .callSuspend(resolver, wrappedCtx) as T
    }

    private suspend fun invokeBatchResolver(
        resolver: NodeResolverBase<T>,
        contexts: List<NodeExecutionContext<T>>
    ): List<FieldValue<T>> {
        // Get the Context nested class
        val contextClass = getContextClass(resolver)

        // Wrap all contexts
        val wrappedContexts = contexts.map { ctx ->
            contextClass.primaryConstructor?.call(ctx)
                ?: throw IllegalStateException(
                    "Context class ${contextClass.qualifiedName} must have a primary constructor"
                )
        }

        // Find and validate batchResolve method exists
        val batchResolveMethod = resolver::class.declaredFunctions
            .firstOrNull { it.name == "batchResolve" }
            ?: throw IllegalArgumentException(
                "Resolver ${resolver::class.qualifiedName} does not implement batchResolve(). " +
                    "Use test() instead for single-item resolution."
            )

        // Call batchResolve method
        // Note: callSuspend automatically unwraps InvocationTargetException
        @Suppress("UNCHECKED_CAST")
        return batchResolveMethod.callSuspend(resolver, wrappedContexts) as List<FieldValue<T>>
    }

    private fun getContextClass(resolver: NodeResolverBase<T>): KClass<*> {
        val contextClass = resolver.javaClass.classes
            .firstOrNull { it.simpleName == "Context" }
            ?.kotlin
            ?: throw IllegalArgumentException(
                "Expected resolver (${resolver::class.qualifiedName}) to contain " +
                    "a nested class called 'Context', but none was found. " +
                    "Resolvers must have a nested Context class that wraps the execution context."
            )

        require(contextClass.primaryConstructor != null) {
            "Context class in ${resolver::class.qualifiedName} must have a primary constructor " +
                "that accepts an execution context parameter."
        }

        return contextClass
    }

    private fun buildContextQueryMap(contextQueries: List<Query>): PrebakedResults<Query> {
        return when {
            contextQueries.isEmpty() -> emptyPrebakedResults()
            contextQueries.size == 1 -> singleResultPrebakedResults(contextQueries[0])
            else -> throw UnsupportedOperationException(
                "Multiple context queries not yet supported in new API. " +
                    "Please use a single Query or open a feature request."
            )
        }
    }
}
