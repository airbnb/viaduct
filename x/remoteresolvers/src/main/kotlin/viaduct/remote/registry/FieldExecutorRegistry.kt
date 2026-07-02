package viaduct.remote.registry

import viaduct.engine.api.spi.FieldResolverExecutor

/**
 * Registry of [FieldResolverExecutor]s keyed by resolver id — the field coordinate
 * ("Type.field"). Kept separate from [NodeExecutorRegistry] (node executors) so the two
 * keyspaces and value types never mix.
 */
object FieldExecutorRegistry : ExecutorRegistry<FieldResolverExecutor>(FieldResolverExecutor::resolverId)
