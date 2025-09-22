---
title: Getting Started
description: Setting up and modifying a simple Viaduct application
weight: 1
---

## Getting Started with Viaduct

To get you started with Viaduct, we have a created a number of small demonstration applications to illustrate what is a Viaduct application and how you write and build one.  You can find these at [github.com/viaduct-graphql](https://github.com/viaduct-graphql).  In particular, in order of complexity, we have a CLI starter, a Spring starter, and a more full-featured StarWars application.  This document walks you through the CLI starter in some detail, and provides suggestions for how to explore the other two.

### Requirements

Java 21 must be on the path or available via JAVA_HOME.

We assume a basic familiarity with GraphQL.  The GraphQL Foundation has excellent [learning materials](https://graphql.org/learn/) on this topic.

### Running the Simple Application

Start by making a local clone of the [CLI starter](https://github.com/viaduct-graphql/cli-starter):

```shell
git clone https://github.com/viaduct-graphql/cli-starter.git
```

Next `cd` into that clone and test that your environment is ready by typing:

```shell
./gradle test
```

After building and testing the CLI demo, Gradle should report that the build was successful.

Although Viaduct is typically hosted in a Web server, to keep things simple the CLI demo simply calls it directly from the application's main directory.  You can do this through Gradle:

```shell
./gradlew -q run --args="'{ greeting }'"
```

Here is the full schema for this simple application:

```graphql
type Query {
   greeting: String @resolver
   author: String @resolver
}
```

Through the command line you issue any query against this schema.



### Touring the Application

The main elements of this application's source-code directory are the following:

```
build.gradle.kts
src/
   main/viaduct/schema/schema.graphqls
   main/kotlin/com/example/viadapp/ViaductApplication.kt
   main/kotlin/com/example/viadapp/resolvers/HelloWorldResolvers.kt
   ...
...
```

You can see that a Viaduct application is a Gradle project.  The file:

* `schema.graphqls` contains the GraphQL schema for the application.  All files matching `**/*.graphqls` in that directory collectively define the schema of the application

* `ViaductApplication.kt` contains the `main` function for this command-line tool.  This function creates an instance of a `Viaduct` engine and sends the query it gets as its command-line argument to that engine.

* `HelloWorldResolvers.kt` contain the "application logic" for our schema, the code that says how to resolver the fields of the schema.


At the top of `build.gradle.kts` you'll see:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
    alias(libs.plugins.viaduct.module)
    application
}
```

You can see the two Viaduct plugins appearing here.  Viaduct applications are structured as multi-project Gradle builds.  The root project must contain the _application_ plugin shown above: this plugin coordinates certain build processes across the engine application.  In addition, one or more projects also apply the _module_ plugin, which indicates that that project contains application code (in our case, the code found in `HelloWorldResolvers.kt`).  As this example shows, a Viaduct application can be as simple as a single project build, in which case both plugins are applied to that one project.

## Extending the Application

### Extending the Schema

Let’s explore our sample application more deeply by extending its functionality.  Viaduct is a “schema first” GraphQL environment, meaning you write your schema first, and then generate classes to write your code against.  So in that spirit, let’s start by extending the schema in `schema.graphqls` (in the location noted above).  You should see the following in that file:

```
extend type Query {
  greeting: String @resolver
  author: String @resolver
}
```

Viaduct itself has built-in definitions for the root GraphQL types `Query` and `Mutation` (Viaduct doesn’t yet support subscriptions).  Since `Query` is built-in, application code should extend it as illustrated above.  You’ll also see in this schema fragment that both fields have `@resolver` applied to them, meaning that a developer-provided function is needed to compute their respective value.  (*All* fields of `Query` must have `@resolver` applied to them.)

Let’s extend this schema to add a new field, `attributedGreeting`, which will attribute the greeting to its author:

```
extend type Query @scope(to: ["publicScope"]) {
  greeting: String @resolver
  author: String @resolver
  attributedGreeting: AttributedGreeting @resolver
}

type AttributedGreeting {
  greeting: String
}
```

There’s no practical reason to have the `AttributedGreeting` type here: `attributedGreeting` could’ve just been a `String`.  We’re using a GraphQL object-type here in order to demonstrate some features of our API.

### Extending the code

After you make a schema change, in your application root directory (the one that applies the application plugin) you need to run `./gradlew viaductCodegen`.  This will regenerate the code needed to build your application.

Having done that, you need to write a resolver for our new field.  Actually, you could add it to `HelloWorldResolvers.kt`: resolvers for this application can be placed to any file as long it's in the `com.example.viadapp.resolvers` package.  To support copy-and-paste, create a file named `AttributedGreetingResolver.kt` (or whatever) in the same subdirectory as `HelloWorldResolvers.kt` and copy the following code into it:

```kotlin
package com.example.viadapp.resolvers

import viaduct.api.Resolver
import com.example.viadapp.helloworld.resolverbases.QueryResolvers
import viaduct.api.grts.AttributedGreeting // The class generated for our AttributedGreeting type

// New code:
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

The basic idea is that the resolver for `attributedGreeting` will combine `author` and `greeting` into a string that attributes the greeting to the author.  The resolver has access to these two fields because its `@Resolver` annotation indicates that it needs those fields: if the `@Resolver` annotation didn’t mention the `author` field, for example, then the attempt to read `objectValue.author()` would fail at runtime.

Let’s examine some of the details of Viaduct that are illustrated by this file:

* For every GraphQL type, Viaduct generates a Kotlin interface or class to represent it in code.  We call these GraphQL Representational Types, or GRTs for short.  These GRTs are all placed in the `viaduct.api.grts` package.

* Viaduct generates also generate a _resolver base class_ for writing resolvers.  For each field _Type.field_ with an `@resolver` directive in the schema, we generate a base class _`Type.Field`_.  As illustrated by our example, to write a resolver for that field, you subclass this base class and override the `resolve` function.

You can save this file and the run:

```shell
./gradlew -q run --args="'{ attributedGreeting }'"
```

and you should see the appropriate response.

## What’s Next

**StarWars Deep Dive.**  The StarWars application comes with a deep dive document describing Viaduct features in some detail.

**Documentation.**  Explore our [documentation site](..).

**Building your own application.**  Pick the structure that you like best - single project, two project (root plus on module), or multi-module.  Make a copy of the respective demo app (CLI, Spring, or StarWars) and make a copy.

