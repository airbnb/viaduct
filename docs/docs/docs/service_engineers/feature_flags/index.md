---
title: Feature Flags
description: Controlling Viaduct runtime behaviour with FlagManager.
---

Viaduct exposes certain runtime behaviours as feature flags so that service engineers can control them without redeploying. The {{ kdoc("viaduct.service.api.spi.FlagManager", "FlagManager") }} SPI is the integration point: implement it to bridge Viaduct flags into your organisation's flag system, or use one of the built-in implementations when you don't need dynamic control.

## Default behaviour

If you do not call `withFlagManager`, Viaduct uses `FlagManager.Default`. The current default reports every framework flag as disabled. Use it as a fallback when your service does not override a flag. See [Available flags](#available-flags) for the per-flag defaults.

## Available flags

All flags are defined in `FlagManager.Flags`.

| Flag | Default | Description |
|---|---|---|
| `KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH` | disabled | Reverts the data-loader coroutine dispatcher to blocking enqueue flush. Enable to recover from a regression introduced by the non-blocking implementation. |
| `ENGINE2_ENABLED` | disabled | Selects the engine2 runtime. |
| `ENGINE2_BATCHING` | disabled | Reserved gate; enabling it is an invalid configuration until engine2 supports batching. |
| `ENABLE_MAT_RESOLUTION` | disabled | Enables differential resolver execution through the Mat workflow. |
| `ENABLE_INCREMENTAL_EXECUTION` | disabled | Enables incremental query execution. |
| `ENABLE_RESOLVER_OUTPUT_MISSING_FIELD_ERRORS` | disabled | Adds field errors when resolver output omits a requested field. |
| `ENABLE_CACHE_KEY_LOOKUP_PARTITIONING` | disabled | Deprecated compatibility flag; partitioning is always enabled. |
| `KILLSWITCH_FIELD_RSS_ORIGIN_FILTERING` | disabled | Restores legacy origin filtering for field required selections. |

## Wiring a FlagManager

Pass your implementation to `ViaductBuilder.withFlagManager`. The StarWars demo wires Viaduct through `ViaductBuilder`; adding a flag manager follows the same pattern:

===! "Kotlin"

    ```kotlin title="production/ViaductConfiguration.kt"
    import io.micronaut.context.annotation.Bean
    import io.micronaut.context.annotation.Factory
    import viaduct.service.ViaductBuilder
    import viaduct.service.api.Viaduct
    import viaduct.service.api.spi.FlagManager

    @Factory
    class ViaductConfiguration(
        private val tenantModuleInjectorFactory: MicronautTenantModuleInjectorFactory,
        private val meterRegistry: MeterRegistry,
        private val flagManager: StarWarsFlagManager,
    ) {
        @Bean
        fun providesViaduct(): Viaduct =
            ViaductBuilder()
                .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
                .withMeterRegistry(meterRegistry)
                .withFlagManager(flagManager)
                .build()
    }
    ```

=== "Java"

    ```java
    @Factory
    public final class ViaductConfiguration {
        private final MicronautTenantModuleInjectorFactory tenantModuleInjectorFactory;
        private final MeterRegistry meterRegistry;
        private final FlagManager flagManager;

        public ViaductConfiguration(MicronautTenantModuleInjectorFactory factory,
            MeterRegistry meterRegistry, FlagManager flagManager) {
            this.tenantModuleInjectorFactory = factory;
            this.meterRegistry = meterRegistry;
            this.flagManager = flagManager;
        }

        @Bean
        public Viaduct providesViaduct() {
            return new ViaductBuilder()
                .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
                .withMeterRegistry(meterRegistry)
                .withFlagManager(flagManager)
                .build();
        }
    }
    ```

## Implementing FlagManager

`isEnabled` is called on the hot path during every query execution. Implementations must return quickly — do not perform I/O or blocking work inside `isEnabled`. Fetch flag state asynchronously and cache it; let `isEnabled` read from the cache.

===! "Kotlin"

    ```kotlin
    class StarWarsFlagManager(
        private val flagCache: FlagCache,
    ) : FlagManager {
        override fun isEnabled(flag: FlagManager.Flag): Boolean =
            flagCache.get(flag.flagName) ?: FlagManager.Default.isEnabled(flag)
    }
    ```

=== "Java"

    ```java
    public final class StarWarsFlagManager implements FlagManager {
        private volatile Map<String, Boolean> snapshot = Map.of();

        public void update(Map<String, Boolean> flags) {
            snapshot = Map.copyOf(flags);
        }

        @Override
        public boolean isEnabled(Flag flag) {
            Boolean enabled = snapshot.get(flag.getFlagName());
            return enabled != null ? enabled : FlagManager.Default.INSTANCE.isEnabled(flag);
        }
    }
    ```

The Java example accepts immutable snapshots from your external flag client; call `update()` when that client refreshes. Kotlin singleton objects are accessed as `FlagManager.Default.INSTANCE` and `FlagManager.Disabled.INSTANCE` from Java.

Falling back to `FlagManager.Default` for unknown flags means your service automatically picks up the recommended default for any flags added in future Viaduct releases.

## Built-in implementations

| Implementation | Behaviour |
|---|---|
| `FlagManager.Default` | Framework-recommended defaults — use this as a fallback |
| `FlagManager.Disabled` | All flags disabled — useful in tests to isolate behaviour |

## See also

- [Observability](../observability/index.md) — Metrics and error reporting
- [Dependency Injection](../dependency_injection/index.md) — Wiring a DI container into Viaduct
