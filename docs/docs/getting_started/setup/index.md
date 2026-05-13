---
title: Project Setup
description: Set up your first Viaduct application
---


## Requirements

- Java 17+
- `git`
- Basic familiarity with GraphQL (see [GraphQL Foundation learning materials](https://graphql.org/learn/))
- Linux or Mac (Windows support coming soon)

## Compatibility

Viaduct is compatible with the following versions:

- **Language**: Kotlin (tenant code is written in Kotlin; Java tenants are not yet supported).
- **Java**: 17+
- **Gradle**: 8.10+ (Gradle 9.1.0+ supported with Kotlin 2.0+)
- **Kotlin**: Use a Kotlin version compatible with your Gradle version.
    - Gradle 8.x: Kotlin 1.9.x or 2.x
    - Gradle 9.x: Kotlin 2.0+

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
