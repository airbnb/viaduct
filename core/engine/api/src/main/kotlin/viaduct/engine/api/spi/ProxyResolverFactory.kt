package viaduct.engine.api.spi

/**
 * A factory for creating proxy resolvers that wrap tenant-written resolvers.
 *
 * At bootstrap time, after the initial resolver registry is created, the engine will call
 * [proxyField] or [proxyNode] on every field- and node-resolver executor. If the factory
 * returns null, the engine will use the original executor (no proxying). If the factory
 * returns a new executor, the engine will use this new executor in place of the original one.
 *
 * It becomes the responsibility of the proxy resolver to call the original resolver it was passed.
 * Proxy resolvers enable a variety of use cases including remote execution, instrumentation,
 * caching, and more.
 *
 * Example usage:
 * ```
 * val factory = object : ProxyResolverFactory {
 *     override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor? {
 *         // Wrap certain node resolvers for remote execution
 *         if (shouldExecuteRemotely(executor.typeName)) {
 *             return RemoteNodeResolverExecutor(executor)
 *         }
 *         return null // Use original executor
 *     }
 *
 *     override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor? {
 *         // Not proxying field resolvers in this example
 *         return null
 *     }
 * }
 *
 * ViaductBuilder()
 *     .withProxyResolverFactory(factory)
 *     .build()
 * ```
 */
interface ProxyResolverFactory {
    /** Returns a proxy wrapping [executor], or null to leave it unchanged. */
    fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor?

    /** Returns a proxy wrapping [executor], or null to leave it unchanged. */
    fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor?

    companion object {
        /** Pass-through implementation that leaves all resolvers unchanged. */
        @JvmField
        val NO_OP = object : ProxyResolverFactory {
            override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor? = null

            override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor? = null
        }
    }
}
