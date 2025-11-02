---
title: Setup Guide
description: How to enable and configure metrics in your Viaduct instance
weight: 10
---

This guide walks you through enabling and configuring observability in your Viaduct instance. Metrics collection is automatic once you provide a MeterRegistry implementation.

## Prerequisites

Add the Micrometer dependency for your chosen monitoring system. Viaduct works with any Micrometer-supported registry.

For Prometheus:
```kotlin
dependencies {
    implementation("io.micrometer:micrometer-registry-prometheus:1.11.0")
}
```

For other registries, see the <a href="https://micrometer.io/docs" target="_blank" rel="noopener noreferrer">Micrometer documentation</a>.

## Basic Setup

### Step 1: Create a MeterRegistry

Choose and configure a MeterRegistry implementation based on your monitoring system:

#### In-Memory Registry (for testing)

```kotlin
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

val meterRegistry = SimpleMeterRegistry()
```

#### Prometheus

```kotlin
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

val prometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

// Expose metrics endpoint
app.get("/metrics") { req, res ->
    res.contentType("text/plain; version=0.0.4")
    prometheusMeterRegistry.scrape()
}
```

#### Other Monitoring Systems

Viaduct supports all Micrometer registries including Datadog, CloudWatch, StatsD, Graphite, and more. See the <a href="https://micrometer.io/docs/registry" target="_blank" rel="noopener noreferrer">Micrometer documentation</a> for configuration examples for your monitoring system.

### Step 2: Configure Viaduct with MeterRegistry

Pass your MeterRegistry to the ViaductBuilder:

```kotlin
import viaduct.service.ViaductBuilder

val viaduct = ViaductBuilder()
    .withMeterRegistry(meterRegistry)
    .withTenantAPIBootstrapperBuilder(myBootstrapper)
    .build()
```

That's it! Viaduct will now automatically emit metrics for all GraphQL operations.

## Advanced Configuration

### Custom Percentiles

By default, Viaduct configures percentiles at p50, p75, p90, and p95. To customize percentiles, configure your MeterRegistry before passing it to Viaduct:

```kotlin
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig

meterRegistry.config().meterFilter(object : MeterFilter {
    override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig? {
        if (id.name.startsWith("viaduct.")) {
            return config.merge(
                DistributionStatisticConfig.builder()
                    .percentiles(0.5, 0.75, 0.9, 0.95, 0.99, 0.999)
                    .build()
            )
        }
        return config
    }
})
```

### Adding Common Tags

Add tags to all Viaduct metrics for filtering and aggregation:

```kotlin
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.Tag

meterRegistry.config().commonTags(
    Tag.of("service", "my-viaduct-service"),
    Tag.of("environment", "production"),
    Tag.of("region", "us-east-1")
)
```

### Filtering Metrics

Disable specific metrics or add custom logic:

```kotlin
import io.micrometer.core.instrument.config.MeterFilter

// Disable field-level metrics (reduce cardinality)
meterRegistry.config().meterFilter(
    MeterFilter.deny { id -> id.name == "viaduct.field" }
)

// Only track metrics for specific operations
meterRegistry.config().meterFilter(
    MeterFilter.deny { id ->
        id.name.startsWith("viaduct.") &&
        !id.tags.any { tag ->
            tag.key == "operation_name" &&
            tag.value in setOf("GetUser", "SearchProducts")
        }
    }
)
```

### Histogram Configuration

Configure histogram buckets for better distribution analysis:

```kotlin
meterRegistry.config().meterFilter(object : MeterFilter {
    override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig? {
        if (id.name.startsWith("viaduct.")) {
            return config.merge(
                DistributionStatisticConfig.builder()
                    .percentilesHistogram(true)
                    .minimumExpectedValue(Duration.ofMillis(1).toNanos().toDouble())
                    .maximumExpectedValue(Duration.ofSeconds(10).toNanos().toDouble())
                    .build()
            )
        }
        return config
    }
})
```

## Composite MeterRegistry

Use CompositeMeterRegistry to send metrics to multiple backends:

```kotlin
import io.micrometer.core.instrument.composite.CompositeMeterRegistry

val compositeMeterRegistry = CompositeMeterRegistry()

// Add Prometheus for real-time querying
val prometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
compositeMeterRegistry.add(prometheusMeterRegistry)

// Add Datadog for long-term storage and alerting
val datadogMeterRegistry = DatadogMeterRegistry(datadogConfig, Clock.SYSTEM)
compositeMeterRegistry.add(datadogMeterRegistry)

// Use composite registry with Viaduct
val viaduct = ViaductBuilder()
    .withMeterRegistry(compositeMeterRegistry)
    .withTenantAPIBootstrapperBuilder(myBootstrapper)
    .build()
```

## Verification

### Verify Metrics are Being Collected

For SimpleMeterRegistry or testing:

```kotlin
// Execute a query
viaduct.execute(executionInput)

// Check metrics
meterRegistry.meters.forEach { meter ->
    if (meter.id.name.startsWith("viaduct.")) {
        println("${meter.id.name} - ${meter.measure()}")
    }
}
```
