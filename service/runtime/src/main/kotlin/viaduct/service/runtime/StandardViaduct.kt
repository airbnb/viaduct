@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.service.runtime

import com.google.inject.Guice
import com.google.inject.Inject
import com.google.inject.Injector
import com.google.inject.ProvisionException
import graphql.ExecutionResult
import graphql.ExecutionResultImpl
import graphql.GraphQL
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.GraphQLSchema
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import viaduct.apiannotations.InternalApi
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineImpl
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.flatten
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.TenantNameResolver
import viaduct.engine.runtime.execution.ViaductDataFetcherExceptionHandler
import viaduct.engine.runtime.tenantloading.DispatcherRegistryFactory
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
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
        val engineRegistry: EngineRegistry,
        private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
        private val standardViaductFactory: Factory
    ) : Viaduct {
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
                fun createForSchema(schemaConfig: SchemaConfiguration): StandardViaduct {
                    val schemaModule = SchemaScopedModule(schemaConfig)
                    val childInjector = injector.createChildInjector(schemaModule)
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
            @Suppress("DEPRECATION")
            private var temporaryBypassAccessCheck: TemporaryBypassAccessCheck? = null
            private var dataFetcherExceptionHandler: DataFetcherExceptionHandler? = null
            private var resolverErrorReporter: ErrorReporter? = null
            private var resolverErrorBuilder: ResolverErrorBuilder? = null
            private var coroutineInterop: CoroutineInterop? = null
            private var schemaConfiguration: SchemaConfiguration = SchemaConfiguration.DEFAULT
            private var documentProviderFactory: DocumentProviderFactory? = null
            private var tenantNameResolver: TenantNameResolver = TenantNameResolver()
            private var tenantAPIBootstrapperBuilders: List<TenantAPIBootstrapperBuilder<TenantModuleBootstrapper>> = emptyList()
            private var chainInstrumentationWithDefaults: Boolean = false
            private var defaultQueryNodeResolversEnabled: Boolean = true
            private var meterRegistry: MeterRegistry? = null
            private var resolverInstrumentation: ViaductResolverInstrumentation? = null
            private var allowSubscriptions: Boolean = false
            private var globalIDCodec: GlobalIDCodec? = null

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
            fun withTenantAPIBootstrapperBuilder(builder: TenantAPIBootstrapperBuilder<TenantModuleBootstrapper>): Builder = withTenantAPIBootstrapperBuilders(listOf(builder))

            /**
             * Adds a TenantAPIBootstrapperBuilder to be used for creating TenantAPIBootstrapper instances.
             * Multiple builders can be added, and all their TenantAPIBootstrapper instances will be used
             * together to bootstrap tenant modules.
             *
             * @param builders The builder instance that will be used to create a TenantAPIBootstrapper
             * @return This Builder instance for method chaining
             */
            fun withTenantAPIBootstrapperBuilders(builders: List<TenantAPIBootstrapperBuilder<TenantModuleBootstrapper>>): Builder =
                apply {
                    tenantAPIBootstrapperBuilders = builders
                }

            /**
             * A convenience function to indicate that no bootstrapper is
             * wanted.  Used for testing purposes.  We want the empty case
             * to be explicit because in almost all non-test scenarios
             * this is a programming error that should be flagged early.
             */
            @InternalApi
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

            @Suppress("DEPRECATION")
            fun withTemporaryBypassChecker(temporaryBypassAccessCheck: TemporaryBypassAccessCheck): Builder =
                apply {
                    this.temporaryBypassAccessCheck = temporaryBypassAccessCheck
                }

            fun withSchemaConfiguration(schemaConfiguration: SchemaConfiguration): Builder =
                apply {
                    this.schemaConfiguration = schemaConfiguration
                }

            fun withDocumentProviderFactory(documentProviderFactory: DocumentProviderFactory): Builder =
                apply {
                    this.documentProviderFactory = documentProviderFactory
                }

            fun withFlagManager(flagManager: FlagManager): Builder =
                apply {
                    this.flagManager = flagManager
                }

            fun withDataFetcherExceptionHandler(dataFetcherExceptionHandler: DataFetcherExceptionHandler): Builder =
                apply {
                    this.dataFetcherExceptionHandler = dataFetcherExceptionHandler
                }

            fun withResolverErrorReporter(resolverErrorReporter: ErrorReporter): Builder =
                apply {
                    this.resolverErrorReporter = resolverErrorReporter
                }

            fun withDataFetcherErrorBuilder(resolverErrorBuilder: ResolverErrorBuilder): Builder =
                apply {
                    this.resolverErrorBuilder = resolverErrorBuilder
                }

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
            fun getSchemaConfiguration(): SchemaConfiguration = schemaConfiguration

            fun withMeterRegistry(meterRegistry: MeterRegistry) =
                apply {
                    this.meterRegistry = meterRegistry
                }

            fun withResolverInstrumentation(resolverInstrumentation: ViaductResolverInstrumentation): Builder =
                apply {
                    this.resolverInstrumentation = resolverInstrumentation
                }

            /**
             * Configures the GlobalIDCodec for serializing and deserializing GlobalIDs.
             * All tenant-API implementations within this Viaduct instance will share this codec
             * to ensure interoperability.
             *
             * @param globalIDCodec The GlobalIDCodec instance to use
             * @return This Builder instance for method chaining
             */
            fun withGlobalIDCodec(globalIDCodec: GlobalIDCodec): Builder =
                apply {
                    this.globalIDCodec = globalIDCodec
                }

            @Deprecated("For testing only, subscriptions are not currently supported in Viaduct.", level = DeprecationLevel.WARNING)
            fun allowSubscriptions(allow: Boolean) =
                apply {
                    allowSubscriptions = allow
                }

            /**
             * Builds the Guice Module within Viaduct and gets Viaduct from the injector.
             * Uses the factory pattern for proper dependency injection.
             *
             * @return a Viaduct Instance ready to execute
             */
            fun build(): StandardViaduct {
                val finalGlobalIDCodec = globalIDCodec ?: GlobalIDCodecDefault

                // engine configuration has a lot of defaults, so we copy over any non-null values from the StandardViaduct.Builder
                val engineConfiguration = with(EngineConfiguration.default) {
                    val builder = this@Builder
                    val finalResolverErrorReporter = builder.resolverErrorReporter ?: resolverErrorReporter
                    val finalResolverErrorBuilder = builder.resolverErrorBuilder ?: resolverErrorBuilder
                    copy(
                        coroutineInterop = builder.coroutineInterop ?: coroutineInterop,
                        fragmentLoader = builder.fragmentLoader ?: fragmentLoader,
                        flagManager = builder.flagManager ?: flagManager,
                        temporaryBypassAccessCheck = builder.temporaryBypassAccessCheck ?: temporaryBypassAccessCheck,
                        resolverErrorReporter = finalResolverErrorReporter,
                        resolverErrorBuilder = finalResolverErrorBuilder,
                        dataFetcherExceptionHandler = builder.dataFetcherExceptionHandler
                            ?: ViaductDataFetcherExceptionHandler(finalResolverErrorReporter, finalResolverErrorBuilder),
                        meterRegistry = builder.meterRegistry ?: meterRegistry,
                        additionalInstrumentation = builder.instrumentation ?: additionalInstrumentation,
                        chainInstrumentationWithDefaults = builder.chainInstrumentationWithDefaults,
                        resolverInstrumentation = builder.resolverInstrumentation ?: resolverInstrumentation,
                        globalIDCodec = finalGlobalIDCodec,
                    )
                }

                // Build tenant bootstrapper from builders
                val tenantBootstrapper = buildList {
                    addAll(tenantAPIBootstrapperBuilders)
                    if (defaultQueryNodeResolversEnabled) {
                        add(ViaductNodeResolverAPIBootstrapper.Builder())
                    }
                }.map { it.create() }.flatten()

                val parentModule = StandardViaductModule(
                    tenantBootstrapper = tenantBootstrapper,
                    engineConfiguration = engineConfiguration,
                    tenantNameResolver = tenantNameResolver,
                    checkerExecutorFactory = checkerExecutorFactory,
                    checkerExecutorFactoryCreator = checkerExecutorFactoryCreator,
                    documentProviderFactory = documentProviderFactory,
                )

                try {
                    val parentInjector = Guice.createInjector(parentModule)

                    // Get factory from parent injector
                    val factory = parentInjector.getInstance(Factory::class.java)

                    // Factory creates child injector with schema modules and returns StandardViaduct
                    return factory.createForSchema(schemaConfiguration)
                        .also { viaduct ->
                            if (!airbnbModeEnabled && !allowSubscriptions && hasSubscriptions(viaduct.engineRegistry.getSchema(SchemaId.Full))) {
                                throw GraphQLBuildError("Viaduct does not currently support subscriptions.")
                            }
                        }
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
             * Checks if the given schema contains Subscription operation type.
             *
             * @param schema the schema to check
             * @return true if schema has subscriptions defined, false otherwise
             */
            private fun hasSubscriptions(schema: ViaductSchema): Boolean {
                return schema.schema.subscriptionType != null
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

        private fun mkSchemaNotFoundError(schemaId: SchemaId): CompletableFuture<ExecutionResult> {
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
         * @param schemaId the id of the schema for which we want to execute the operation. Defaults to the full schema.
         * @return [CompletableFuture] of sorted [ExecutionResult]
         */
        override suspend fun executeAsync(
            executionInput: ExecutionInput,
            schemaId: SchemaId
        ): CompletableFuture<ExecutionResult> {
            val engine = try {
                engineRegistry.getEngine(schemaId)
            } catch (_: EngineRegistry.SchemaNotFoundException) {
                return mkSchemaNotFoundError(schemaId)
            }
            return coroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                val executionResult = engine.execute(executionInput.toEngineExecutionInput())
                sortExecutionResult(executionResult)
            }
        }

        /**
         * This is a blocking(!!) function that executes an operation (found in ExecutionInput) and returns
         * a sorted ExecutionResult
         *
         * @param executionInput the [ExecutionInput] to execute
         * @param schemaId the id of the schema for which we want to execute the operation. Defaults to the full schema.
         * @return [CompletableFuture] of sorted [ExecutionResult]
         */
        override fun execute(
            executionInput: ExecutionInput,
            schemaId: SchemaId
        ): ExecutionResult =
            runBlocking {
                executeAsync(executionInput, schemaId).join()
            }

        /**
         * This function is used to get the applied scopes for a given schemaId
         *
         * @param schemaId the id of the schema for which we want a [GraphQLSchema]
         *
         * @return Set of scopes that are applied to the schema
         */
        override fun getAppliedScopes(schemaId: SchemaId): Set<String> {
            return getSchema(schemaId).scopes()
        }

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
         * This function is used to get the GraphQLSchema from the registered scopes.
         *
         * @param schemaId the id of the schema for which we want a [GraphQLSchema]
         *
         * @return GraphQLSchema instance of the registered scope
         */
        fun getSchema(schemaId: SchemaId): ViaductSchema = engineRegistry.getSchema(schemaId)

        /**
         * Airbnb only
         *
         * This function is used to get the engine from the GraphQLSchemaRegistry
         * @param schemaId the id of the schema for which we want a [GraphQL] engine
         *
         * @return GraphQL instance of the engine
         */
        @Deprecated(
            message = "Airbnb use only. For temporary use during migration to Engine API from graphql-java GraphQL.",
            level = DeprecationLevel.WARNING
        )
        @Suppress("DEPRECATION")
        fun getEngine(schemaId: SchemaId): GraphQL =
            (
                engineRegistry.getEngine(schemaId) as? viaduct.engine.EngineGraphQLJavaCompat
                    ?: throw IllegalStateException("Engine is not GraphQL compatible")
            ).getGraphQL()

        /**
         * Creates an instance of EngineExecutionContext. This should be called exactly once
         * per request and set in the graphql-java execution input's local context.
         */
        @Deprecated(
            message = "Airbnb use only. Internal API for direct engine access.",
            level = DeprecationLevel.WARNING
        )
        fun mkEngineExecutionContext(
            schemaId: SchemaId,
            requestContext: Any?
        ): EngineExecutionContext {
            val engine = engineRegistry.getEngine(schemaId) as? EngineImpl ?: throw IllegalStateException("Engine is not EngineImpl")
            return engine.mkEngineExecutionContext(requestContext)
        }
    }
