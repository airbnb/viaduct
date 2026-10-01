# Resolution Algorithms By Example

Most examples use the [resolver-test DSL](resolver-test-dsl.md) to describe complete deterministic resolver worlds. The Query-OER strategy example uses a direct registry table because the DSL does not declare Query-rooted resolver fragments. The examples illustrate demand operations and execution behavior: local closure determines everything needed to construct resolver inputs, output projection determines everything a producer must retain so client demand and downstream resolver inputs can be satisfied, and one associated Query OER shares declared-fragment production within each containing orchestration. Resolver worlds are presented top-down: root fields first, followed by the types reached from them.

## Demand Closure

Consider this world:

```graphql
extend type Query {
  user: User!
    @resolver(result: {first: 2, last: 3})
}

type User {
  first: Int!
  last: Int!

  display: Int!
    @resolver(
      of: "first last"
      result: "sum(first, last)"
    )

  greeting: Int!
    @resolver(
      of: "display"
      result: "sumplus1(display)"
    )
}
```

The client asks for:

```graphql
query {
  user {
    greeting
  }
}
```

`User.greeting` directly requires `display`, but `display` is another resolver field. Its resolver
requires the passive fields `first` and `last`, so closing the local demand is transitive:

```graphql
fragment on User {
  greeting
  display
  first
  last
}
```

The algorithm orders the exact field keys by their input dependencies:

```text
first, last -> display -> greeting
```

The `Query.user` resolver supplies `first = 2` and `last = 3`. The `display` resolver receives those
values and returns `5`; the `greeting` resolver then returns `6`.

At one concrete object-result occurrence, the resolution algorithm repeatedly selects each exact resolver occurrence not yet expanded, binds its variables, instantiates the variable templates in its fixed fragment at that occurrence path, and combines the resulting requirements with local demand. The finite closure reaches a fixed point when no activated resolver key remains unexpanded.

One question remains: `first` and `last` originated in the raw output of `Query.user`, but the
client asked only for `greeting`. The next section explains why they survive projection of the
producer's output.

## Output Projection

This section applies to selective producers. Resolver01 and Resolver02 instead consume each
resolver's complete finite returned value.

For a producer `P`:

- Local demand closure expands `P.of`, constructing `P`'s input from predecessor resolvers.
- Output projection needs the recursively closed input requirements of demanded successor
  resolvers inside `P`'s output.

Extend the world with:

```graphql
extend type Query {
  project: Project!
    @resolver(
      result: {
        owner: {
          first: 2
          last: 3
        }
      }
    )
}

type Project {
  owner: User!
}
```

The client asks for:

```graphql
query {
  project {
    owner {
      greeting
    }
  }
}
```

Closing the construction demand on Query adds nothing for `Query.project` because its `of` fragment is empty. `User.greeting` is nested output demand, not a key on the current Query object.

Without successor-demand extension, `Query.project` would receive only:

```graphql
fragment on Project {
  owner {
    greeting
  }
}
```

Projection retains the passive `owner` object but stops at the resolver-owned `greeting` boundary.
The returned value would become `Project { owner: User {} }`. Resolution could later discover that
`greeting` requires `display`, `first`, and `last`, but the producer projection would already have
discarded the passive inputs.

`successorDemand()` prevents that loss. It walks through `owner`, finds the demanded
`User.greeting` occurrence, closes that successor's input requirements, and roots them at the
containing `User` occurrence:

```graphql
fragment on Project {
  owner {
    greeting
    display
    first
    last
  }
}
```

Projection still stops at the resolver-owned `greeting` and `display` fields, but retains
`first` and `last`:

```text
Project {
  owner: User {
    first: 2
    last: 3
  }
}
```

When resolution enters that `User`, local closure can construct `display` and then `greeting`.
Local closure constructs a producer's input from predecessors on the current object occurrence;
successor demand preserves producer-owned passive data needed by resolvers on descendant
occurrences.

## Type Conditions

Conditions on an occurrence path must remain attached when demand is lifted. An abstract selection
may denote different concrete resolver coordinates whose inputs require different passive fields:

```graphql
extend type Query {
  subject: Subject!
    @resolver(
      result: {
        __typename: "Person"
        first: 7
      }
    )
}

interface Subject {
  summary: Int!
}

type Person implements Subject {
  first: Int!

  summary: Int!
    @resolver(
      of: "first"
      result: "sum(first)"
    )
}

type Organization implements Subject {
  legal: Int!

  summary: Int!
    @resolver(
      of: "legal"
      result: "sum(legal)"
    )
}
```

The client asks for:

```graphql
query {
  subject {
    summary
  }
}
```

The demand collector treats the resolver function as opaque and learns the concrete `Subject` type
only from its result. It therefore lifts both possible successor requirements while preserving
their conditions:

```graphql
fragment on Subject {
  summary

  ... on Person {
    first
  }

  ... on Organization {
    legal
  }
}
```

Projection examines the returned `Person`. The `Person` condition applies, so `first` is retained;
the `Organization` condition does not apply. Lifting preserves possible successor demand, while
the retained type conditions prevent that demand from becoming unconditional.

## Fresh Query OER Resolution Strategies

This checker-free example compares three policies for resolving declared Query fragments. All three preserve a Query OER separate from the operation root, but they choose different boundaries for sharing Query-fragment work.

The schema contains only root fields:

```graphql
extend type Query {
  field0: Int!
  field1: Int!
  field2: Int!
  field3: Int!
}
```

The resolver registry assigns each field an empty object fragment, a constant result, and the following Query fragment. This table is direct registry notation rather than resolver-test DSL syntax because the DSL currently has no Query-fragment declaration.

| Resolver | Query fragment | Result |
| --- | --- | --- |
| `Query.field0` | `field1 field2` | `0` |
| `Query.field1` | `field2 field3` | `1` |
| `Query.field2` | `field3` | `2` |
| `Query.field3` | empty | `3` |

The client selects only the first field:

```graphql
query {
  field0
}
```

### Historical exponential resolution

Under the former policy, every active resolver occurrence with a nonempty declared Query fragment received its own fresh Query OER. Let `Q0` name the operation's Query OER. Resolving `Q0.field0` created `Q1` for that occurrence's `field1 field2` Query fragment. Those sibling selections shared `Q1`, but their resolvers created separate roots for their own Query fragments:

```text
Q0.field0
└── Q1 { field1, field2 }
    ├── Q1.field1
    │   └── Q2 { field2, field3 }
    │       ├── Q2.field2
    │       │   └── Q4 { field3 }
    │       └── Q2.field3
    └── Q1.field2
        └── Q3 { field3 }
```

`Q1.field2` and `Q2.field2` have the same schema coordinate and arguments, but they belong to different Query OERs. They are distinct resolver occurrences, so each creates another fresh root selecting `field3`. Consequently `field0` and `field1` are each invoked once, `field2` twice, and `field3` three times: seven resolver invocations for four fields.

Extending the same pattern with `field4` makes `field2` select `field3 field4`, `field3` select `field4`, and `field4` select nothing. The per-field invocation counts become `1, 1, 2, 3, 5`. Each additional level receives the sum of the preceding two occurrence counts, producing Fibonacci growth; the total reaches 88 invocations at depth 8 and 832,039 at depth 27.

This graph is finite and acyclic: every edge points from `fieldN` to a field with a larger index. Cycle detection therefore has nothing to reject. The resource problem comes from repeating overlapping acyclic work across independent fresh roots, not from a deadlock or an unrecognized logical cycle. Qplan no longer implements this policy; it is retained here as the motivating comparison.

### Linear resolution

A less duplicative policy could associate at most one child Query OER with each parent OER. Every resolver occurrence on the parent would contribute its grounded Query-fragment demand to that shared child, while retaining its own projection from the completed child. The four-field example would become:

```text
Q0 { field0 }
└── Q1 { field1, field2 }
    └── Q2 { field2, field3 }
        └── Q3 { field3 }
```

`Q0.field0` contributes `field1 field2` to `Q1`. On `Q1`, the orchestration task unions `Q1.field1`'s `field2 field3` demand with `Q1.field2`'s `field3` demand, producing one child `Q2` with `field2 field3`. The same rule gives `Q2` one child `Q3` containing `field3`. Each resolver still materializes only its declared projection from its parent OER's shared child.

This policy invokes `field0` and `field1` once and invokes `field2` and `field3` twice, for six invocations. The number of Query OERs grows linearly with dependency depth instead of branching exponentially, but resolution work remains superlinear. In the same pattern extended through depth `D`, the Query OER at generation `k` contains field indices `k` through `min(2k, D)`: the first generations contain one, two, three, and then four exact field keys. The width continues growing until the midpoint and then shrinks, so summing the fields resolved across all generations is quadratic in `D`. Depth 8 requires 25 resolver invocations and depth 27 requires 210, even though only one Query OER exists at each generation.

“Linear” therefore describes root growth, not a linear bound on resolution. Sharing removes the exponential multiplicity caused by sibling roots repeating equal exact keys, but it does not coalesce the same key across different generations. More generally, the one-child policy bounds how many roots occur at a generation; it does not bound how many instantiated selection occurrences recursive or wider resolver fragments contribute at that generation. Grounding maps each such occurrence to at most one exact field-and-arguments key. Different arguments do not generate more occurrences; they only prevent independently generated occurrences from coalescing to one key. Superlinear work relative to the original client selection set must therefore come from repeated fragment instantiation, runtime object or list fanout, or another source of additional occurrences—not from argument grounding itself.

This unimplemented intermediate policy would add an OER-to-child-Query-OER association and make each orchestration task collect and ground every active resolver's Query fragment before dispatching the shared child. It would also need owner-specific input projections, compatible merging by exact field key and arguments, and shared failure and cancellation rules. This is moderate orchestration complexity: it preserves nested Query levels and therefore avoids a transitive same-OER fixed point, but it changes sibling resolver occurrences from isolated Query executions to shared production.

### Singular resolution

Qplan implements a stronger policy that replaces the recursive OER-to-child rule with a Query scope. Source OER `Q0` owns one associated Query OER `Q1`; resolver occurrences executed inside `Q1` contribute further Query-fragment demand back into `Q1` rather than creating children. For `Q0.field0`, `Q1` initially receives `field1 field2`; closing the Query fragments of those fields adds `field3` without creating another root:

```text
Q0.field0
└── Q1 { field1, field2, field3 }
```

Inside `Q1`, `field3` resolves first, `field2` materializes its `field3` input and resolves next, and `field1` materializes `field2 field3` and resolves last. `Q0.field0` then materializes only its declared `field1 field2` projection. Each coordinate executes once, so the complete operation performs four resolver invocations.

For this exact two-successor pattern, the three policies produce sharply different total resolver work:

| Policy | Depth 3 | Depth 8 | Depth 27 |
| --- | ---: | ---: | ---: |
| Exponential, one root per resolver occurrence | 7 | 88 | 832,039 |
| Linear roots, one child per parent OER | 6 | 25 | 210 |
| Singular, one transitively closed Query scope | 4 | 9 | 28 |

The singular policy makes this example linear because the fixed point contains one exact key for each schema field. For a fixed set of instantiated selection occurrences on one OER, the number of exact grounded keys cannot exceed the number of occurrences, so argument grounding cannot make closure superlinear in that set's size. Work can still be superlinear relative to the original syntactic client selection set when resolver-fragment expansion or runtime object fanout creates additional occurrences. The singular policy removes repetition caused only by Query-root identity; it cannot avoid work represented by those genuinely distinct occurrences.

The implementation uses a Query-scope fixed point analogous to construction-demand closure. Newly discovered exact resolver keys contribute their Query fragments back into the same OER; preparation installs every required cell and binding before producers run; dependency ordering and cycle edges operate within that shared scope; and each resolver retains an occurrence-local projection despite shared cells. Same-key recursive Query dependencies become visible cycles, equal exact work coalesces, and variables, inclusion conditions, aliases, failures, cancellation, and Resolution's value-derived bindings retain their occurrence-local semantics.

## Why The Depth-First Resolvers Do Not Support `@parent`

`@parent` turns the ordinary result tree into a graph and can require resolution to leave an occurrence, revisit an ancestor, and then re-enter the same still-open descendant before the original work can finish. Consider this complete resolver world:

```graphql
directive @parent on FIELD_DEFINITION

extend type Query {
  root: Root!
    @resolver(result: {})
}

type Root {
  child: Child!
    @resolver(result: {})

  ancestorValue: Int!
    @resolver(
      of: "child { parent { child { marker } } }"
      result: "value(child.parent.child.marker)"
    )
}

type Child {
  parent: Root! @parent

  grandchild: Grandchild!
    @resolver(result: {})

  marker: Int!
    @resolver(result: 42)
}

type Grandchild {
  parent: Child! @parent

  greatGrandchild: GreatGrandchild!
    @resolver(result: {})
}

type GreatGrandchild {
  parent: Grandchild! @parent

  result: Int!
    @resolver(
      of: "parent { parent { parent { ancestorValue } } }"
      result: "value(parent.parent.parent.ancestorValue)"
    )
}
```

The client asks for:

```graphql
query {
  root {
    child {
      grandchild {
        greatGrandchild {
          result
        }
      }
    }
  }
}
```

`GreatGrandchild.result` walks three parent edges to demand `Root.ancestorValue`. That resolver's input descends through `Root.child`, follows the child's parent back to the same `Root`, then re-enters the same `Child` occurrence to demand `marker`. The required execution order is therefore:

```text
Child.marker -> Root.ancestorValue -> GreatGrandchild.result
```

This order crosses occurrence boundaries in both directions. An order computed by `SiblingDependencyLogic` for sibling keys on one OER cannot express it, and a traversal that reserves an occurrence's work before recursively deepening it cannot safely re-enter that still-open occurrence. Supporting the case requires occurrence-aware suspension or orchestration across parent and child edges, not merely a different local sibling order.

Resolver01-03 and Resolver06-08 intentionally retain their simple recursive and explicit-task depth-first structures, so their input domain requires a schema with no `@parent` fields. Resolver21 has the same schema precondition. This world is outside those versions' input domains; they do not define runtime rejection behavior for it. Resolver22/23 use structured suspension with exact promises, and Resolution performs parent-aware construction-demand and successor-demand fixed-point computations, so those resolvers support this world. A more elaborate graph-aware depth-first engine could be built, but adding its re-entry machinery to these reference algorithms would defeat their purpose as small stepping stones toward correctness proofs.
