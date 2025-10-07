# Viaduct Framework Tutorials

A comprehensive, hands-on tutorial series that teaches you how to build GraphQL APIs with the Viaduct framework. Each tutorial builds on the previous one, taking you from basic concepts to advanced optimization patterns.


## Tutorial Notes
-  Function: createGlobalIdString is a TEST-ONLY utility method provided by FeatureAppTestBase.
-  Function: getInternalId is a TEST-ONLY utility method provided by FeatureAppTestBase.
-  Each test scenario builds a new instance of viaduct.

## Prerequisites

- Basic understanding of GraphQL concepts (queries, mutations, types)
- Familiarity with Kotlin programming language
- Understanding of database and API concepts

## Tutorial Series Overview

This series consists of 10 progressive tutorials, each demonstrating core Viaduct concepts through working code examples and tests. Follow them in order for the best learning experience.

## How to Use the Tutorials

Start with Tutorial 1 and work your way through to Tutorial 10. Each tutorial builds on concepts from previous ones.

### Navigation Within Files
Each tutorial file includes navigation comments:
```kotlin
/**
 * PREVIOUS: [viaduct.tenant.tutorial01.SimpleFieldResolverFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial03.SimpleResolversFeatureAppTest]
 */
```

### Tutorial Structure

Each tutorial follows a consistent format:
- **Learning Objectives** - What you'll master in this tutorial
- **Viaduct Features Demonstrated** - Framework features you'll use
- **Concepts Covered** - Technical concepts explained
- **Working Code** - Complete, runnable examples
- **Tests** - Demonstrations of functionality
- **Navigation** - Links to previous and next tutorials

## Complete Tutorial Path

### 1. Basic Field Resolvers
**File:** [SimpleFieldResolverFeatureAppTest.kt](tutorial01/SimpleFieldResolverFeatureAppTest.kt)

**What you'll learn:**
- The most basic Viaduct resolver pattern
- How `@resolver` directive generates base classes
- Relationship between SDL schema and Kotlin code

**Key concepts:** SDL to Kotlin generation, basic resolver implementation

---

### 2. Node Resolvers
**File:** [SimpleNodeResolverFeatureAppTest.kt](tutorial02/SimpleNodeResolverFeatureAppTest.kt)

**What you'll learn:**
- Node Resolvers for object-by-ID patterns
- GlobalID system for type-safe object references
- Integration between Field and Node Resolvers

**Key concepts:** Relay Global Object Identification, type safety with GlobalIDs

---

### 3. Combined Resolvers
**File:** [SimpleResolversFeatureAppTest.kt](tutorial03/SimpleResolversFeatureAppTest.kt)

**What you'll learn:**
- Combining Node Resolvers and Field Resolvers in one schema
- `objectValueFragment` for accessing parent object data
- Computed fields that depend on other fields

**Key concepts:** Separation of object creation vs field computation, dependency resolution

---

### 4. Backing Data
**File:** [SimpleBackingDataFeatureAppTest.kt](tutorial04/SimpleBackingDataFeatureAppTest.kt)

**What you'll learn:**
- Eliminating redundant expensive operations across multiple fields
- Sharing complex Kotlin objects between field resolvers
- `@backingData` directive and custom class specification

**Key concepts:** Performance optimization through data sharing, external service integration

---

### 5. Mutations
**File:** [SimpleMutationsFeatureAppTest.kt](tutorial05/SimpleMutationsFeatureAppTest.kt)

**What you'll learn:**
- GraphQL mutations for data modification
- ID extraction from mutation results for chaining operations
- Node Resolver integration with mutations

**Key concepts:** Create/Read/Update patterns, ID extraction, mutation-to-query workflows

---

### 6. Scopes
**File:** [SimpleScopesFeatureAppTest.kt](tutorial06/SimpleScopesFeatureAppTest.kt)

**What you'll learn:**
- API security through field-level access control
- Deploying different API versions for different client types
- Organizing GraphQL schemas by scope (USER, ADMIN, INTERNAL)

**Key concepts:** Multi-tenant API architecture, security through schema separation

---

### 7. Batch Resolvers
**File:** [SimpleBatchResolverFeatureAppTest.kt](tutorial07/SimpleBatchResolverFeatureAppTest.kt)

**What you'll learn:**
- Solving the N+1 query problem for related data
- Batching multiple field requests into single operations

**Key concepts:** N+1 problem solutions, DataLoader pattern, performance optimization

---

### 8. Batch Node Resolvers
**File:** [BatchNodeResolverFeatureAppTest.kt](tutorial08/BatchNodeResolverFeatureAppTest.kt)

**What you'll learn:**
- Applying batching to Node Resolver operations
- Optimizing multiple object lookups by GlobalID
- Handling mixed valid/invalid IDs in batch operations

**Key concepts:** Object-level batching, error isolation, performance monitoring

---

### 9. Variables & Directives
**File:** [VariablesDirectivesFeatureAppTest.kt](tutorial09/VariablesDirectivesFeatureAppTest.kt)

**What you'll learn:**
- Controlling GraphQL directives (`@include`/`@skip`) dynamically
- Using variables to conditionally fetch fields at runtime
- Three patterns: declarative, VariablesProvider, and argument-based

**Key concepts:** Runtime field selection optimization, conditional data access

---

### 10. Variables for Arguments
**File:** [VariablesForArgumentsFeatureAppTest.kt](tutorial10/VariablesForArgumentsFeatureAppTest.kt)

**What you'll learn:**
- Controlling GraphQL field arguments dynamically using variables
- Dynamic argument injection into selection sets
- Conditional argument passing based on business logic

**Key concepts:** Advanced variable usage, argument transformation, conditional behavior

## Getting the Most Out of These Tutorials

1. **Read the Learning Objectives** first to understand what you'll gain
2. **Run the tests** to see the code in action
3. **Study the code comments** for detailed explanations
4. **Experiment** by modifying examples to test your understanding
5. **Follow the navigation** to maintain proper learning sequence

## Core Concepts You'll Master

- **GraphQL API Design** - Proper schema design and resolver architecture
- **Performance Optimization** - Batch resolvers, backing data, and N+1 problem solutions
- **Security Patterns** - Scoped APIs and access control
- **Advanced Features** - Variables, directives, and dynamic behavior
- **Production Readiness** - Error handling, monitoring, and best practices

## Support and Further Learning

Each tutorial is self-contained with comprehensive examples and explanations. The code demonstrates production-ready patterns you can apply to your own Viaduct applications.

For additional support:
- Review the inline code comments for detailed explanations
- Run the tests to see expected behavior
- Experiment with the code to deepen understanding

# Demo Applications
For more detailed, real-world examples of Viaduct in action, explore the complete demo applications located
in the demoapps directory at the root of the project. There you'll find three fully-functional applications:
- [Star Wars API](../../../../../../../demoapps/starwars) - A comprehensive GraphQL API demonstrating advanced Viaduct patterns
- [CLI Starter](../../../../../../../demoapps/cli-starter) - Command-line application starter template
- [Spring Starter](../../../../../../../demoapps/spring-starter) - Spring Boot integration example


Each of these demoapps showcase how to use viaduct in a gradle project.
