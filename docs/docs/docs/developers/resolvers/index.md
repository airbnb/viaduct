---
title: Resolvers
description: Understanding resolvers in Viaduct
---


In Viaduct, all module code is provided in the form of either a *node resolver* or a *field resolver*. Node and field resolvers are implemented similarly:

* **Schema**: The schema is the source of truth for what resolvers should exist. Define node types and add the `@resolver` directive to fields you want to provide resolvers for.
* **Generated base classes**: Viaduct generates abstract classes for all node and field resolvers based on the schema. Each generated class contains either a `resolve` method or a `batchResolve` method, depending on the `@resolver` directive's `isBatching` argument.
* **Implementation**: Implement a resolver by providing a subclass of the generated base class and overriding the generated method.

## Output selection sets

Output selection sets are an important concept in the Viaduct API. Every node and field resolver is responsible for resolving the fields in its output selection set. This includes direct and nested fields that themselves do not have a resolver. The node and field resolver pages provide more details with examples.

Field resolvers declare the data they depend on as *required selection sets* in the `@Resolver` annotation. When several resolvers in a module need the same selections, you can factor them into a reusable [named fragment](named_fragments.md) and spread it (`...FragmentName`) instead of repeating them.

## Injection

Viaduct is designed to create instances of resolver classes using a dependency injection framework, allowing your resolver classes to obtain their dependencies using injection. This framework is [configured into a Viaduct application at startup](../../service_engineers/dependency_injection/index.md).
