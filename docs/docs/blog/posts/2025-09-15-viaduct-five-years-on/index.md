---
date: 2025-09-15
authors:
  - adammiskiewicz
  - aileenchen
  - raymiestata
categories:
  - Announcements
description: A more powerful engine and a simpler API for our data-oriented mesh.
---

# Viaduct, Five Years On: Modernizing the Data-Oriented Service Mesh

In November 2020 we published a post about Viaduct, our data-oriented service mesh. Today, we're excited to announce our intent to make Viaduct available as open-source software (OSS).

Before we talk about OSS, here’s a quick update on Viaduct’s adoption and evolution at Airbnb over the last five years. Since 2020, traffic through Viaduct has grown by a factor of eight. The number of teams hosting code in Viaduct has doubled to 130+ (with hundreds of weekly active developers). The codebase hosted by Viaduct has tripled to over 1.5M lines (plus about the same in test code). We’ve achieved all this while keeping operational overhead constant, halving incident-minutes, and keeping costs growing linearly with QPS.

# What’s the same?

Three principles have guided Viaduct since day one and still anchor the project: a **central schema** served by **hosted business logic** via a **re-entrant** API.

**Central schema** Viaduct serves our central schema: a single, integrated schema connecting all of our domains across the company. While that schema is developed in a *decentralized* manner by many teams, it’s one, highly connected graph. Over 75% of Viaduct requests are internal because Viaduct has become a “one‑stop” data-oriented mesh connecting developers to all of our data and capabilities.

**Hosted business logic** From the beginning, we’ve encouraged teams to host their business logic directly in Viaduct. This runs counter to what many consider to be best practices in GraphQL, which is that GraphQL servers should be a thin layer over microservices that host the real business logic. We’ve created a serverless platform for hosting business logic, allowing our developers to focus on writing business logic rather than on operational issues. As noted by Katie, an engineer on our Media team:

As we migrate our media APIs into Viaduct, we’re looking forward to retiring a handful of standalone services. Centralizing everything means less overhead, fewer moving parts, and a much smoother developer experience\!

**Re-entrancy**

At the heart of our developer experience is what we call *re-entrancy*: Logic hosted on Viaduct composes with other logic hosted on Viaduct by issuing GraphQL fragments and queries. Re-entrancy has been crucial for maintaining modularity in a large codebase and avoiding classic monolith hazards.

# What’s changed?

For most of Viaduct’s history, evolution has been bottom-up and reactive to immediate developer needs. We added capabilities incrementally, which helped us move fast, but also produced multiple ways to accomplish similar tasks (some well‑supported, others not) and created a confusing developer experience, especially for new teams. Another side-effect of this reactive approach has been a lack of architectural integrity. The interfaces between the layers of Viaduct, described in more detail below, are loose and often arbitrary, and the abstraction boundary between the Viaduct framework and the code that it hosts is weak. As a result, it has become increasingly difficult to make changes to Viaduct without disrupting our customer base.

To address these issues, over a year ago we launched a major initiative we call “Viaduct Modern,” a ground-up overhaul of both the developer-facing API and the execution engine.

## Tenant API

One driving principle of Viaduct Modern has been to simplify and rationalize the API we provide to developers in Viaduct, which we call the “Tenant API”. The following diagram captures the decision tree one faced when deciding how to implement functionality in the old API:

![][image4]

Each oval in this diagram represents a different mechanism for writing code. In contrast, the new API offers just two mechanisms: node resolvers and field resolvers.

![][image3]

The choice between the two is driven by the schema itself, not ad‑hoc distinctions based on a feature’s behavior. We unified the APIs for both resolver types wherever possible, which simplifies dev experience. After four years evolving the API in a use‑case‑driven manner, we distilled the best ideas into a single simple surface (and left the mistakes behind).

## Tenant modularity

Strong abstraction boundaries are essential in any large codebase. Microservices achieve this via service definitions and RPC API boundaries; Viaduct achieves it via **modules** plus **re‑entrancy**.

Modularity in the central schema and hosted code has evolved. Initially, all we had was a vague set of conventions for organizing code into team-owned directories. There was no formal concept of a module, and schema and code were kept in separate source directories with unenforced naming conventions to connect the two. Over time, we evolved that into a more formal abstraction we call a “tenant module.” A tenant module is a unit of schema together with the code that implements that schema, and crucially, is owned by a single team.  While we encourage rich graph‑level connections across modules, we **discourage direct code dependencies** between modules. Instead, modules compose via GraphQL fragments and queries. Viaduct Modern extends and simplifies these re‑entrancy tools.

Let’s look at an example. Imagine two teams, a “Core User” team that owns and manages the basic profile data of users, and then a “Messaging” team that operates a messaging platform for users to interact with each other.  In our example, the Messaging team would like to define a `displayName` field on a `User`, which is used in their user interface. This would look something like this:

| Core User team | Messaging team |
| ----- | ----- |
|  `type User implements Node {   id: ID!   firstName: String   lastName: String   ... }`  |  `extend type User {   displayName: String @resolver }`  |
|  `class UserResolver : Nodes.User() { @Inject val userClient: UserServiceClient @Inject val userResponseMapper: UserResponseMapper override suspend fun resolve(ctx.Context): User {   val r = userClient.fetch(ctx.id)   return userResponseMapper(r) }`  |  `@Resolver("firstName lastName") class DisplayNameResolver : UserResolvers.DisplayName() { override suspend fun resolve(ctx: Context) : String {   val f = ctx.objectValue.getFirstName()   val l = ctx.objectValue.getLastName()   return "$f ${l.first()}." } }`  |

On the left-hand side is the base definition of the `User` type that lives in the Core User team’s module. This base definition defines the first- and last-name fields (among many others), and it’s the Core User team’s responsibility to materialize those fields.  On the right hand side, the Messaging team extends the `User` type with the display name field, and also indicates that they intend to provide a resolver for it. In the lower-right is the code for that resolver. This code has a `@Resolver` annotation that indicates which fields of the `User` object it needs to implement the `displayName` field. The Messaging team doesn’t need to understand which module these fields come from, and their code doesn’t depend on code from the Core User team. Instead, the Messaging team states their data needs in a declarative fashion.

## Framework modularity

A major goal of Viaduct Modern has been to make the framework itself more modular.  We want to enable faster improvements to Viaduct—especially regarding performance and reliability—without extensive changes to application code. Viaduct is composed of three main layers: the GraphQL execution engine, the tenant API, and the hosted application code. While these layers made sense, the interfaces between them were weak, making Viaduct difficult to update. The new design is focused on creating strong abstraction boundaries between these layers to improve flexibility and maintainability:

![][image1]

The most significant change is the boundary between the **engine** and the developer-facing **tenant API**. In the previous system, that boundary hardly existed. Viaduct Modern defines a strong **engine API** whose core is a **dynamically‑typed** representation of GraphQL values (input and output objects as simple maps from field name → value). The tenant API, by contrast, is **statically typed**: we generate Kotlin classes for every GraphQL type in the central schema. In the new architecture, generated types are thin wrappers over the dynamic representation. The tenant API forms the bridge between the engine’s untyped world and tenants’ typed world.

This separation lets us evolve the engine in relative isolation (to improve latency, throughput, and reliability) and evolve the tenant API in relative isolation (to improve dev experience). Large changes will still cross the boundary, but as Viaduct Modern stabilizes, that should be rare.

## Migration without a “big bang”

Viaduct Modern would be a non‑starter if it required a step‑function migration of a million+ lines of code. To enable gradual migration, we’re shipping **two tenant APIs** side‑by‑side—the new **Modern** API and the existing **Classic** API—both on top of the new engine. This lets teams realize performance/cost wins from the engine immediately, while adopting the ergonomic wins of the Modern API over time.

Shipping two APIs has also improved the Engine API design: building two tenant runtimes simultaneously forced us to keep the engine’s concerns clean and general. Over time, we expect to build additional tenant APIs on the engine (e.g., **TypeScript**).

## Other improvements

Viaduct has seen a lot of other improvements since 2020\. The story is too long to be told in this post, but to list some of the highlights:

* **Observability.** Hosting software from 100+ teams means our framework has to make ownership and attribution crystal clear. In the old system, there is no clear dividing line between tenant code and framework code, so our instrumentation includes a bit of guesswork in its attribution to parties. The new architecture draws a crisp boundary, which enables deeper, more accurate attribution.

* **Build time.** We’re schema‑first: Developers write schema as source, and Viaduct generates code to provide a strongly typed, ergonomic surface. At our scale, build time is a constant battle. Over the years we’ve made numerous investments to improve build time, including direct-to-bytecode code generation that bypasses lengthy compilation of generated code. We anticipate that the improved modularity in Viaduct Modern will keep build times in check as the codebase grows.

* **Dispatcher.** We run Viaduct as a horizontally-scaled Kubernetes app. To mitigate blast radius, we use a dispatcher that routes operations to deployment shards, applying [shuffle sharding](https://aws.amazon.com/blogs/architecture/shuffle-sharding-massive-and-magical-fault-isolation/). It also simplifies isolating offline vs. online traffic and hosting experimental framework builds. (We don’t currently plan to open‑source the dispatcher as it’s tightly tied to Airbnb’s serving framework, but we may talk more about our strategies here in the future\!)

# Open-sourcing Viaduct

Our intent from the start of Viaduct Modern was to open‑source it. We believe that setting out to build software for the world will result in higher quality software for ourselves. Also, as a significant consumer of open-source software, we feel an obligation to give back. Last but not least, while we’ve learned a lot by working with Airbnb developers, we think Viaduct can be massively improved by incorporating ideas and contributions from the wider community.

We’re open‑sourcing at an interesting moment. Viaduct is **mature and battle‑tested** *and also* **new and evolving**.  The new engine is now in full production, while the new tenant API is still in alpha. We’ve implemented a small but robust kernel of the new API and are using it in a few (demanding) use cases. We’re investing heavily in the Modern API and migrating our own workloads to it, but it’s early days. By open‑sourcing early, we hope to grow a community who will shape the API with us together.

# Is Viaduct for you?

Although our use case proves Viaduct can scale to massive graphs, we think it’s also a great GraphQL server when you’re just starting. We’ve emphasized developer ergonomics from day one, and we believe Viaduct provides one of the best environments for building GraphQL solutions. Whether you’re operating a supergraph today or just kicking the tires, we’d love for you to try Viaduct Modern and tell us what works—and what doesn’t.

# See us at GraphQLConf 2025

We’re honored to be giving three talks at this year’s [GraphQLConf](https://graphql.org/conf/2025/) in Amsterdam, **September 8–10, 2025**. We’ll share lessons from supporting hundreds of tenants, dive into technical details of the new engine, and introduce **batch resolvers**, a Modern‑API feature that smooths adoption of the data‑loader pattern. We hope to see you there (in person or virtually).

[image1]: image1.png

[image3]: image3.png

[image4]: image4.png
