---
title: Viaduct Roadmap
hide:
- navigation
- toc
---




Feature Support in the Engine and API.
{.mt-5}


!!! note
    Roadmap is subject to change.

## Feature Support

| Name                                | Status             | Description                                                                                                                                                                                                                                                                                            |
|-------------------------------------|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Resolvers MVP                       | Released           | [Resolvers](../docs/developers/resolvers/index.md)                                                                                                                                                                                                                                                     |
| Observability                       | Released           | [Observability](../docs/service_engineers/observability/index.md)                                                                                                                                                                                                                                      |
| Scopes                              | Released           | [Scopes](../docs/developers/scopes/index.md)                                                                                                                                                                                                                                                           |
| Multi-tenancy/ multi module support | Preview            | [Multi-tenancy](../docs/service_engineers/multi_tenancy/index.md)                                                                                                                                                                                                                                      |
| Batch resolvers                     | Preview            | [Batch Resolution](../docs/developers/resolvers/batch_resolution.md)                                                                                                                                                                                                                                   |
| Mutations                           | Preview            | [Mutations](../docs/developers/resolvers/mutations.md)                                                                                                                                                                                                                                                 |
| Development Server                  | Preview            | Internal HTTP server for quickly previewing Viaduct tenants.                                                                                                                                                                                                                                           |
| Java API                            | Preview            | An alternative to the Kotlin tenant API written in Java. Preview shipped in [v0.20.0](https://github.com/airbnb/viaduct/commit/61d2eefd8ca2d8ad7e6fec14429e87ff347d25b2).                                                                                                                              |
| Subqueries                          | Under Development  | [Subqueries](../docs/developers/resolvers/subqueries.md)                                                                                                                                                                                                                                               |
| Object Mapping                      | Under Development  | Object mapping allows the mapping of a generic object type (like a Thrift object type) to a GraphQL type.                                                                                                                                                                                              |
| Coding Agent Support                | Under Development  | A mechanism for creating Viaduct applications via its existing example code and the contained markdown files, to be refined into a Claude code skill in order to be able to create Viaduct applications more easily.                                                                                   |
| Build Time Validation               | Under Development  | "Shift-left" mechanisms to validate schema violations at build time.                                                                                                                                                                                                                                   |
| Schema Customization                | Under Development  | Support for custom root types. Support for custom scalar types is planned but not yet under development.                                                                                                                                                                                               |
| Connections                         | Experimental       | [Pagination](../docs/developers/pagination/index.md) — cursor-based connections with `fromList`, `fromSlice`, and `fromEdges` builder utilities                                                                                                                                                        |
| Factory Types                       | Planned for H1 '26 | Factory types are a straight-forward way for tenants to share functions in a Kotlin-native manner without breaking our principle of interacting “only through the graph.” More specifically, a factory type defines one or more factory functions that can be used by other modules to construct GRTs. |
| Named Fragments                     | Planned for H1 '26 | Reusable part of a GraphQL query that you can define once and use in multiple required selection sets.                                                                                                                                                                                                 |
| Visibility                          | Planned for H1 '26 | Implement a @visibility directive that controls what internal module code can see.                                                                                                                                                                                                                     |
| Subscriptions                       | Planned for H1 '26 | Support for [GraphQL Subscriptions](https://graphql.org/learn/subscriptions/)                                                                                                                                                                                                                          |
| Parent/Child Relationships          | Planned for H1 '26 | In the context of Viaduct, parent-child relationships define hierarchical or associated data relationships across GraphQL types. These relationships allow one type (the parent) to reference or contain another type (the child), enabling structured data querying and retrieval.                    |
| AI generated mock data              | Planned for H1 '26 | When testing Viaduct resolvers, engineers need to manually mock out data for these fragments, which is time-consuming and can eventually lead to mocks getting out of sync with the fragments they implement as resolvers evolve over time. This effort will aid with auto-generating mock data.       |


