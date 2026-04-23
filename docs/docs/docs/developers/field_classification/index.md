---
title: Field Classification
description: Types of fields in Viaduct and their usage.
---


### Private Fields

Private fields are fields that are only visible to the defining tenant module (the tenant that defines the fields), not visible to other tenant modules. It is used when a tenant developer wants to hold certain data only for internal calculation purposes (for resolving the private or public fields in the defining tenant module), not for external access (for resolving the fields defined in other tenant modules, or accessed by any Viaduct client services).

#### Backing Data Fields

Private fields can have any GraphQL type, including the Viaduct-defined scalar type `BackingData` (which is *only* allowed on private fields). For all types *other* than `BackingData`, code generation for private fields happens as for other fields. For `BackingData` fields, getters and setters are not generated; we only do runtime casting using dynamic setter/builder and getter methods. Note that we still have type-check-on-write for private fields. This means the dynamic builder for private fields will check for the correct type.

BackingData scalar typed field must have a `@backingData` directive specifying the type of the field. See usage of the directive [here](https://sourcegraph.a.musta.ch/airbnb/treehouse@456ddae601a0a54ba51755755bf5d5a8d5158dfd/-/blob/projects/viaduct/modules/entity/common/commontypes/schema/src/main/resources/graphql/entity/common/commontypes/Directives.graphqls?L1085).

An example of the backing data field can be found [here](https://sourcegraph.a.musta.ch/airbnb/treehouse@456ddae601a0a54ba51755755bf5d5a8d5158dfd/-/blob/projects/viaduct/modules/data/example/schema/src/main/resources/graphql/data/example/ExampleBackingData.graphqls?L1). Their resolvers can be found [here](https://sourcegraph.a.musta.ch/airbnb/treehouse@456ddae601a0a54ba51755755bf5d5a8d5158dfd/-/blob/projects/viaduct/modules/data/example/src/main/kotlin/com/airbnb/viaduct/data/example/resolvers/ExampleBackingData.kt?L7). Notice the resolver for backing data field returns a manually constructed object of the specifying backing data type. The resolver for the publicly accessible field uses a fragment to access the backing data field, and use it by calling the raw getter `ctx.getObjectValue().get(...)`

#### Private Visibility

As of today, we don’t support the “private to tenant module” visibility yet. We only support “private to Viaduct service”. So you still need to add `@scope(to: [“viaduct-private”])` to the private fields, to prevent them from being visible to Viaduct client services.
