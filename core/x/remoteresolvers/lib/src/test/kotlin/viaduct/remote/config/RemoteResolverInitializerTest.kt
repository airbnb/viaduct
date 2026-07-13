package viaduct.remote.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.fixtures.SimpleFieldResolverExecutor
import viaduct.remote.registry.FieldExecutorRegistry

class RemoteResolverInitializerTest {
    private fun cfg(
        enabled: Boolean,
        fieldProxyingEnabled: Boolean = true,
        remoteFields: Set<String> = emptySet(),
        rrsServerName: String = "rrs-${System.nanoTime()}",
        callbackEndpoint: String = "rrp-${System.nanoTime()}",
    ) = RemoteResolverConfig(
        enabled = enabled,
        remoteTypes = emptySet(),
        remoteFields = remoteFields,
        fieldProxyingEnabled = fieldProxyingEnabled,
        rrsServerName = rrsServerName,
        callbackEndpoint = callbackEndpoint,
    )

    private fun networkCfg() =
        RemoteResolverConfig(
            enabled = true,
            mode = RemoteResolverMode.NETWORK,
            remoteTypes = emptySet(),
            // Port 0 lets the OS pick a free callback port.
            rrsHost = "localhost",
            rrsPort = 0,
            callbackPort = 0,
        )

    @Test
    fun `disabled config returns NO_OP`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = false))
        assertSame(ProxyResolverFactory.NO_OP, initializer.initialize())
        initializer.close()
    }

    @Test
    fun `enabled config produces a non-NO_OP factory`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        try {
            assertTrue(initializer.initialize() !== ProxyResolverFactory.NO_OP)
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `repeat initialize returns the same factory`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        try {
            assertSame(initializer.initialize(), initializer.initialize())
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `initialize after close throws IllegalStateException`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        initializer.initialize()
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `close before initialize still terminates the instance`() {
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `network mode produces a non-NO_OP factory and binds the callback port`() {
        val initializer = RemoteResolverInitializer(networkCfg())
        try {
            assertTrue(initializer.initialize() !== ProxyResolverFactory.NO_OP)
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `network mode initialize is idempotent`() {
        val initializer = RemoteResolverInitializer(networkCfg())
        try {
            assertSame(initializer.initialize(), initializer.initialize())
        } finally {
            initializer.close()
        }
    }

    @Test
    fun `network mode initialize after close throws IllegalStateException`() {
        val initializer = RemoteResolverInitializer(networkCfg())
        initializer.initialize()
        initializer.close()
        assertThrows<IllegalStateException> { initializer.initialize() }
    }

    @Test
    fun `default field proxying excludes built-in resolvers but proxies tenant fields`() {
        // With no explicit VIADUCT_REMOTE_RESOLVER_FIELDS, the default predicate proxies tenant field
        // resolvers but excludes the engine's built-ins (Query.node/nodes, @namespaceType) — those are
        // in-JVM framework ops that shouldn't take a gRPC hop. An explicit list can still opt them in.
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        try {
            val factory = initializer.initialize()
            assertNull(
                factory.proxyField(fieldExecutor("Query.node", "query-node-resolver")),
                "built-in Query.node must not be proxied by default"
            )
            assertNotNull(
                factory.proxyField(fieldExecutor("Character.isAdult", "isAdult")),
                "a plain tenant field should be proxied by default"
            )
        } finally {
            initializer.close()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `disabled field proxying never proxies any field`() {
        // fieldProxyingEnabled = false is the field-only off switch: even a plain tenant field the
        // default predicate would proxy runs locally (returns null). Node proxying is unaffected.
        val initializer = RemoteResolverInitializer(cfg(enabled = true, fieldProxyingEnabled = false))
        try {
            val factory = initializer.initialize()
            assertNull(
                factory.proxyField(fieldExecutor("Character.isAdult", "isAdult")),
                "no field may be proxied when field proxying is disabled"
            )
        } finally {
            initializer.close()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `explicit remoteFields opts a built-in in and excludes unlisted tenant fields`() {
        // A non-empty whitelist keys solely on resolverId: only listed coordinates proxy (even engine
        // built-ins like Query.node), and every unlisted field — including tenant fields — is skipped.
        val initializer = RemoteResolverInitializer(cfg(enabled = true, remoteFields = setOf("Query.node")))
        try {
            val factory = initializer.initialize()
            assertNotNull(
                factory.proxyField(fieldExecutor("Query.node", "query-node-resolver")),
                "an explicitly listed built-in must be proxied"
            )
            assertNull(
                factory.proxyField(fieldExecutor("Character.isAdult", "isAdult")),
                "an unlisted tenant field must not be proxied when the whitelist is non-empty"
            )
        } finally {
            initializer.close()
            FieldExecutorRegistry.clear()
        }
    }

    @Test
    fun `default field proxying excludes all engine built-in resolvers`() {
        // Every name in BUILT_IN_FIELD_RESOLVER_NAMES (Query.node/nodes, @namespaceType) is an in-JVM
        // framework op, so the default predicate excludes each by ResolverMetadata.name — the coordinate
        // is irrelevant, only the built-in name matters.
        val initializer = RemoteResolverInitializer(cfg(enabled = true))
        try {
            val factory = initializer.initialize()
            for (builtInName in listOf("query-node-resolver", "query-nodes-resolver", "namespace-type-resolver")) {
                assertNull(
                    factory.proxyField(fieldExecutor("Query.field", builtInName)),
                    "built-in $builtInName must not be proxied by default"
                )
            }
        } finally {
            initializer.close()
            FieldExecutorRegistry.clear()
        }
    }

    // Reuses the shared simple field-resolver fixture, overriding only the metadata name the
    // default-proxy predicate keys on. batchResolve is inherited but never called by these tests.
    private fun fieldExecutor(
        id: String,
        metadataName: String
    ) = SimpleFieldResolverExecutor(id, ResolverMetadata.forModern(metadataName))
}
