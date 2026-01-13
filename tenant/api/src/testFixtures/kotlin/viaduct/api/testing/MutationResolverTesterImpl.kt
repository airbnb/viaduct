package viaduct.api.testing

import kotlin.reflect.KClass
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.primaryConstructor
import viaduct.api.context.ExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.ResolverBase
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockMutationFieldExecutionContext
import viaduct.api.mocks.MockSelectionSetFactory
import viaduct.api.mocks.PrebakedResults
import viaduct.api.mocks.mkSchema
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Internal implementation of [MutationResolverTester].
 *
 * This class is package-private and should not be used directly.
 * Use [MutationResolverTester.create] instead.
 */
internal class MutationResolverTesterImpl<Q : Query, A : Arguments, O : CompositeOutput>(
    override val config: ResolverTester.TesterConfig
) : MutationResolverTester<Q, A, O> {
    private val schema = ViaductSchema(mkSchema(config.schemaSDL))
    private val reflectionLoader = mockReflectionLoader(config.grtPackage, config.classLoader)
    private val internalContext = MockInternalContext(schema, GlobalIDCodecDefault, reflectionLoader)

    override val context: ExecutionContext = MockExecutionContext(internalContext)

    private val selectionSetFactory: SelectionSetFactory = MockSelectionSetFactory(schema)

    override suspend fun test(
        resolver: ResolverBase<O>,
        block: MutationResolverTester.MutationTestConfig<Q, A, O>.() -> Unit
    ): O {
        val testConfig = MutationResolverTester.MutationTestConfig<Q, A, O>().apply(block)

        // Validate required fields
        val arguments = testConfig.arguments
            ?: throw IllegalArgumentException(
                "arguments must be set in test configuration. " +
                    "Example: tester.test(resolver) { arguments = Mutation_X_Arguments(...) }"
            )

        val queryValue = testConfig.queryValue ?: NullQuery as Q
        val selections = testConfig.selections ?: (SelectionSet.NoSelections as SelectionSet<O>)

        val ctx = createMutationContext(
            queryValue = queryValue,
            arguments = arguments,
            requestContext = testConfig.requestContext,
            selections = selections,
            contextQueryValues = testConfig.contextQueryValues,
            contextMutationValues = testConfig.contextMutationValues
        )

        return invokeResolver(resolver, ctx)
    }

    private fun createMutationContext(
        queryValue: Q,
        arguments: A,
        requestContext: Any?,
        selections: SelectionSet<O>,
        contextQueryValues: List<Query>,
        contextMutationValues: List<Mutation>
    ): MutationFieldExecutionContext<Q, A, O> {
        val queryResults = buildContextQueryMap(contextQueryValues)
        val mutationResults = buildContextMutationMap(contextMutationValues)

        return MockMutationFieldExecutionContext(
            queryValue = queryValue,
            arguments = arguments,
            requestContext = requestContext,
            selectionsValue = selections,
            internalContext = internalContext,
            queryResults = queryResults,
            mutationResults = mutationResults,
            selectionSetFactory = selectionSetFactory
        )
    }

    private suspend fun invokeResolver(
        resolver: ResolverBase<O>,
        ctx: MutationFieldExecutionContext<Q, A, O>
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

    private fun buildContextMutationMap(contextMutations: List<Mutation>): PrebakedResults<Mutation> {
        return when {
            contextMutations.isEmpty() -> emptyPrebakedResults()
            contextMutations.size == 1 -> singleResultPrebakedResults(contextMutations[0])
            else -> throw UnsupportedOperationException(
                "Multiple context mutations not yet supported in new API. " +
                    "Please use a single Mutation or open a feature request."
            )
        }
    }
}
