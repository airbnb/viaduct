package viaduct.engine.runtime.mocks

import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcherImpl

fun mkDispatcherRegistry(
    fieldResolverExecutors: Map<Coordinate, FieldResolverExecutor> = emptyMap(),
    nodeResolverExecutors: Map<String, NodeResolverExecutor> = emptyMap(),
    fieldCheckerExecutors: Map<Coordinate, CheckerExecutor> = emptyMap(),
    typeCheckerExecutors: Map<String, CheckerExecutor> = emptyMap(),
): DispatcherRegistry {
    return DispatcherRegistry.Impl(
        fieldResolverDispatchers = fieldResolverExecutors.map { (k, v) -> k to FieldResolverDispatcherImpl(v) }.toMap(),
        nodeResolverDispatchers = nodeResolverExecutors.map { (k, v) -> k to NodeResolverDispatcherImpl(v) }.toMap(),
        fieldCheckerDispatchers = fieldCheckerExecutors.map { (k, v) -> k to CheckerDispatcherImpl(v) }.toMap(),
        typeCheckerDispatchers = typeCheckerExecutors.map { (k, v) -> k to CheckerDispatcherImpl(v) }.toMap(),
    )
}
