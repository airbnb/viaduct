---
title: Observability
description: Monitoring and Logging in Viaduct
weight: 20
no_list: true
---

Viaduct provides comprehensive observability features to help you monitor, debug, and optimize your GraphQL API. This includes built-in metrics collection, error tracking, and integration with popular monitoring systems through <a href="https://micrometer.io/" target="_blank" rel="noopener noreferrer">Micrometer</a>.

## Overview

Viaduct automatically tracks key metrics across your GraphQL operations without requiring any code changes in your resolvers. The system focuses on three main areas:

* **Latency monitoring** - Track execution times across operations, fields, and resolvers
* **Error tracking** - Monitor success/failure rates and attribute errors to specific components
* **Attribution** - Understand which components contribute to latency and errors

## Documentation Structure

* **[Setup Guide](setup)** - How to enable and configure metrics in your Viaduct instance
* **[Error Handling](error_handling)** - Custom error reporting and handling

## Quick Start

Enable observability in your Viaduct instance by providing a `MeterRegistry`:

```kotlin
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import viaduct.service.ViaductBuilder

val meterRegistry: MeterRegistry = SimpleMeterRegistry()

val viaduct = ViaductBuilder()
    .withMeterRegistry(meterRegistry)
    .withTenantAPIBootstrapperBuilder(myBootstrapper)
    .build()
```

Once configured, Viaduct automatically emits metrics for all GraphQL operations. See the [Setup Guide](setup) for detailed configuration options.

## Available Metrics

Viaduct emits three primary metric types, all implemented as <a href="https://micrometer.io/docs/concepts#_timers" target="_blank" rel="noopener noreferrer">Micrometer Timers</a>:

### 1. viaduct.execution

Full execution lifecycle metric measuring end-to-end execution time for the entire GraphQL request, from parsing through response serialization.

**Measurements:**
* Duration (timer) with percentiles: p50, p75, p90, p95
* Count (number of executions)

**Tags:**
* `operation_name` - GraphQL operation name from the query (e.g., `GetUser`, `SearchProducts`)
  * Only present if the operation is named in the query
* `success` - Execution success indicator
  * `true` - No exceptions thrown AND data is present in the response
  * `false` - Exception occurred OR no data in response (even if partial data exists)

**Use cases:**
* Monitor overall API health and performance
* Track SLAs at the operation level
* Identify operations with high error rates

### 2. viaduct.operation

Operation-level metric measuring the time to execute the specific GraphQL operation after parsing and validation.

**Measurements:**
* Duration (timer) with percentiles: p50, p75, p90, p95
* Count (number of executions)

**Tags:**
* `operation_name` - GraphQL operation definition name from the query document
  * Only present if the operation definition includes a name

**Use cases:**
* Measure execution performance excluding parsing/validation overhead
* Compare performance across different operations
* Identify slow operations for optimization

### 3. viaduct.field

Field-level metric measuring the time to fetch/resolve individual GraphQL fields. This is the most granular metric and helps identify specific bottlenecks.

**Measurements:**
* Duration (timer) with percentiles: p50, p75, p90, p95
* Count (number of field resolutions)

**Tags:**
* `operation_name` - GraphQL operation name (if available)
* `field` - Fully qualified field path
  * Format: `ParentType.fieldName` (e.g., `User.email`, `Query.searchProducts`)
  * For root fields: just `fieldName` if parent type unavailable
* `success` - Field resolution success indicator
  * `true` - No exception thrown during field fetch
  * `false` - Exception thrown during field resolution

**Use cases:**
* Identify slow fields and resolvers
* Monitor error rates for specific fields
* Understand which fields contribute most to overall latency
* Attribute performance issues to specific tenant modules

## Metric Collection Details

* **Automatic collection** - Metrics are collected automatically, requiring no changes to your resolver code
* **Percentile distribution** - All metrics include p50, p75, p90, and p95 percentiles
* **Low overhead** - Minimal performance impact on your GraphQL operations
* **Accurate attribution** - Metrics correctly identify which operations and fields are responsible for latency and errors

## Use Cases

### Latency Analysis

* Determine latency across various percentiles for operations and fields
* Identify critical paths in request execution that contribute to overall latency
* Understand why specific fields or resolvers are slow
* Attribute slowness to specific tenant modules or components

### Error Monitoring

* Monitor partial and full failure rates for operations
* Identify which fields or resolvers are causing errors
* Track error rates over time to detect regressions
* Attribute errors to responsible components for faster debugging

### Understanding Field Dependencies

Field-level metrics help you understand relationships between fields:

* **Field dependencies** - See which fields trigger the resolution of other fields
* **Resolver impact** - Track how resolver performance affects overall request latency
* **Execution frequency** - Monitor how often specific fields are resolved
* **Critical path analysis** - Identify which fields contribute most to request latency

For example, as a tenant developer you can:
* Understand why your field is slow by examining dependent field metrics
* See which operations most frequently trigger your field resolution
* Monitor error rates for fields your resolvers depend on

## Integration with Monitoring Systems

Viaduct uses <a href="https://micrometer.io/" target="_blank" rel="noopener noreferrer">Micrometer</a> as its metrics facade, enabling integration with many monitoring systems including Prometheus, Datadog, CloudWatch, StatsD, Graphite, and more.

## Next Steps

* **[Setup Guide](setup)** - Learn how to configure metrics collection
* **[Error Handling](error_handling)** - Configure custom error reporting
