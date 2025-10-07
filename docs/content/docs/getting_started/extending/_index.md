---
title: Extending the Application
description: Learn how to add new functionality to your Viaduct application
weight: 5
---

Let's explore our sample application more deeply by extending its functionality. Viaduct is a "schema first" GraphQL environment, meaning you write your schema first, and then generate classes to write your code against.

## Extending the Schema

Let's add a new field called `attributedGreeting`, which will attribute the greeting to its author. Open `schema.graphqls` and extend it as follows:

```graphql
extend type Query {
  greeting: String @resolver
  author: String @resolver
  attributedGreeting: AttributedGreeting @resolver
}

type AttributedGreeting {
  greeting: String
}
```

There's no practical reason to have the `AttributedGreeting` type here: `attributedGreeting` could've just been a `String`. We're using a GraphQL object-type here in order to demonstrate some features of our API.


This will regenerate the code needed to build your application. Viaduct generates Kotlin classes and interfaces that correspond to your GraphQL types, making it type-safe to work with your schema.

## Writing the Resolver

Now you need to write a resolver for the new field. You could add it to `HelloWorldResolvers.kt`: resolvers for this application can be placed in any file as long as it's in the `com.example.viadapp.resolvers` package.

To support copy-and-paste, create a file named `AttributedGreetingResolver.kt` in the same subdirectory as `HelloWorldResolvers.kt` and copy the following code into it:

```kotlin
package com.example.viadapp.resolvers

import viaduct.api.Resolver
import com.example.viadapp.helloworld.resolverbases.QueryResolvers
import viaduct.api.grts.AttributedGreeting

@Resolver("""
  greeting
  author
""")
class AttributedGreetingResolver : QueryResolvers.AttributedGreeting() {
    override suspend fun resolve(ctx: Context): AttributedGreeting {
        val greeting = ctx.objectValue.getGreeting()
        val author = ctx.objectValue.getAuthor()
        return AttributedGreeting.Builder(ctx)
            .greeting("$author says: \"$greeting\"")
            .build()
    }
}
```

## Understanding the Resolver

Let's break down what's happening in this resolver:

### The @Resolver Annotation

```kotlin
@Resolver("""
  greeting
  author
""")
```

The `@Resolver` annotation indicates that this resolver needs access to the `greeting` and `author` fields. If the annotation didn't mention the `author` field, for example, then the attempt to read `ctx.objectValue.getAuthor()` would fail at runtime.

This is an important feature of Viaduct: it allows you to declare dependencies between fields, ensuring efficient query execution.

### Accessing Field Values

```kotlin
val greeting = ctx.objectValue.getGreeting()
val author = ctx.objectValue.getAuthor()
```

The resolver can access the values of `greeting` and `author` through the `Context` object. These values are computed by their respective resolvers before this resolver runs.

### Building the Result

```kotlin
return AttributedGreeting.Builder(ctx)
    .greeting("$author says: \"$greeting\"")
    .build()
```

To create an instance of the `AttributedGreeting` GraphQL type, we use the generated builder class. All GraphQL object types have a corresponding builder for type-safe construction.

## Understanding Viaduct's Code Generation

Viaduct generates two main types of code:

### 1. GraphQL Representational Types (GRTs)

For every GraphQL type, Viaduct generates a Kotlin interface or class to represent it in code. We call these **GraphQL Representational Types**, or **GRTs** for short. These GRTs are all placed in the `viaduct.api.grts` package.

For example, our `AttributedGreeting` GraphQL type has a corresponding `AttributedGreeting` GRT that we import:

```kotlin
import viaduct.api.grts.AttributedGreeting
```

### 2. Resolver Base Classes

Viaduct also generates a **resolver base class** for writing resolvers. For each field `Type.field` with an `@resolver` directive in the schema, we generate a base class `Type.Field`.

As illustrated by our example, to write a resolver for that field, you subclass this base class and override the `resolve` function:

```kotlin
class AttributedGreetingResolver : QueryResolvers.AttributedGreeting() {
    override suspend fun resolve(ctx: Context): AttributedGreeting {
        // Implementation
    }
}
```

## Testing the New Field

Save your files and run:

```shell
./gradlew -q run --args="'{ attributedGreeting { greeting } }'"
```

You should see the appropriate response with the author attribution!

## What's Next

**StarWars Deep Dive.** The StarWars application comes with a deep dive document describing Viaduct features in some detail. [View the StarWars documentation](../starwars).

**Documentation.** Explore our [full documentation site](../../).

**Building your own application.** Pick the structure that you like best—single project, two project (root plus one module), or multi-module. Make a copy of the respective demo app (CLI, Spring, or StarWars) and customize it for your needs.
