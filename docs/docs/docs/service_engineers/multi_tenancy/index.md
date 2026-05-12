---
title: Multi-tenancy
description: Allowing multiple teams to deploy their own isolated GraphQL subschemas (tenants) within shared Viaduct infrastructure with independent resolvers.
---

Viaduct's multi-tenant architecture allows multiple teams to independently develop and deploy their own GraphQL schemas (called **tenants**) within a shared Viaduct infrastructure. Each tenant owns its schema definitions and resolvers while contributing to a unified central schema.

This guide explains how to use multiple build modules and configure tenants to serve portions of a central schema.

## What is a Tenant?

A **tenant** is an isolated GraphQL subschema with its own:

- GraphQL SDL (schema) files defining types, queries, and mutations
- Kotlin resolvers implementing the business logic
- Independent deployment lifecycle

Multiple tenants are composed together at build time to form the **central schema** that your Viaduct application exposes.

## Module Architecture

Viaduct organizes tenants into a hierarchical module structure. Understanding this hierarchy is essential for managing dependencies and schema composition.

### Module Hierarchy Levels

1. **Module Roots**: Top-level organizational units (e.g., `data`, `entity`, `presentation`)
2. **Sub-modules**: Groupings within a module root (e.g., `entity/listingblock`, `entity/userblock`)
3. **Tenants**: Individual GraphQL schemas (e.g., `entity/listingblock/stays/listing`)

### Example Structure

```
modules/
├── entity/                          # Module root
│   ├── common/                      # Sub-module (shared types)
│   │   └── commontypes/             # Tenant
│   │       ├── schema/
│   │       └── src/main/kotlin/
│   ├── listingblock/                # Sub-module
│   │   └── stays/
│   │       └── listing/             # Tenant
│   │           ├── schema/
│   │           └── src/main/kotlin/
│   └── userblock/                   # Sub-module
│       └── user/                    # Tenant
│           ├── schema/
│           └── src/main/kotlin/
├── data/                            # Module root
│   ├── payments/                    # Tenant
│   └── orders/                      # Tenant
└── presentation/                    # Module root
    └── checkout/                    # Tenant
```

### Module Dependencies

Modules form a dependency graph that determines schema composition order. Common patterns include:

```
presentation → data → entity → entity/common
```

When you build a tenant, Viaduct automatically includes all schemas from ancestor modules. For example:

- Building `presentation/checkout` includes schemas from: `presentation`, `data`, `entity`, and `entity/common`
- Building `entity/listingblock/stays/listing` includes schemas from: `entity/listingblock`, `entity`, and `entity/common`

This dependency resolution ensures that:

- Types defined in lower-level modules are available to higher-level modules
- Schema composition happens in the correct order
- Common types are shared across tenants

## Tenant Directory Structure

Each tenant follows a standard layout:

```
modules/<module>/<tenant>/
├── build.gradle.kts                # Build configuration
├── schema/
│   └── src/main/resources/graphql/
│       └── <domain>/<entity>/      # GraphQL schema files
│           ├── Type1.graphqls
│           ├── Type2.graphqls
│           └── queries/
│               └── AllType1Query.graphqls
└── src/main/kotlin/
    └── com/yourcompany/viaduct/<tenant>/
        ├── loaders/                 # Data loading logic
        └── resolvers/               # Resolver implementations
```

### Schema Files Location

Schema files must be placed in:

```
schema/src/main/resources/graphql/<hierarchical-path>/
```

The hierarchical path typically mirrors your module structure:

```
schema/src/main/resources/graphql/entity/listingblock/stays/listing/
├── Listing.graphqls
├── StayDetails.graphqls
└── queries/
    └── AllListingsQuery.graphqls
```

This naming convention:

- Ensures schema files are properly discovered during build
- Prevents naming conflicts between modules
- Organizes schemas by ownership and domain

## Creating Multiple Tenants

When creating new tenants, follow the standard directory structure and conventions outlined above. For detailed guidance on implementing resolvers, schemas, and build configuration, see the [Star Wars Tutorial](../../../getting_started/starwars/index.md), which provides comprehensive examples of:

- Query resolvers, node resolvers, and field resolvers
- Batch resolution patterns for efficient data loading
- GraphQL schema design and type extensions
- Build configuration with Gradle

## Using Multiple Tenants to Serve a Central Schema

### Schema Composition

At build time, Viaduct merges all tenant schemas into a single **central schema**. The composition process:

1. **Discovery**: Scans all tenant schema directories
2. **Aggregation**: Collects all `.graphqls` files per module
3. **Merging**: Combines types, respecting GraphQL type system rules
4. **Validation**: Ensures the composed schema is valid
5. **Code Generation**: Generates resolver base classes

### Type Extensions Across Tenants

Tenants can extend types defined in other tenants using GraphQL's `extend` keyword:

```graphql title="modules/entity/listingblock/stays/listing/schema/.../Listing.graphqls"
type Listing @scope(to: ["default"]) {
  id: ID!
  title: String!
}
```

```graphql title="modules/entity/listingblock/stays/amenities/schema/.../ListingAmenities.graphqls"
extend type Listing @scope(to: ["default"]) {
  amenities: [Amenity!]!
  hasWifi: Boolean!
}
```

After composition, the central schema contains:

```graphql
type Listing @scope(to: ["default"]) {
  id: ID!
  title: String!
  amenities: [Amenity!]!
  hasWifi: Boolean!
}
```

!!! warning
    When extending types across tenants, ensure the extending tenant depends on the module containing the original type definition.

### Shared Types and Common Modules

For types used across multiple tenants, create a common module:

```graphql title="modules/entity/common/commontypes/schema/.../CommonTypes.graphqls"
type Address @scope(to: ["default"]) {
  street: String!
  city: String!
  country: String!
}

type Money @scope(to: ["default"]) {
  amount: BigDecimal!
  currency: String!
}
```

All tenants depending on `entity/common` can reference these types:

```graphql title="modules/entity/userblock/user/schema/.../User.graphqls"
type User @scope(to: ["default"]) {
  id: ID!
  billingAddress: Address   # References common type
}
```

## Using Scopes for Schema Visibility

Scopes control which fields appear in different schemas. This allows serving **multiple schemas** from a single central definition.

### Defining Scopes

Annotate types and fields with `@scope` directives:

```graphql
type User @scope(to: ["public", "internal"]) {
  id: ID!
  name: String!
}

extend type User @scope(to: ["internal"]) {
  email: String!
  ipAddress: String!
  internalNotes: String!
}
```

### Multiple Schema IDs

Configure your Viaduct application to expose multiple schemas:

```kotlin title="src/main/kotlin/.../ViaductConfiguration.kt"
@Factory
class ViaductConfiguration {
    @Bean
    fun providesViaduct(): Viaduct {
        return BasicViaductFactory.create(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(
                    SchemaId.Scoped("public", setOf("public")).toSchemaScopeInfo(),
                    SchemaId.Scoped("internal", setOf("public", "internal")).toSchemaScopeInfo(),
                )
            ),
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "com.yourcompany.viaduct"
            )
        )
    }
}
```

### Runtime Schema Selection

Choose which schema to use per request:

```kotlin
suspend fun graphql(
    @Body request: Map<String, Any>,
    @Header("X-Schema") schemaHeader: String?
): HttpResponse<Map<String, Any>> {

    val schemaId = when (schemaHeader) {
        "internal" -> INTERNAL_SCHEMA_ID
        else -> PUBLIC_SCHEMA_ID
    }

    val executionInput = createExecutionInput(request)
    val result = viaduct.executeAsync(executionInput, schemaId).await()

    return HttpResponse.ok(result.toSpecification())
}
```

Requests using the "public" schema only see:

```graphql
type User {
  id: ID!
  name: String!
}
```

Requests using the "internal" schema see all fields:

```graphql
type User {
  id: ID!
  name: String!
  email: String!
  ipAddress: String!
  internalNotes: String!
}
```

### Use Cases for Multiple Schemas

**External vs Internal APIs**

```graphql
type Product @scope(to: ["public", "internal"]) {
  id: ID!
  name: String!
  price: Money!
}

extend type Product @scope(to: ["internal"]) {
  costBasis: Money!
  profitMargin: Float!
  inventoryCount: Int!
}
```

**Feature Flags and Gradual Rollout**

```graphql
type Feature @scope(to: ["default", "beta"]) {
  id: ID!
  name: String!
}

extend type Feature @scope(to: ["beta"]) {
  experimentalSettings: ExperimentalSettings!
}
```

**Multi-Tenant SaaS Applications**

```graphql
type Dashboard @scope(to: ["enterprise", "pro", "free"]) {
  basicMetrics: [Metric!]!
}

extend type Dashboard @scope(to: ["enterprise", "pro"]) {
  advancedAnalytics: Analytics!
}

extend type Dashboard @scope(to: ["enterprise"]) {
  customReports: [Report!]!
  apiAccess: APICredentials!
}
```

## Practical Example: Multi-Tenant E-Commerce

Let's build a multi-tenant schema for an e-commerce platform with separate teams owning different domains.

### Module Structure

```
modules/
├── entity/
│   ├── common/              # Shared types
│   ├── productblock/        # Product catalog team
│   ├── userblock/           # User management team
│   └── orderblock/          # Order processing team
├── data/                    # Data access layer
│   ├── payments/            # Payment service integration
│   └── inventory/           # Inventory service integration
└── presentation/            # API facade
    └── storefront/          # Customer-facing API
```

### Common Types (Entity Layer)

```graphql title="modules/entity/common/commontypes/schema/.../CommonTypes.graphqls"
type Money @scope(to: ["public", "internal"]) {
  amount: BigDecimal!
  currency: String!
}

type Address @scope(to: ["public", "internal"]) {
  street: String!
  city: String!
  postalCode: String!
  country: String!
}
```

### Product Tenant

```graphql title="modules/entity/productblock/catalog/schema/.../Product.graphqls"
type Product @scope(to: ["public", "internal"]) {
  id: ID!
  name: String!
  description: String!
  price: Money!
  images: [String!]!
}

extend type Product @scope(to: ["internal"]) {
  costBasis: Money!
  supplierInfo: String!
  profitMargin: Float!
}

type Query @scope(to: ["public", "internal"]) {
  product(id: ID!): Product
  searchProducts(query: String!): [Product!]!
}
```

### User Tenant

```graphql title="modules/entity/userblock/user/schema/.../User.graphqls"
type User @scope(to: ["public", "internal"]) {
  id: ID!
  name: String!
}

extend type User @scope(to: ["internal"]) {
  email: String!
  registeredAt: DateTime!
  lastLogin: DateTime
}
```

### Order Tenant

```graphql title="modules/entity/orderblock/order/schema/.../Order.graphqls"
type Order @scope(to: ["public", "internal"]) {
  id: ID!
  orderNumber: String!
  customer: User!
  items: [OrderItem!]!
  total: Money!
  status: OrderStatus!
}

type OrderItem @scope(to: ["public", "internal"]) {
  product: Product!
  quantity: Int!
  price: Money!
}

enum OrderStatus @scope(to: ["public", "internal"]) {
  PENDING
  CONFIRMED
  SHIPPED
  DELIVERED
  CANCELLED
}

extend type User @scope(to: ["public", "internal"]) {
  orders: [Order!]!
}

type Query @scope(to: ["public", "internal"]) {
  order(id: ID!): Order
}

type Mutation @scope(to: ["public"]) {
  createOrder(input: CreateOrderInput!): Order!
  cancelOrder(orderId: ID!): Order!
}
```

### Presentation Layer

The presentation layer can aggregate and reshape data from entity and data layers:

```graphql title="modules/presentation/storefront/schema/.../Storefront.graphqls"
type StorefrontData @scope(to: ["public"]) {
  featuredProducts: [Product!]!
  categories: [Category!]!
  userRecommendations(userId: ID!): [Product!]!
}

extend type Query @scope(to: ["public"]) {
  storefront: StorefrontData!
}
```

### Composed Central Schema

After composition, the central schema for the "public" scope includes:

- All types from all tenants with `@scope(to: ["public"])`
- Type extensions merged into base types
- Fields from the `Order` tenant extending the `User` type

Clients querying the public API can traverse the entire graph:

```graphql
query CustomerOrders {
  user(id: "123") {
    name
    orders {
      orderNumber
      total {
        amount
        currency
      }
      items {
        product {
          name
          price {
            amount
          }
        }
        quantity
      }
    }
  }
}
```

## Best Practices

### Module Organization

1. **Domain-Driven Design**: Organize tenants by business domain, not technical layers
2. **Shared Types**: Place common types in `entity/common` or similar base modules
3. **Minimize Dependencies**: Only depend on modules you actually need
4. **Clear Ownership**: Each tenant should have a single owning team

### Schema Design

1. **Use Type Extensions**: Extend types across tenant boundaries rather than duplicating
2. **Scope Consistently**: Apply scopes to all types—there is no default scope
3. **Hierarchical Naming**: Use paths that reflect module hierarchy in schema file locations
4. **Avoid Circular Dependencies**: Structure modules as a directed acyclic graph (DAG)

### Resolvers

1. **Package Conventions**: Follow consistent package naming across tenants
2. **Thin Resolvers**: Delegate business logic to service/loader classes
3. **Batch Loading**: Use data loaders to avoid N+1 queries
4. **Error Handling**: Use consistent error patterns across tenants

### Testing

1. **Per-Tenant Tests**: Write unit tests for each tenant's resolvers
2. **Integration Tests**: Test schema composition and cross-tenant queries
3. **Scope Validation**: Verify fields appear only in intended schemas

## Troubleshooting

### Schema Composition Errors

**Problem**: Build fails with "Unable to find concrete type for interface"

**Solution**: The interface is defined in module A, but the implementing type is in module B, and B doesn't depend on A. Either move the interface to a common ancestor module or add a dependency from B to A.

**Problem**: Type extensions don't appear in the schema

**Solution**: Ensure the extending tenant's module depends on the module containing the base type.

### Scope Issues

**Problem**: Fields are missing from the schema

**Solution**: Check that types and fields have the correct `@scope` annotations. Remember that fields in type extensions must use scopes that are subsets of the base type's scopes.

**Problem**: "Invalid scope usage within a type" error

**Solution**: Type extensions can only use scopes that were declared on the base type definition. Add the scope to the base type's `@scope` directive.

### Runtime Errors

**Problem**: Resolver not found at runtime

**Solution**: Ensure:

- Resolver class is annotated with `@Resolver`
- Resolver package is under the configured `tenantPackagePrefix`
- The tenant's `build.gradle.kts` includes the Viaduct tenant plugin

## See Also

- [Developers: Scopes](../../developers/scopes/index.md) — Detailed scope usage and validation rules
- [Developers: Resolvers](../../developers/resolvers/index.md) — Writing resolver implementations
- [Getting Started](../../../getting_started/index.md) — Understanding the starter application's structure
- [Schema Extensions](../schema_extensions/index.md) — Application-wide custom directives and types
