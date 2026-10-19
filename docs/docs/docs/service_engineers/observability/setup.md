---
title: Setup Guide
description: How to enable and configure metrics in your Viaduct instance
---


This guide walks you through enabling and configuring observability in your Viaduct instance. Metrics collection is automatic once you provide a MeterRegistry implementation.

## Prerequisites

Add the Micrometer dependency for your chosen monitoring system. Viaduct works with any Micrometer-supported registry.

These dependency declarations use Gradle's Kotlin DSL for both Kotlin and Java applications.

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

===! "Kotlin"

    ```kotlin
    import io.micrometer.core.instrument.simple.SimpleMeterRegistry

    val meterRegistry = SimpleMeterRegistry()
    ```

=== "Java"

    ```java
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ```

#### Prometheus

===! "Kotlin"

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

=== "Java"

    ```java
    PrometheusMeterRegistry prometheusMeterRegistry =
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    app.get("/metrics", (req, res) -> {
        res.type("text/plain; version=0.0.4");
        return prometheusMeterRegistry.scrape();
    });
    ```

The endpoint example uses Spark-style routing, where `app` is your configured router. With Micronaut, serve the string returned by `scrape()` from your metrics controller instead. The imports above match the Prometheus registry version shown in Prerequisites.

#### Other Monitoring Systems

Viaduct supports all Micrometer registries including Datadog, CloudWatch, StatsD, Graphite, and more. See the <a href="https://micrometer.io/docs/registry" target="_blank" rel="noopener noreferrer">Micrometer documentation</a> for configuration examples for your monitoring system.

### Step 2: Configure Viaduct with MeterRegistry

Pass your MeterRegistry to the ViaductBuilder:

===! "Kotlin"

    ```kotlin
    import viaduct.service.ViaductBuilder

    val viaduct = ViaductBuilder()
        .withMeterRegistry(meterRegistry)
        .withTenantModuleInjectorFactory(myInjectorFactory)
        .build()
    ```

=== "Java"

    ```java
    Viaduct viaduct = new ViaductBuilder()
        .withMeterRegistry(meterRegistry)
        .withTenantModuleInjectorFactory(myInjectorFactory)
        .build();
    ```

That's it! Viaduct will now automatically emit metrics for all GraphQL operations.

## Advanced Configuration

### Custom Percentiles

By default, Viaduct configures percentiles at p50, p75, p90, and p95. To customize percentiles, configure your MeterRegistry before passing it to Viaduct:

===! "Kotlin"

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

=== "Java"

    ```java
    meterRegistry.config().meterFilter(new MeterFilter() {
        @Override
        public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
            if (id.getName().startsWith("viaduct.")) {
                return config.merge(DistributionStatisticConfig.builder()
                    .percentiles(0.5, 0.75, 0.9, 0.95, 0.99, 0.999)
                    .build());
            }
            return config;
        }
    });
    ```

### Adding Common Tags

Add tags to all Viaduct metrics for filtering and aggregation:

===! "Kotlin"

    ```kotlin
    import io.micrometer.core.instrument.config.MeterFilter
    import io.micrometer.core.instrument.Tag

    meterRegistry.config().commonTags(listOf(
        Tag.of("service", "my-viaduct-service"),
        Tag.of("environment", "production"),
        Tag.of("region", "us-east-1")
    ))
    ```

=== "Java"

    ```java
    meterRegistry.config().commonTags(List.of(
        Tag.of("service", "my-viaduct-service"),
        Tag.of("environment", "production"),
        Tag.of("region", "us-east-1")
    ));
    ```

### Filtering Metrics

Disable specific metrics or add custom logic:

===! "Kotlin"

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

=== "Java"

    ```java
    meterRegistry.config().meterFilter(MeterFilter.deny(
        id -> id.getName().equals("viaduct.field")));

    meterRegistry.config().meterFilter(MeterFilter.deny(id ->
        id.getName().startsWith("viaduct.") && id.getTags().stream().noneMatch(tag ->
            tag.getKey().equals("operation_name") &&
            Set.of("GetUser", "SearchProducts").contains(tag.getValue()))));
    ```

### Histogram Configuration

Configure histogram buckets for better distribution analysis:

===! "Kotlin"

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

=== "Java"

    ```java
    meterRegistry.config().meterFilter(new MeterFilter() {
        @Override
        public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
            if (id.getName().startsWith("viaduct.")) {
                return config.merge(DistributionStatisticConfig.builder()
                    .percentilesHistogram(true)
                    .minimumExpectedValue((double) Duration.ofMillis(1).toNanos())
                    .maximumExpectedValue((double) Duration.ofSeconds(10).toNanos())
                    .build());
            }
            return config;
        }
    });
    ```

## Composite MeterRegistry

Use CompositeMeterRegistry to send metrics to multiple backends:

===! "Kotlin"

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
        .withTenantModuleInjectorFactory(myInjectorFactory)
        .build()
    ```

=== "Java"

    ```java
    CompositeMeterRegistry compositeMeterRegistry = new CompositeMeterRegistry();
    PrometheusMeterRegistry prometheusMeterRegistry =
        new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    compositeMeterRegistry.add(prometheusMeterRegistry);

    DatadogMeterRegistry datadogMeterRegistry = new DatadogMeterRegistry(datadogConfig, Clock.SYSTEM);
    compositeMeterRegistry.add(datadogMeterRegistry);

    Viaduct viaduct = new ViaductBuilder()
        .withMeterRegistry(compositeMeterRegistry)
        .withTenantModuleInjectorFactory(myInjectorFactory)
        .build();
    ```

## Verification

### Verify Metrics are Being Collected

The following verification runs from a test or command-line thread, where waiting with `join()` is appropriate. In an asynchronous HTTP handler, compose the future as shown in [Server Integration](../server_integration/index.md).

For SimpleMeterRegistry or testing:

===! "Kotlin"

    ```kotlin
    // Execute a query (executeAsync returns a future, callable from non-coroutine code)
    viaduct.executeAsync(executionInput).join()

    // Check metrics
    meterRegistry.meters.forEach { meter ->
        if (meter.id.name.startsWith("viaduct.")) {
            println("${meter.id.name} - ${meter.measure()}")
        }
    }
    ```

=== "Java"

    ```java
    viaduct.executeAsync(executionInput).join();

    meterRegistry.getMeters().forEach(meter -> {
        if (meter.getId().getName().startsWith("viaduct.")) {
            System.out.println(meter.getId().getName() + " - " + meter.measure());
        }
    });
    ```
