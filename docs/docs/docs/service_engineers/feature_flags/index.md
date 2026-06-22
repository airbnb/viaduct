---
title: Feature Flags
description: Controlling Viaduct runtime behaviour with FlagManager.
---

Viaduct exposes certain runtime behaviours as feature flags so that service engineers can control them without redeploying. The [`FlagManager`][FlagManager] SPI is the integration point: implement it to bridge Viaduct flags into your organisation's flag system, or use one of the built-in implementations when you don't need dynamic control.

## Default behaviour

If you do not call `withFlagManager`, Viaduct uses `FlagManager.default`. The default enables flags that are considered safe and stable, and disables experimental or killswitch flags. See [Available flags](#available-flags) for the per-flag defaults.

## Available flags

All flags are defined in `FlagManager.Flags`.

| Flag | Default | Description |
|---|---|---|
| `KILLSWITCH_NON_BLOCKING_ENQUEUE_FLUSH` | disabled | Reverts the data-loader coroutine dispatcher to blocking enqueue flush. Enable to recover from a regression introduced by the non-blocking implementation. |
| `ENABLE_SYNC_VALUE_COMPUTATION` | disabled | Allows resolvers to compute values synchronously where possible. Experimental — do not enable in production without guidance from the Viaduct team. |

## Wiring a FlagManager

Pass your implementation to `ViaductBuilder.withFlagManager`. The StarWars demo wires Viaduct through `ViaductBuilder`; adding a flag manager follows the same pattern:

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

## Implementing FlagManager

`isEnabled` is called on the hot path during every query execution. Implementations must return quickly — do not perform I/O or blocking work inside `isEnabled`. Fetch flag state asynchronously and cache it; let `isEnabled` read from the cache.

```kotlin
class StarWarsFlagManager(
    private val flagCache: FlagCache,
) : FlagManager {
    override fun isEnabled(flag: FlagManager.Flag): Boolean =
        flagCache.get(flag.flagName) ?: FlagManager.default.isEnabled(flag)
}
```

Falling back to `FlagManager.default` for unknown flags means your service automatically picks up the recommended default for any flags added in future Viaduct releases.

## Built-in implementations

| Implementation | Behaviour |
|---|---|
| `FlagManager.default` | Framework-recommended defaults — use this as a fallback |
| `FlagManager.disabled` | All flags disabled — useful in tests to isolate behaviour |

## See also

- [Observability](../observability/index.md) — Metrics and error reporting
- [Dependency Injection](../dependency_injection/index.md) — Wiring a DI container into Viaduct
