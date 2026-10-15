---
title: Project Setup
description: Set up your first Viaduct application
---


## Requirements

- Java 17+
- `git`
- Basic familiarity with GraphQL (see [GraphQL Foundation learning materials](https://graphql.org/learn/))
- Linux, Mac, or Windows

## Compatibility

Viaduct is compatible with the following versions:

- **Java**: 17 or newer. CI tests 17, 21 and 25.
- **Tenant language**: Kotlin, or Java through the [Java Tenant API](/apis/javaapi-api/) (preview). The
  [`starwars-java`](https://github.com/airbnb/viaduct/tree/main/demoapps/starwars-java) demo app shows a Java tenant.
- **Kotlin**: 2.2 or 2.3 for Kotlin tenants. The Viaduct Gradle plugin rejects other versions. Kotlin 2.2 compiles
  for JVM targets up to 24, so targeting Java 25 needs Kotlin 2.3.
- **Gradle**: 8.11 or newer, and 9.1 or newer on Java 25. CI tests 8.11 and 9.1.

> **Note**: Choose Gradle and Kotlin versions that are compatible with each other — the [Gradle-Kotlin compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html#kotlin) is the authoritative reference.

## Choose Your Approach

There are two ways to set up your first Viaduct application based on your preference:

### [Quick Start - Clone Approach](clone/index.md)

Clone one of our starter applications and start building immediately.

**Best for:** Getting familiar with Viaduct quickly, exploring examples

### [Build From Scratch](from_scratch/index.md)

Build a Viaduct application from the ground up to understand how all the pieces fit together.

**Best for:** Understanding the complete structure, setting up custom projects

---

All approaches will result in a working application. After completing any setup path, continue to [Touring the Application](../tour/index.md) to understand the structure.
