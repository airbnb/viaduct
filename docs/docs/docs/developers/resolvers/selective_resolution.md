---
title: Selective Resolution
description: Loading only the fields requested from selective resolvers
---


Field and node resolvers may be declared as selective through an argument on the resolver directive: `@resolver(isSelective: true)`.

A selective resolver may invoke `ctx.selections()` from inside its resolve function. This method returns a `SelectionSet` describing the fields that the resolver must resolve. A resolver may use this to produce values for only the fields that are selected, improving resolver performance by skipping computationally expensive fields that were not selected.

## Resolver Outputs
There are some do's and don'ts when it comes to what a selective resolver returns. To help frame this, let's look at the `Query.foo` resolver for this schema and query:

```graphql
  # schema
  extend type Query { foo:Foo @resolver(isSelective:true) }
  type Foo { x:Int, y:Int, bar:Bar @resolver }
  type Bar { z:Int }

  # query
  { foo { x } }
```

Every Viaduct resolver has an [output selection set](index.md#output-selection-sets), which are the fields in its subtree it is responsible for producing values for. In the case of `Query.foo`, its output selection set is `{ x y }`. This output selection set does not include `bar` nor any of the fields under `Bar`, which are owned by the `Foo.bar` resolver.

A selective resolver *must* produce values for the selected subset of its output selection set. This requirement is different than the one for non-selective resolvers, which must produce values for the full output selection set. Given the example above, the `Query.foo` resolver need only produce a value for `{ x }`

A selective resolver *may* also produce values for fields in its output selection set that were not selected. In the query above, the `Query.foo` resolver may return a value for `y`. This value will be recorded by Viaduct and may be used if a value for `y` becomes required later in execution.

Finally, no resolver may produce values for fields outside its output selection set; this restriction applies to both selective and non-selective resolvers. In the query above, the `Query.foo` resolver may not produce a value for `bar` -- any value produced for this field will be ignored.

## Execution
When executing a query, Viaduct may execute a selective resolver multiple times to resolve the same logical data.

To illustrate this, consider this schema and query:

```graphql
# schema
extend type Query {
  baz: Baz @resolver(isSelective: true)
}
type Baz {
  x: Int @resolver # this resolver selects 'y' in its object fragment
  y: Int
}

# query
{ baz { x } }
```

When this query is executed, Viaduct will:

1. Invoke `Query.baz` with the intersection of its output selection set and the client selections. This intersection is the empty selection set `{}` -- the resolvers job is to resolve the existence of a `Baz` object without producing any field values.
1. Traverse into the returned `Baz` object and resolve the `x` selection, which will uncover that fields dependency on `y`. Because `y` was not produced in the first execution of `Query.baz`, Viaduct will re-invoke the resolver for `Query.baz`.
1. Re-invoke `Query.baz` with selections `{y}`. The resolver's job is to resolve the existence of a `Baz` object and produce a value for `y`.

This example illustrates some key properties of how Viaduct handles selective resolvers:

- a selective resolver is executed for only the selections that Viaduct knows must be produced
- when a selective resolver is executed multiple times, the `SelectionSet` given to the resolver will describe selections that have not been previously resolved
- if a selective resolver is executed multiple times, it should make a best effort to return consistent data

### Consistency Requirements
This last point on consistency is important! In the example above, the `Query.baz` resolver could be inconsistent by returning a non-null `Baz` object on its first invocation and a null value on its second invocation. This is one example of inconsistent resolution, though the larger family includes:

- a non-null value becomes null
- an abstract type changes its concrete type
- a list changes its size
- a list changes its ordering
- a node or root field reference changes its identity

While Viaduct is able to tolerate some forms of inconsistent resolution, data that is *structurally* inconsistent is irreconcilable. If Viaduct detects structurally inconsistent data, then it will emit a field error for the resolving field.

As a final note, a list that changes its ordering is a special kind of structural inconsistency that Viaduct is not always able to detect. When this occurs, the data resolved by Viaduct may be corrupted by combining fields from different list items. To prevent this from happening, it is important to verify that any data sources used for list-typed fields are ordered.

## Mutations

Because selective resolvers may be executed multiple times, mutation fields are not allowed to be selective. This applies to fields on the root Mutation object as well as any namespace types reachable from Mutation.
