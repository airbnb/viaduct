---
title: Clone Approach
description: Get by cloning a starter application
---


This guide walks you through getting started with Viaduct by cloning one of our starter applications.

## Getting Started

To get you started with Viaduct, we have created a number of small demonstration applications to illustrate what a Viaduct application is and how you write and build one. You can find these at [github.com/viaduct-dev](https://github.com/viaduct-dev). In particular, in order of complexity, we have a CLI starter, a Spring starter, and a more full-featured StarWars application.

### Running the Application

Start by making a local clone of the [CLI starter](https://github.com/viaduct-dev/cli-starter):

```shell
git clone https://github.com/viaduct-dev/cli-starter.git
```

Next, `cd` into that clone and test that your environment is ready by typing:

```shell
./gradlew test
```

After building and testing the CLI demo, Gradle should report that the build was successful.

Although Viaduct is typically hosted in a web server, to keep things simple the CLI demo calls it directly from the application's main function. You can do this through Gradle:

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

Through the command line you can issue any query against this schema.

## What's Next

Continue to [Touring the Application](../../tour/index.md) to understand the structure of a Viaduct application.
