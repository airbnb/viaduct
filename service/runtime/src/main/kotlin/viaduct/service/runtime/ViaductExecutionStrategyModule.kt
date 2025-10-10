package viaduct.service.runtime

import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.name.Named
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionStrategy
import graphql.execution.instrumentation.Instrumentation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.AccessCheckRunner
import viaduct.engine.runtime.execution.ExecutionParameters
import viaduct.engine.runtime.execution.ViaductExecutionStrategy
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation
import viaduct.engine.runtime.instrumentation.TaggedMetricInstrumentation
import viaduct.service.api.spi.FlagManager
import viaduct.utils.slf4j.logger

/*
 * This module is responsible for creating the ViaductExecutionStrategy and all of its dependencies.
 * Configuration is injected from the parent injector, execution strategies are schema-scoped.
 * This module runs in the child injector to create schema-specific execution strategies.
 */
class ViaductExecutionStrategyModule : AbstractModule() {
    companion object {
        private val log by logger()
    }

    data class Config(
        val chainInstrumentationWithDefaults: Boolean = false
    )

    override fun configure() {
    }

    @Provides
    @Singleton
    @ExperimentalCoroutinesApi
    fun provideFragmentLoader(
        config: ViaductBuilderConfiguration,
        viaductFragmentLoader: ViaductFragmentLoader
    ): FragmentLoader {
        return config.fragmentLoader ?: viaductFragmentLoader
    }

    @Provides
    @Singleton
    @Named("QueryExecutionStrategy")
    fun providesQueryExecutionStrategy(
        viaductExecutionStrategyFactory: ViaductExecutionStrategy.Factory,
        coroutineInterop: CoroutineInterop,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler
    ): ExecutionStrategy {
        return WrappedCoroutineExecutionStrategy(
            viaductExecutionStrategyFactory.create(isSerial = false),
            coroutineInterop,
            dataFetcherExceptionHandler
        )
    }

    @Provides
    @Singleton
    @Named("MutationExecutionStrategy")
    fun providesMutationExecutionStrategy(
        viaductExecutionStrategyFactory: ViaductExecutionStrategy.Factory,
        coroutineInterop: CoroutineInterop,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    ): ExecutionStrategy {
        return WrappedCoroutineExecutionStrategy(
            viaductExecutionStrategyFactory.create(isSerial = true),
            coroutineInterop,
            dataFetcherExceptionHandler
        )
    }

    @Provides
    @Singleton
    @Named("SubscriptionExecutionStrategy")
    fun providesSubscriptionExecutionStrategy(
        viaductExecutionStrategyFactory: ViaductExecutionStrategy.Factory,
        coroutineInterop: CoroutineInterop,
        dataFetcherExceptionHandler: DataFetcherExceptionHandler
    ): ExecutionStrategy {
        return WrappedCoroutineExecutionStrategy(
            viaductExecutionStrategyFactory.create(isSerial = true),
            coroutineInterop,
            dataFetcherExceptionHandler
        )
    }

    @Provides
    @Singleton
    fun providesExecutionStrategyFactory(
        dataFetcherExceptionHandler: DataFetcherExceptionHandler, // From parent
        coroutineInterop: CoroutineInterop, // From parent
        flagManager: FlagManager, // From parent
        temporaryBypassAccessCheck: TemporaryBypassAccessCheck, // From parent
        requiredSelectionSetRegistry: RequiredSelectionSetRegistry, // From child (DispatcherRegistry)
        fieldCheckerDispatcherRegistry: FieldCheckerDispatcherRegistry, // From child (DispatcherRegistry)
        typeCheckerDispatcherRegistry: TypeCheckerDispatcherRegistry, // From child (DispatcherRegistry)
    ): ViaductExecutionStrategy.Factory {
        return ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler,
            ExecutionParameters.Factory(
                requiredSelectionSetRegistry,
                fieldCheckerDispatcherRegistry,
                typeCheckerDispatcherRegistry,
                flagManager
            ),
            AccessCheckRunner(coroutineInterop),
            coroutineInterop,
            temporaryBypassAccessCheck
        )
    }

    @Provides
    @Singleton
    fun providesScopeInstrumentation(): ScopeInstrumentation {
        return ScopeInstrumentation()
    }

    @Provides
    @Singleton
    fun providesResolverInstrumentation(
        coroutineInterop: CoroutineInterop, // From parent
        dispatcherRegistry: DispatcherRegistry, // From child
    ): ResolverDataFetcherInstrumentation {
        return ResolverDataFetcherInstrumentation(
            dispatcherRegistry,
            dispatcherRegistry,
            coroutineInterop
        )
    }

    @Provides
    @Singleton
    fun providesInstrumentation(
        resolverDataFetcherInstrumentation: ResolverDataFetcherInstrumentation, // From child
        scopeInstrumentation: ScopeInstrumentation, // From child
        config: ViaductBuilderConfiguration // From parent
    ): Instrumentation {
        val taggedMetricInstrumentation = config.meterRegistry?.let {
            TaggedMetricInstrumentation(meterRegistry = it)
        }
        val defaultInstrumentations = listOfNotNull(
            scopeInstrumentation.asStandardInstrumentation,
            resolverDataFetcherInstrumentation,
            taggedMetricInstrumentation?.asStandardInstrumentation
        )
        return if (config.chainInstrumentationWithDefaults) {
            val gjInstrumentation = config.instrumentation?.let {
                if (it !is ViaductModernGJInstrumentation) {
                    ViaductModernGJInstrumentation.fromStandardInstrumentation(it)
                } else {
                    it
                }
            }
            ChainedModernGJInstrumentation(defaultInstrumentations + listOfNotNull(gjInstrumentation))
        } else {
            config.instrumentation ?: ChainedModernGJInstrumentation(defaultInstrumentations)
        }
    }
}
