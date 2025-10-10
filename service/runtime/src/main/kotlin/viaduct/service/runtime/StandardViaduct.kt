package viaduct.service.runtime

import com.google.inject.Guice
import com.google.inject.Inject
import com.google.inject.Injector
import com.google.inject.ProvisionException
import com.google.inject.util.Modules
import graphql.ExecutionInput as GJExecutionInput
import graphql.ExecutionResult
import graphql.ExecutionResultImpl
import graphql.GraphQL
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionId
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.GraphQLSchema
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import viaduct.engine.GraphQLJavaConfig
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.TenantAPIBootstrapper.Companion.flatten
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid
import viaduct.service.api.ExecutionInput
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.runtime.noderesolvers.ViaductNodeResolverAPIBootstrapper

/**
 * An immutable implementation of Viaduct interface, it configures and executes queries against the Viaduct runtime
 *
 * Registers two different types of schema:
 * 1. The full schema, which is only exposed to internal Viaduct interfaces such as derived fields and components.
 * 2. Scoped schemas, which have both introspectable and non-introspectable versions. Scoped schemas that are
 *   not already registered when requested will be lazily computed.
 */
class StandardViaduct
    @Inject
    internal constructor(
        val viaductSchemaRegistry: ViaductSchemaRegistry,
        dispatcherRegistry: DispatcherRegistry,
        private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
        fragmentLoader: FragmentLoader,
        resolverDataFetcherInstrumentation: ResolverDataFetcherInstrumentation,
        flagManager: FlagManager,
        private val standardViaductFactory: Factory,
    ) : Viaduct {
        private val engineExecutionContextFactory =
            EngineExecutionContextFactory(
                viaductSchemaRegistry.getFullSchema(),
                dispatcherRegistry,
                fragmentLoader,
                resolverDataFetcherInstrumentation,
                flagManager
            )

        /**
         * Factory for creating StandardViaduct instances with different schema configurations.
         * Uses child injectors to provide proper schema isolation - each StandardViaduct
         * gets its own child injector with schema-specific components.
         */
        class Factory
            @Inject
            constructor(
                private val injector: Injector // Parent injector
            ) {
                /**
                 * Creates a new StandardViaduct with the specified schema configuration.
                 * Each StandardViaduct gets its own child injector, providing proper schema isolation.
                 * Schema-specific components (registries, dispatchers, etc.) are created per child injector.
                 * Configuration (TenantBootstrapper, CheckerExecutorFactory creator) comes from parent injector.
                 */
                fun createForSchema(schemaConfig: SchemaRegistryConfiguration): StandardViaduct {
                    // Create schema-specific modules that will be bound only in child injector
                    val schemaModules = listOf(
                        SchemaConfigurationModule(schemaConfig),
                        ViaductInternalEngineModule(), // Schema-specific providers (registry, dispatcher, etc.)
                        ViaductExecutionStrategyModule() // Execution strategies with schema-specific dispatchers
                    )

                    // Create new child injector with schema modules
                    // This ensures @Singleton in these modules = schema-scoped
                    val childInjector = injector.createChildInjector(schemaModules)

                    // Get StandardViaduct from child injector
                    // This will create schema-specific components automatically
                    return childInjector.getInstance(StandardViaduct::class.java)
                }
            }

        class Builder {
            private var airbnbModeEnabled: Boolean = false
            private var fragmentLoader: FragmentLoader? = null
            private var instrumentation: Instrumentation? = null
            private var flagManager: FlagManager? = null
            private var checkerExecutorFactory: CheckerExecutorFactory? = null
            private var checkerExecutorFactoryCreator: ((ViaductSchema) -> CheckerExecutorFactory)? = null
            private var temporaryBypassAccessCheck: TemporaryBypassAccessCheck = TemporaryBypassAccessCheck.Default
            private var dataFetcherExceptionHandler: DataFetcherExceptionHandler? = null
            private var resolverErrorReporter: ResolverErrorReporter? = null
            private var resolverErrorBuilder: ResolverErrorBuilder? = null
            private var coroutineInterop: CoroutineInterop? = null
            private var schemaRegistryConfiguration: SchemaRegistryConfiguration = SchemaRegistryConfiguration()
            private var tenantNameResolver: TenantNameResolver = TenantNameResolver()
            private var tenantAPIBootstrapperBuilders: List<TenantAPIBootstrapperBuilder> = emptyList()
            private var chainInstrumentationWithDefaults: Boolean = false
            private var defaultQueryNodeResolversEnabled: Boolean = true
            private var meterRegistry: MeterRegistry? = null

            fun enableAirbnbBypassDoNotUse(
                fragmentLoader: FragmentLoader,
                tenantNameResolver: TenantNameResolver,
            ): Builder =
                apply {
                    this.fragmentLoader = fragmentLoader
                    this.tenantNameResolver = tenantNameResolver
                    this.airbnbModeEnabled = true
                }

            /** See [withTenantAPIBootstrapperBuilder]. */
            fun withTenantAPIBootstrapperBuilder(builder: TenantAPIBootstrapperBuilder): Builder = withTenantAPIBootstrapperBuilders(listOf(builder))

            /**
             * Adds a TenantAPIBootstrapperBuilder to be used for creating TenantAPIBootstrapper instances.
             * Multiple builders can be added, and all their TenantAPIBootstrapper instances will be used
             * together to bootstrap tenant modules.
             *
             * @param builders The builder instance that will be used to create a TenantAPIBootstrapper
             * @return This Builder instance for method chaining
             */
            fun withTenantAPIBootstrapperBuilders(builders: List<TenantAPIBootstrapperBuilder>): Builder =
                apply {
                    tenantAPIBootstrapperBuilders = builders
                }

            /**
             * A convenience function to indicate that no bootstrapper is
             * wanted.  Used for testing purposes.  We want the empty case
             * to be explicit because in almost all non-test scenarios
             * this is a programming error that should be flagged early.
             */
            fun withNoTenantAPIBootstrapper() = apply { withTenantAPIBootstrapperBuilders(emptyList()) }

            /**
             * By default, Viaduct instances implement `Query.node` and `Query.nodes`
             * resolvers automatically.  Calling this function turns off that default behavior.
             * (If your schema does not have the `Query.node/s` field(s), you do
             * _not_ have to explicitly turn off the default behavior.)
             */
            fun withoutDefaultQueryNodeResolvers(enabled: Boolean = false): Builder =
                apply {
                    this.defaultQueryNodeResolversEnabled = enabled
                }

            fun withCheckerExecutorFactory(checkerExecutorFactory: CheckerExecutorFactory): Builder =
                apply {
                    this.checkerExecutorFactory = checkerExecutorFactory
                }

            fun withCheckerExecutorFactoryCreator(factoryCreator: (ViaductSchema) -> CheckerExecutorFactory): Builder =
                apply {
                    this.checkerExecutorFactoryCreator = factoryCreator
                }

            fun withTemporaryBypassChecker(temporaryBypassAccessCheck: TemporaryBypassAccessCheck): Builder =
                apply {
                    this.temporaryBypassAccessCheck = temporaryBypassAccessCheck
                }

            fun withSchemaRegistryConfiguration(schemaRegistryConfiguration: SchemaRegistryConfiguration): Builder =
                apply {
                    this.schemaRegistryConfiguration = schemaRegistryConfiguration
                }

            fun withFlagManager(flagManager: FlagManager): Builder =
                apply {
                    this.flagManager = flagManager
                }

            fun withDataFetcherExceptionHandler(dataFetcherExceptionHandler: DataFetcherExceptionHandler): Builder =
                apply {
                    this.dataFetcherExceptionHandler = dataFetcherExceptionHandler
                }

            // fun withDataFetcherErrorReporter(dataFetcherErrorReporter: DataFetcherErrorReporter): Builder =
            //     apply {
            //         this.dataFetcherErrorReporter = dataFetcherErrorReporter
            //     }
            //
            // fun withDataFetcherErrorBuilder(dataFetcherErrorBuilder: DataFetcherErrorBuilder): Builder =
            //     apply {
            //         this.dataFetcherErrorBuilder = dataFetcherErrorBuilder
            //     }

            @Deprecated("For advance uses, Airbnb-use only", level = DeprecationLevel.WARNING)
            fun withInstrumentation(
                instrumentation: Instrumentation?,
                chainInstrumentationWithDefaults: Boolean = false
            ): Builder =
                apply {
                    this.instrumentation = instrumentation
                    this.chainInstrumentationWithDefaults = chainInstrumentationWithDefaults
                }

            fun withCoroutineInterop(coroutineInterop: CoroutineInterop) =
                apply {
                    this.coroutineInterop = coroutineInterop
                }

            @Deprecated("For advance uses, Airbnb-use only.", level = DeprecationLevel.WARNING)
            fun getSchemaRegistryConfiguration(): SchemaRegistryConfiguration = schemaRegistryConfiguration

            fun withMeterRegistry(meterRegistry: MeterRegistry) =
                apply {
                    this.meterRegistry = meterRegistry
                }

            /**
             * Builds the Guice Module within Viaduct and gets Viaduct from the injector.
             * Uses the factory pattern for proper dependency injection.
             *
             * @return a Viaduct Instance ready to execute
             */
            fun build(): StandardViaduct {
                val scopedFuture = coroutineInterop ?: DefaultCoroutineInterop
                val executionStrategyModuleConfig = ViaductExecutionStrategyModule.Config(
                    chainInstrumentationWithDefaults = chainInstrumentationWithDefaults,
                )

                // Build tenant bootstrapper from builders
                val tenantBootstrapper = buildList {
                    addAll(tenantAPIBootstrapperBuilders)
                    if (defaultQueryNodeResolversEnabled) {
                        add(ViaductNodeResolverAPIBootstrapper.Builder())
                    }
                }.map { it.create() }.flatten()

                // Create builder configuration to pass to parent injector
                val builderConfiguration = ViaductBuilderConfiguration(
                    instrumentation = instrumentation,
                    fragmentLoader = fragmentLoader,
                    meterRegistry = meterRegistry,
                    tenantNameResolver = tenantNameResolver,
                    chainInstrumentationWithDefaults = executionStrategyModuleConfig.chainInstrumentationWithDefaults,
                    checkerExecutorFactory = checkerExecutorFactory,
                    checkerExecutorFactoryCreator = checkerExecutorFactoryCreator,
                    tenantBootstrapper = tenantBootstrapper
                )

                // Create parent modules - stateless factories and global utilities
                val parentModules = listOf(
                    StatelessFactoryModule(),
                    CoreUtilitiesModule(
                        flagManager,
                        scopedFuture,
                        dataFetcherExceptionHandler,
                        resolverErrorReporter ?: ResolverErrorReporter.NoOpResolverErrorReporter,
                        resolverErrorBuilder ?: ResolverErrorBuilder.NoOpResolverErrorBuilder,
                        temporaryBypassAccessCheck,
                    ),
                    ViaductBuilderConfigurationModule(builderConfiguration)
                )

                try {
                    // Create parent injector with only stateless modules
                    val parentInjector = Guice.createInjector(
                        Modules.combine(parentModules)
                    )

                    // Get factory from parent injector
                    val factory = parentInjector.getInstance(Factory::class.java)

                    // Factory creates child injector with schema modules and returns StandardViaduct
                    return factory.createForSchema(schemaRegistryConfiguration)
                } catch (e: ProvisionException) {
                    val isCausedByDispatcherRegistryFactory = e.cause?.stackTrace?.any {
                        it.className == DispatcherRegistryFactory::class.java.name
                    } ?: false

                    if (isCausedByDispatcherRegistryFactory) {
                        throw throwDispatcherRegistryError(e)
                    }
                    throw e
                }
            }

            /**
             * If attempting to create a [StandardViaduct] results in a Guice exception,
             * call this method to potentially unwrap it.  We don't unwrap _all_ Guice
             * exceptions, but where we have high confidence that cause of the Guice
             * exception would be more informative to the Service Engineer configuring
             * Viaduct -- for example, if we detect an invalid required selection set --
             * then we will unwrap the exception to give the Service Engineer a better
             * experience in trying to diagnose the problem.
             *
             * @param exception The exception thrown by Guice
             *
             * @return GraphQLBuildError with proper details
             */
            private fun throwDispatcherRegistryError(exception: ProvisionException): GraphQLBuildError {
                return when (exception.cause) {
                    is RequiredSelectionsAreInvalid -> GraphQLBuildError(
                        "Found GraphQL validation errors: %s".format(
                            (exception.cause as RequiredSelectionsAreInvalid).errors,
                        ),
                        exception.cause
                    )

                    is IllegalArgumentException -> GraphQLBuildError(
                        "Illegal Argument found : %s".format(
                            exception.cause?.message,
                        ),
                        exception.cause
                    )

                    else -> GraphQLBuildError(
                        "Invalid DispatcherRegistryFactory configuration. " + "This is likely invalid schema or fragment configuration.",
                        exception
                    )
                }
            }
        }

        /**
         * Function to create a new StandardViaduct from an existing StandardViaduct with a different schema.
         * Uses the factory pattern for proper dependency injection.
         * Caller is expected to construct the schema registry builder then pass it in.
         */
        fun newForSchema(schemaRegistryConfig: SchemaRegistryConfiguration): StandardViaduct {
            return standardViaductFactory.createForSchema(schemaRegistryConfig)
        }

        fun mkSchemaNotFoundError(schemaId: String): CompletableFuture<ExecutionResult> {
            val error: GraphQLError = GraphqlErrorBuilder.newError()
                .message("Schema not found for schemaId=$schemaId")
                .build()
            return CompletableFuture.completedFuture(
                ExecutionResultImpl.newExecutionResult()
                    .addError(error)
                    .build()
            )
        }

        /**
         * This function asynchronously executes an operation (found in ExecutionInput),
         * returning a completable future that will contain the sorted ExecutionResult
         *
         * @param executionInput the [ExecutionInput] to execute
         * @return [CompletableFuture] of sorted [ExecutionResult]
         */
        override fun executeAsync(executionInput: ExecutionInput): CompletableFuture<ExecutionResult> {
            val engine = viaductSchemaRegistry.getEngine(executionInput.schemaId)
                ?: return mkSchemaNotFoundError(executionInput.schemaId)
            val gjExecutionInput = mkExecutionInput(executionInput)
            return coroutineInterop.enterThreadLocalCoroutineContext {
                val executionResult = engine.executeAsync(gjExecutionInput).await()
                requireNotNull(executionResult) {
                    "Unknown GQ Error: ExecutionResult for schemaId=${executionInput.schemaId} cannot be null"
                }
                sortExecutionResult(executionResult)
            }
        }

        /**
         * This is a blocking(!!) function that executes an operation (found in ExecutionInput) and returns
         * a sorted ExecutionResult
         *
         * @param executionInput the [ExecutionInput] to execute
         * @return [CompletableFuture] of sorted [ExecutionResult]
         */
        override fun execute(executionInput: ExecutionInput): ExecutionResult {
            return executeAsync(executionInput).join()
        }

        /**
         * This function is used to get the applied scopes for a given schemaId
         *
         * @param schemaId the id of the schema for which we want a [GraphQLSchema]
         *
         * @return Set of scopes that are applied to the schema
         */
        override fun getAppliedScopes(schemaId: String): Set<String>? {
            @Suppress("DEPRECATION")
            return getSchema(schemaId)?.scopes()
        }

        /**
         * Runs a query against the schema named "" (blank string) using
         * a simplified execution context.  (Intended for testing.)
         */
        fun runQuery(
            query: String,
            variables: Map<String, Any?> = emptyMap(),
        ): ExecutionResult = runQuery("", query, variables)

        /**
         * Runs a query against the schema using a simplified
         * execution context.  (Intended for testing.)
         */

        /** Runs a query. */
        fun runQuery(
            schemaId: String,
            query: String,
            variables: Map<String, Any?> = emptyMap(),
        ): ExecutionResult =
            execute(
                ExecutionInput.create(
                    schemaId = schemaId,
                    operationText = query,
                    variables = variables,
                    requestContext = Any(),
                )
            )

        /**
         * Creates ExecutionResult from Execution Result and sorts the errors based on a path
         *
         * @param executionResult the ExecutionResult
         *
         * @return the ExecutionResult with the data off the executionResult
         *
         * Internal for Testing
         */
        internal fun sortExecutionResult(executionResult: ExecutionResult): ExecutionResult {
            val sortedErrors: List<GraphQLError> =
                executionResult.errors.sortedWith(
                    compareBy({ it.path?.joinToString(separator = ".") ?: "" }, { it.message })
                )

            return ExecutionResultImpl(
                executionResult.getData(),
                sortedErrors,
                executionResult.extensions
            )
        }

        /**
         * This function is used to create the ExecutionInput that is needed to run the engine of GraphQL.
         *
         * @param executionInput The ExecutionInput object that has the data to create the input for execution
         *
         * @return GJExecutionInput created via the data inside the executionInput.
         */
        private fun mkExecutionInput(executionInput: ExecutionInput): GJExecutionInput {
            val executionInputBuilder =
                GJExecutionInput
                    .newExecutionInput()
                    .executionId(ExecutionId.generate())
                    .query(executionInput.operationText)

            if (executionInput.operationName != null) {
                executionInputBuilder.operationName(executionInput.operationName)
            }
            executionInputBuilder.variables(executionInput.variables)
            val engineContext = mkEngineExecutionContext(executionInput.schemaId, executionInput.requestContext)
            val localContext = CompositeLocalContext.withContexts(engineContext)

            @Suppress("DEPRECATION")
            return executionInputBuilder
                .context(executionInput.requestContext)
                .localContext(localContext)
                .graphQLContext(GraphQLJavaConfig.default.asMap())
                .build()
        }

        /**
         * Temporary - Will be either private/or somewhere not exposed
         *
         * This function is used to get the GraphQLSchema from the registered scopes.
         *
         * @param schemaId the id of the schema for which we want a [GraphQLSchema]
         *
         * @return GraphQLSchema instance of the registered scope
         */
        @Suppress("DEPRECATION")
        @Deprecated("Will be either private/or somewhere not exposed")
        override fun getSchema(schemaId: String): ViaductSchema? = viaductSchemaRegistry.getSchema(schemaId)

        /**
         * AirBNB only
         *
         * This function is used to get the engine from the GraphQLSchemaRegistry
         * @param schemaId the id of the schema for which we want a [GraphQL] engine
         *
         * @return GraphQL instance of the engine
         */
        fun getEngine(schemaId: String): GraphQL? = viaductSchemaRegistry.getEngine(schemaId)

        /**
         * Creates an instance of EngineExecutionContext. This should be called exactly once
         * per request and set in the graphql-java execution input's local context.
         */
        fun mkEngineExecutionContext(
            schemaId: String,
            requestContext: Any?
        ): EngineExecutionContext {
            return viaductSchemaRegistry.getSchema(schemaId)?.let {
                engineExecutionContextFactory.create(it, requestContext)
            } ?: throw IllegalArgumentException("Schema not registered for $schemaId")
        }
    }
