---
title: Resolvers
description: Understanding resolvers in Viaduct
---


In Viaduct, all module code is provided in the form of either a *node resolver* or a *field resolver*. Node and field resolvers are implemented similarly:

* **Schema**: The schema is the source of truth for what resolvers should exist. Define node types and add the `@resolver` directive to fields you want to provide resolvers for.
* **Generated base classes**: Viaduct generates abstract classes for all node and field resolvers based on the schema. Each generated class contains either a `resolve` method or a `batchResolve` method, depending on the `@resolver` directive's `isBatching` argument.
* **Implementation**: Implement a resolver by providing a subclass of the generated base class and overriding the generated method.

## Responsibility sets

Responsibility sets (also known as "responsibility selection sets") are an important concept in the Viaduct API. Every node and field resolver is responsible for resolving the fields in its responsibility set. This includes all fields, including nested fields, that themselves do not have a resolver. The node and field resolver pages provide more details with examples.

## Injection

Viaduct is designed to create instances of resolver classes through dependency injection. You can define this behavior by implementing this interface:

```kotlin
interface TenantCodeInjector {
    fun <T> getProvider(clazz: Class<T>): Provider<T>
}
```

Examples of using this dependency injection mechanism are available in the demo applications.

If you do not provide an implementation of {{ kdoc("viaduct.service.api.spi.TenantCodeInjector") }}, Viaduct will use a naive default implementation that assumes a zero-argument constructor is available for all resolvers. Whenever the GraphQL execution engine needs to invoke a resolver, it will use this to construct an instance of the resolver. While sufficient for toy applications, we strongly suggest using a dependency injection framework for real applications.
