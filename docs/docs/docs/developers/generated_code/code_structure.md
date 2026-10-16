---
title: Code Structure
description: Tenant API Code Structure
---


## Code Structure

### Packages

=== "Kotlin"

    Generated code lives in two packages:

    * `viaduct.api.grts`: This is where we put generated code for classes used to represent GraphQL types, see description of GRTs below.

    * `<module-prefix>.resolverbases`: This is where we put generated base classes to be inherited by resolver classes (more on these shortly).

=== "Java"

    Generated code lives in two packages:

    * `viaduct.java.grts`: The application's generated GraphQL representational types. Import Java GRTs from this package rather than the Kotlin `viaduct.api.grts` package.
    * `<module-prefix>.resolverbases`: Generated resolver base classes for a tenant module, such as `QueryResolvers`, `CharacterResolvers`, and `NodeResolvers`.

    The Java module plugin configures the source set and shared GRT dependency. Your implementation subclasses the generated base class and returns `CompletableFuture` from its resolver method. See [Generated Code](index.md) for getters, builders, and the different Java generation model.
