package viaduct.api.testing

import kotlin.reflect.KClass
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor
import viaduct.api.FieldValue
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.ResolverBase
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockFieldExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockSelectionSetFactory
import viaduct.api.mocks.PrebakedResults
import viaduct.api.mocks.mkSchema
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Internal implementation of [FieldResolverTester].
 *
 * This class is package-private and should not be used directly.
 * Use [FieldResolverTester.create] instead.
 */
internal class FieldResolverTesterImpl<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    override val config: ResolverTester.TesterConfig
) : FieldResolverTester<T, Q, A, O> {
    private val schema = ViaductSchema(mkSchema(config.schemaSDL))
    private val reflectionLoader = mockReflectionLoader(config.grtPackage, config.classLoader)
    private val internalContext = MockInternalContext(schema, GlobalIDCodecDefault, reflectionLoader)

    override val context: ExecutionContext = MockExecutionContext(internalContext)

    private val selectionSetFactory: SelectionSetFactory = MockSelectionSetFactory(schema)

    override suspend fun test(
        resolver: ResolverBase<O>,
        block: FieldResolverTester.FieldTestConfig<T, Q, A, O>.() -> Unit
    ): O {
        val testConfig = FieldResolverTester.FieldTestConfig<T, Q, A, O>().apply(block)

        // Validate required fields
        val objectValue = testConfig.objectValue
            ?: throw IllegalArgumentException(
                "objectValue must be set in test configuration. " +
                    "Example: tester.test(resolver) { objectValue = myObject }"
            )

        val arguments = testConfig.arguments
            ?: throw IllegalArgumentException(
                "arguments must be set in test configuration. " +
                    "Example: tester.test(resolver) { arguments = MyArguments(...) } " +
                    "or arguments = Arguments.NoArguments"
            )

        @Suppress("UNCHECKED_CAST")
        val queryValue = testConfig.queryValue ?: NullQuery as Q
        val selections = testConfig.selections ?: (SelectionSet.NoSelections as SelectionSet<O>)

        val ctx = createFieldContext(
            objectValue = objectValue,
            queryValue = queryValue,
            arguments = arguments,
            requestContext = testConfig.requestContext,
            selections = selections,
            contextQueryValues = testConfig.contextQueryValues
        )

        return invokeResolver(resolver, ctx)
    }

    override suspend fun testBatch(
        resolver: ResolverBase<O>,
        block: FieldResolverTester.BatchFieldTestConfig<T, Q, A, O>.() -> Unit
    ): List<FieldValue<O>> {
        val testConfig = FieldResolverTester.BatchFieldTestConfig<T, Q, A, O>().apply(block)

        require(testConfig.objectValues.isNotEmpty()) {
            "objectValues must not be empty for batch testing. " +
                "Example: tester.testBatch(resolver) { objectValues = listOf(obj1, obj2, obj3) }"
        }

        // Default queryValues to match objectValues length if not provided
        val queryValues = if (testConfig.queryValues.isEmpty()) {
            List(testConfig.objectValues.size) { NullQuery as Q }
        } else {
            require(testConfig.objectValues.size == testConfig.queryValues.size) {
                "objectValues and queryValues must have the same size: " +
                    "objectValues.size=${testConfig.objectValues.size}, " +
                    "queryValues.size=${testConfig.queryValues.size}"
            }
            testConfig.queryValues
        }

        val selections = testConfig.selections ?: (SelectionSet.NoSelections as SelectionSet<O>)

        val contexts = testConfig.objectValues.zip(queryValues) { obj, query ->
            createFieldContext(
                objectValue = obj,
                queryValue = query,
                arguments = Arguments.NoArguments as A, // Batch resolvers don't support per-item arguments
                requestContext = testConfig.requestContext,
                selections = selections,
                contextQueryValues = testConfig.contextQueryValues
            )
        }

        return invokeBatchResolver(resolver, contexts)
    }

    private fun createFieldContext(
        objectValue: T,
        queryValue: Q,
        arguments: A,
        requestContext: Any?,
        selections: SelectionSet<O>,
        contextQueryValues: List<Query>
    ): FieldExecutionContext<T, Q, A, O> {
        val queryResults = buildContextQueryMap(contextQueryValues)

        return MockFieldExecutionContext(
            objectValue = objectValue,
            queryValue = queryValue,
            arguments = arguments,
            requestContext = requestContext,
            selectionsValue = selections,
            internalContext = internalContext,
            queryResults = queryResults,
            selectionSetFactory = selectionSetFactory
        )
    }

    private suspend fun invokeResolver(
        resolver: ResolverBase<O>,
        ctx: FieldExecutionContext<T, Q, A, O>
    ): O {
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
            .callSuspend(resolver, wrappedCtx) as O
    }

    private suspend fun invokeBatchResolver(
        resolver: ResolverBase<O>,
        contexts: List<FieldExecutionContext<T, Q, A, O>>
    ): List<FieldValue<O>> {
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
        return batchResolveMethod.callSuspend(resolver, wrappedContexts) as List<FieldValue<O>>
    }

    private fun getContextClass(resolver: ResolverBase<O>): KClass<*> {
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
