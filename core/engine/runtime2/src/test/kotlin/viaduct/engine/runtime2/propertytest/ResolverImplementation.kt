package viaduct.engine.runtime2.propertytest

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.contract.ResolverResolutionObservation
import viaduct.engine.runtime2.contract.checkResolverProperties
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.resolution.ResolutionDispatcherFactory
import viaduct.engine.runtime2.resolution.configuredResolutionThreadCount
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.resolve as resolveProduction
import viaduct.engine.runtime2.resolvers.resolver06.resolve as resolve06
import viaduct.engine.runtime2.resolvers.resolver07.resolve as resolve07
import viaduct.engine.runtime2.resolvers.resolver08.resolve as resolve08
import viaduct.engine.runtime2.resolvers.resolver21.resolve as resolve21
import viaduct.engine.runtime2.resolvers.resolver22.resolve as resolve22
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23

enum class ResolverImplementation(
    val id: String,
    val selectiveResolvers: Boolean,
) {
    RESOLVER06("resolver06", selectiveResolvers = false),
    RESOLVER07("resolver07", selectiveResolvers = false),
    RESOLVER08("resolver08", selectiveResolvers = true),
    RESOLVER21("resolver21", selectiveResolvers = false),
    RESOLVER22("resolver22", selectiveResolvers = false),
    RESOLVER23("resolver23", selectiveResolvers = true),
    RESOLVER23_PRECOMPUTED("resolver23-precomputed", selectiveResolvers = true),
    RESOLUTION("resolution", selectiveResolvers = true),
}

internal fun ResolverPropertyTestDescriptor.execute() {
    ResolverPropertySubject(implementation).use { subject ->
        subject.checkResolverProperties(suite, exactApplications = exactApplications, fromFieldBindings = fromFieldBindings)
    }
}

private class ResolverPropertySubject(
    private val implementation: ResolverImplementation,
) : ResolverContract, AutoCloseable {
    private val dispatcher: ExecutorCoroutineDispatcher? =
        if (implementation == ResolverImplementation.RESOLUTION) ResolutionDispatcherFactory.create(configuredResolutionThreadCount()) else null

    override val selectiveResolvers: Boolean
        get() = implementation.selectiveResolvers

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        when (implementation) {
            ResolverImplementation.RESOLVER06 -> operation.resolve06(selections)
            ResolverImplementation.RESOLVER07 -> operation.resolve07(selections)
            ResolverImplementation.RESOLVER08 -> operation.resolve08(selections)
            ResolverImplementation.RESOLVER21 -> operation.resolve21(selections)
            ResolverImplementation.RESOLVER22 -> operation.resolve22(selections)
            ResolverImplementation.RESOLVER23, ResolverImplementation.RESOLVER23_PRECOMPUTED -> operation.resolve23(selections)
            ResolverImplementation.RESOLUTION -> operation.resolveProduction(selections, coroutineContext = checkNotNull(dispatcher))
        }

    override fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        resolutionObserver: ResolutionObserver,
        record: Boolean,
    ): ResolverResolutionObservation =
        super.observeResolution(
            world =
                if (implementation == ResolverImplementation.RESOLVER23_PRECOMPUTED) {
                    Assumptions.of(world.schema, world.resolverRegistry, usePrecomputedConstructionDemand = true)
                } else {
                    world
                },
            root = root,
            selections = selections,
            resolutionObserver = resolutionObserver,
            record = record,
        )

    override fun close() {
        dispatcher?.close()
    }
}
