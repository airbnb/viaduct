package viaduct.remote.registry

import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * Registry of [NodeResolverExecutor]s keyed by GraphQL type name. Kept separate from
 * [FieldExecutorRegistry] (field executors) so the two keyspaces and value types never mix.
 */
object NodeExecutorRegistry : ExecutorRegistry<NodeResolverExecutor>(NodeResolverExecutor::typeName)
