# 6. Execution

{++

This copy of the chapter specializes GraphQL execution for Viaduct. Its field-resolution phase has already produced a fully resolved Engine Result Tree, as defined in the [Chapter Appendix](#sec-Chapter-Appendix), including Boolean field- and type-check decisions. The execution algorithms read that tree synchronously to complete a GraphQL response; they do not invoke tenant field resolvers, run access checkers, or wait for additional field-resolution work. They do enforce the access decisions already recorded in the tree.

The specialization makes the following principal changes:

- {ResolveFieldValue()} is no longer an intentionally abstract call into an implementation-provided resolver. Its {objectValue} is specifically an `ObjectEngineResult`; the concrete type, field name, and coerced arguments form a `Key` that selects an existing `EngineResultCell`. The special `__typename` field is obtained directly from the object's retained concrete type.
- `ObjectEngineResult` values use `Key` values rather than GraphQL response keys. A `Key` identifies a field by a concrete Object type, the name of a field on that type, and the field's coerced argument values. A response key still determines the name written to the response map, but an alias never participates in selecting the backing `EngineResultCell`.
- {ReadResultCellValue()} is a new common operation for reading both object-field cells and list-element cells. It gives a raw field-resolution error precedence, then grants access only when the cell's Boolean field-check value and any reached `ObjectEngineResult`'s Boolean type-check value are both {true}. Either value defaults to {true} when no corresponding check applies; either value being {false} raises an _execution error_.
- {CompleteValue()} retains the recursive structure and error propagation of the published algorithm. Its inserted steps make the backing representation concrete by requiring `ObjectEngineResult` and `ListEngineResult` values, reading list-element cells, checking retained type witnesses, and coercing the specified leaf carriers. Apart from the access checks applied while cells are read, these are small representational refinements rather than a structural overhaul of value completion.
- The [Chapter Appendix](#sec-Chapter-Appendix) defines the Engine Result Tree, its object and list forms, result cells, access decisions, and the synchronous response-completion boundary assumed by the chapter.

Text marked by a green vertical rule is a Viaduct specialization of the September 2025 GraphQL specification. Inline text with a green background is a smaller specialization within otherwise retained upstream text. Unmarked text retains the published specification's behavior.

++}

A GraphQL service generates a response from a request via execution.

:: A _request_ for execution consists of a few pieces of information:

- {schema}: The schema to use, typically solely provided by the GraphQL service.
- {document}: A {Document} which must contain GraphQL {OperationDefinition} and
  may contain {FragmentDefinition}.
- {operationName} (optional): The name of the Operation in the Document to
  execute.
- {variableValues} (optional): Values for any Variables defined by the
  Operation.
- {++{initialValue}: The root `ObjectEngineResult` produced for the operation.
  Its concrete type must be the operation's root Object type.++}
- {extensions} (optional): A map reserved for implementation-specific additional
  information.

Given this information, the result of {ExecuteRequest(schema, document,
operationName, variableValues, initialValue)} produces the response, to be
formatted according to the [Response](https://spec.graphql.org/September2025/#sec-Response) section.

Implementations should not add additional properties to a _request_, which may
conflict with future editions of the GraphQL specification. Instead,
{extensions} provides a reserved location for implementation-specific additional
information. If present, {extensions} must be a map, but there are no additional
restrictions on its contents. To avoid conflicts, keys should use unique
prefixes.

Note: GraphQL requests do not require any specific serialization format or
transport mechanism. Message serialization and transport mechanisms should be
chosen by the implementing service.

Note: Descriptions and comments in executable documents (operation definitions,
fragment definitions, and variable definitions) MUST be ignored during execution
and have no effect on the observable execution, validation, or response of a
GraphQL document. Descriptions and comments on executable documents MAY be used
for non-observable purposes, such as logging and other developer tools.

## Executing Requests

To execute a request, the executor must have a parsed {Document} and a selected
operation name to run if the document defines multiple operations, otherwise the
document is expected to only contain a single operation. The result of the
request is determined by the result of executing this operation according to the
"Executing Operations” section below.

ExecuteRequest(schema, document, operationName, variableValues, initialValue):

- Let {operation} be the result of {GetOperation(document, operationName)}.
- Let {coercedVariableValues} be the result of {CoerceVariableValues(schema,
  operation, variableValues)}.
- If {operation} is a query operation:
  - Return {ExecuteQuery(operation, schema, coercedVariableValues,
    initialValue)}.
- Otherwise if {operation} is a mutation operation:
  - Return {ExecuteMutation(operation, schema, coercedVariableValues,
    initialValue)}.
- Otherwise if {operation} is a subscription operation:
  - Return {Subscribe(operation, schema, coercedVariableValues, initialValue)}.

GetOperation(document, operationName):

- If {operationName} is {null}:
  - If {document} contains exactly one operation.
    - Return the Operation contained in the {document}.
  - Otherwise raise a _request error_ requiring {operationName}.
- Otherwise:
  - Let {operation} be the Operation named {operationName} in {document}.
  - If {operation} was not found, raise a _request error_.
  - Return {operation}.

### Validating Requests

As explained in the [Validation](https://spec.graphql.org/September2025/#sec-Validation) section, only requests which pass all validation
rules should be executed. If validation errors are known, they should be
reported in the list of "errors" in the response and the request must fail
without execution.

Typically validation is performed in the context of a request immediately before
execution, however a GraphQL service may execute a request without immediately
validating it if that exact same request is known to have been validated before.
A GraphQL service should only execute requests which _at some point_ were known
to be free of any validation errors, and have since not changed.

For example: the request may be validated during development, provided it does
not later change, or a service may validate a request once and memoize the
result to avoid validating the same request again in the future.

### Coercing Variable Values

If the operation has defined any variables, then the values for those variables
need to be coerced using the input coercion rules of the variable's declared
type. If a _request error_ is encountered during input coercion of variable
values, then the operation fails without execution.

CoerceVariableValues(schema, operation, variableValues):

- Let {coercedValues} be an empty unordered Map.
- Let {variablesDefinition} be the variables defined by {operation}.
- For each {variableDefinition} in {variablesDefinition}:
  - Let {variableName} be the name of {variableDefinition}.
  - Let {variableType} be the expected type of {variableDefinition}.
  - Assert: {IsInputType(variableType)} must be {true}.
  - Let {defaultValue} be the default value for {variableDefinition}.
  - Let {hasValue} be {true} if {variableValues} provides a value for the name
    {variableName}.
  - Let {value} be the value provided in {variableValues} for the name
    {variableName}.
  - If {hasValue} is not {true} and {defaultValue} exists (including {null}):
    - Let {coercedDefaultValue} be the result of coercing {defaultValue}
      according to the input coercion rules of {variableType}.
    - Add an entry to {coercedValues} named {variableName} with the value
      {coercedDefaultValue}.
  - Otherwise if {variableType} is a Non-Nullable type, and either {hasValue} is
    not {true} or {value} is {null}, raise a _request error_.
  - Otherwise if {hasValue} is {true}:
    - If {value} is {null}:
      - Add an entry to {coercedValues} named {variableName} with the value
        {null}.
    - Otherwise:
      - If {value} cannot be coerced according to the input coercion rules of
        {variableType}, raise a _request error_.
      - Let {coercedValue} be the result of coercing {value} according to the
        input coercion rules of {variableType}.
      - Add an entry to {coercedValues} named {variableName} with the value
        {coercedValue}.
- Return {coercedValues}.

Note: This algorithm is very similar to {CoerceArgumentValues()}.

## Executing Operations

The type system, as described in the [Type System](https://spec.graphql.org/September2025/#sec-Type-System) section of the spec, must
provide a query root operation type. If mutations or subscriptions are
supported, it must also provide a mutation or subscription root operation type,
respectively.

### Query

If the operation is a query, the result of the operation is the result of
executing the operation’s _root selection set_ with the query root operation
type.

{++

For engine-result-backed execution, {initialValue} must be the root
`ObjectEngineResult` produced for the query operation.

++}

ExecuteQuery(query, schema, variableValues, initialValue):

- Let {queryType} be the root Query type in {schema}.
- Assert: {queryType} is an Object type.
- Let {rootSelectionSet} be the _root selection set_ in {query}.
- Return {ExecuteRootSelectionSet(variableValues, initialValue, queryType,
  rootSelectionSet, "normal")}.

### Mutation

If the operation is a mutation, the result of the operation is the result of
executing the operation’s _root selection set_ on the mutation root object type.
This selection set should be executed serially.

It is expected that the top level fields in a mutation operation perform
side-effects on the underlying data system. Serial execution of the provided
mutations ensures against race conditions during these side-effects.

{++

In engine-result-backed execution, the producer of the mutation root `ObjectEngineResult` must preserve this serial order while populating top-level field cells. Reading cells during response completion cannot impose side-effect ordering retroactively.

++}

ExecuteMutation(mutation, schema, variableValues, initialValue):

- Let {mutationType} be the root Mutation type in {schema}.
- Assert: {mutationType} is an Object type.
- Let {rootSelectionSet} be the _root selection set_ in {mutation}.
- Return {ExecuteRootSelectionSet(variableValues, initialValue, mutationType,
  rootSelectionSet, "serial")}.

### Subscription

If the operation is a subscription, the result is an _event stream_ called the
_response stream_ where each event in the event stream is the result of
executing the operation for each new event on an underlying _source stream_.

Executing a subscription operation creates a persistent function on the service
that maps an underlying _source stream_ to a returned _response stream_.

Subscribe(subscription, schema, variableValues, initialValue):

- Let {sourceStream} be the result of running
  {CreateSourceEventStream(subscription, schema, variableValues, initialValue)}.
- Let {responseStream} be the result of running
  {MapSourceToResponseEvent(sourceStream, subscription, schema,
  variableValues)}.
- Return {responseStream}.

Note: In a large-scale subscription system, the {Subscribe()} and
{ExecuteSubscriptionEvent()} algorithms may be run on separate services to
maintain predictable scaling properties. See the section below on Supporting
Subscriptions at Scale.

As an example, consider a chat application. To subscribe to new messages posted
to the chat room, the client sends a request like so:

```graphql example
subscription NewMessages {
  newMessage(roomId: 123) {
    sender
    text
  }
}
```

While the client is subscribed, whenever new messages are posted to chat room
with ID "123", the selection for "sender" and "text" will be evaluated and
published to the client, for example:

```json example
{
  "data": {
    "newMessage": {
      "sender": "Hagrid",
      "text": "You're a wizard!"
    }
  }
}
```

The "new message posted to chat room" could use a "Pub-Sub" system where the
chat room ID is the "topic" and each "publish" contains the sender and text.

**Event Streams**

:: An _event stream_ represents a sequence of events: discrete emitted values
over time which can be observed. As an example, a "Pub-Sub" system may produce
an _event stream_ when "subscribing to a topic", with an value emitted for each
"publish" to that topic.

An _event stream_ may complete at any point, often because no further events
will occur. An _event stream_ may emit an infinite sequence of values, in which
it may never complete. If an _event stream_ encounters an error, it must
complete with that error.

An observer may at any point decide to stop observing an _event stream_ by
cancelling it. When an _event stream_ is cancelled, it must complete.

Internal user code also may cancel an _event stream_ for any reason, which would
be observed as that _event stream_ completing.

**Supporting Subscriptions at Scale**

Query and mutation operations are stateless, allowing scaling via cloning of
GraphQL service instances. Subscriptions, by contrast, are stateful and require
maintaining the GraphQL document, variables, and other context over the lifetime
of the subscription.

Consider the behavior of your system when state is lost due to the failure of a
single machine in a service. Durability and availability may be improved by
having separate dedicated services for managing subscription state and client
connectivity.

**Delivery Agnostic**

GraphQL subscriptions do not require any specific serialization format or
transport mechanism. GraphQL specifies algorithms for the creation of the
response stream, the content of each payload on that stream, and the closing of
that stream. There are intentionally no specifications for message
acknowledgement, buffering, resend requests, or any other quality of service
(QoS) details. Message serialization, transport mechanisms, and quality of
service details should be chosen by the implementing service.

#### Source Stream

:: A _source stream_ is an _event stream_ representing a sequence of root
values, each of which will trigger a GraphQL execution. Like field value
resolution, the logic to create a _source stream_ is application-specific.

CreateSourceEventStream(subscription, schema, variableValues, initialValue):

- Let {subscriptionType} be the root Subscription type in {schema}.
- Assert: {subscriptionType} is an Object type.
- Let {selectionSet} be the top level selection set in {subscription}.
- Let {collectedFieldsMap} be the result of {CollectFields(subscriptionType,
  selectionSet, variableValues)}.
- If {collectedFieldsMap} does not have exactly one entry, raise a _request
  error_.
- Let {fields} be the value of the first entry in {collectedFieldsMap}.
- Let {fieldName} be the name of the first entry in {fields}. Note: This value
  is unaffected if an alias is used.
- Let {field} be the first entry in {fields}.
- Let {argumentValues} be the result of {CoerceArgumentValues(subscriptionType,
  field, variableValues)}.
- Let {sourceStream} be the result of running
  {ResolveFieldEventStream(subscriptionType, initialValue, fieldName,
  argumentValues)}.
- Return {sourceStream}.

ResolveFieldEventStream(subscriptionType, rootValue, fieldName, argumentValues):

- Let {resolver} be the internal function provided by {subscriptionType} for
  determining the resolved _event stream_ of a subscription field named
  {fieldName}.
- Return the result of calling {resolver}, providing {rootValue} and
  {argumentValues}.

Note: This {ResolveFieldEventStream()} algorithm is intentionally similar to
{ResolveFieldValue()} to enable consistency when defining resolvers on any
operation type.

#### Response Stream

Each event from the underlying _source stream_ triggers execution of the
subscription _selection set_ using that event's value as the {initialValue}.

{++

For engine-result-backed execution, each such event value must be a root
`ObjectEngineResult` of the subscription type.

++}

MapSourceToResponseEvent(sourceStream, subscription, schema, variableValues):

- Let {responseStream} be a new _event stream_.
- When {sourceStream} emits {sourceValue}:
  - Let {executionResult} be the result of running
    {ExecuteSubscriptionEvent(subscription, schema, variableValues,
    sourceValue)}.
  - If internal {error} was raised:
    - Cancel {sourceStream}.
    - Complete {responseStream} with {error}.
  - Otherwise emit {executionResult} on {responseStream}.
- When {sourceStream} completes normally:
  - Complete {responseStream} normally.
- When {sourceStream} completes with {error}:
  - Complete {responseStream} with {error}.
- When {responseStream} is cancelled:
  - Cancel {sourceStream}.
  - Complete {responseStream} normally.
- Return {responseStream}.

Note: Since {ExecuteSubscriptionEvent()} handles all _execution error_, and
_request error_ only occur during {CreateSourceEventStream()}, the only
remaining error condition handled from {ExecuteSubscriptionEvent()} are internal
exceptional errors not described by this specification.

ExecuteSubscriptionEvent(subscription, schema, variableValues, initialValue):

- Let {subscriptionType} be the root Subscription type in {schema}.
- Assert: {subscriptionType} is an Object type.
- Let {rootSelectionSet} be the _root selection set_ in {subscription}.
- Return {ExecuteRootSelectionSet(variableValues, initialValue,
  subscriptionType, rootSelectionSet, "normal")}.

Note: The {ExecuteSubscriptionEvent()} algorithm is intentionally similar to
{ExecuteQuery()} since this is how each event result is produced.

#### Unsubscribe

Unsubscribe cancels the _response stream_ when a client no longer wishes to
receive payloads for a subscription. This in turn also cancels the Source
Stream, which is a good opportunity to clean up any other resources used by the
subscription.

Unsubscribe(responseStream):

- Cancel {responseStream}.

## Executing Selection Sets

Executing a GraphQL operation recursively collects and executes every selected
field in the operation. First all initially selected fields from the operation's
top most _root selection set_ are collected, then each executed. As each field
completes, all its subfields are collected, then each executed. This process
continues until there are no more subfields to collect and execute.

### Executing the Root Selection Set

:: A _root selection set_ is the top level _selection set_ provided by a GraphQL
operation. A root selection set always selects from a _root operation type_.

To execute the root selection set, the initial value being evaluated and the
root type must be known, as well as whether the fields must be executed in a
series, or normally by executing all fields in parallel (see
[Normal and Serial Execution](#sec-Normal-and-Serial-Execution)).

Executing the root selection set works similarly for queries (parallel),
mutations (serial), and subscriptions (where it is executed for each event in
the underlying Source Stream).

First, the _selection set_ is collected into a _collected fields map_ which is
then executed, returning the resulting {data} and {errors}.

ExecuteRootSelectionSet(variableValues, initialValue, objectType, selectionSet,
executionMode):

- {++Assert: {initialValue} is an `ObjectEngineResult` whose concrete type is
  {objectType}.++}
- Let {collectedFieldsMap} be the result of {CollectFields(objectType,
  selectionSet, variableValues)}.
- Let {data} be the result of running
  {ExecuteCollectedFields(collectedFieldsMap, objectType, initialValue,
  variableValues)} _serially_ if {executionMode} is {"serial"}, otherwise
  _normally_ (allowing parallelization)).
- Let {errors} be the list of all _execution error_ raised while executing the
  selection set.
- Return an unordered map containing {data} and {errors}.

### Field Collection

Before execution, each _selection set_ is converted to a _collected fields map_
by collecting all fields with the same response name, including those in
referenced fragments, into an individual _field set_. This ensures that multiple
references to fields with the same response name will only be executed once.

:: A _collected fields map_ is an ordered map where each entry is a _response
name_ and its associated _field set_. A _collected fields map_ may be produced
from a selection set via {CollectFields()} or from the selection sets of all
entries of a _field set_ via {CollectSubfields()}.

:: A _field set_ is an ordered set of selected fields that share the same
_response name_ (the field alias if defined, otherwise the field's name).
Validation ensures each field in the set has the same name and arguments,
however each may have different subfields (see:
[Field Selection Merging](https://spec.graphql.org/September2025/#sec-Field-Selection-Merging)).

Note: The order of field selections in both a _collected fields map_ and a
_field set_ are significant, hence the algorithms in this specification model
them as an ordered map and ordered set.

As an example, collecting the fields of this query's selection set would result
in a collected fields map with two entries, `"a"` and `"b"`, with two instances
of the field `a` and one of field `b`:

```graphql example
{
  a {
    subfield1
  }
  ...ExampleFragment
}

fragment ExampleFragment on Query {
  a {
    subfield2
  }
  b
}
```

The depth-first-search order of each _field set_ produced by {CollectFields()}
is maintained through execution, ensuring that fields appear in the executed
response in a stable and predictable order.

CollectFields(objectType, selectionSet, variableValues, visitedFragments):

- If {visitedFragments} is not provided, initialize it to the empty set.
- Initialize {collectedFieldsMap} to an empty ordered map of ordered sets.
- For each {selection} in {selectionSet}:
  - If {selection} provides the directive `@skip`, let {skipDirective} be that
    directive.
    - If {skipDirective}'s {if} argument is {true} or is a variable in
      {variableValues} with the value {true}, continue with the next {selection}
      in {selectionSet}.
  - If {selection} provides the directive `@include`, let {includeDirective} be
    that directive.
    - If {includeDirective}'s {if} argument is not {true} and is not a variable
      in {variableValues} with the value {true}, continue with the next
      {selection} in {selectionSet}.
  - If {selection} is a {Field}:
    - Let {responseName} be the _response name_ of {selection} (the alias if
      defined, otherwise the field name).
    - Let {fieldsForResponseName} be the _field set_ value in
      {collectedFieldsMap} for the key {responseName}; otherwise create the
      entry with an empty ordered set.
    - Add {selection} to the {fieldsForResponseName}.
  - If {selection} is a {FragmentSpread}:
    - Let {fragmentSpreadName} be the name of {selection}.
    - If {fragmentSpreadName} is in {visitedFragments}, continue with the next
      {selection} in {selectionSet}.
    - Add {fragmentSpreadName} to {visitedFragments}.
    - Let {fragment} be the Fragment in the current Document whose name is
      {fragmentSpreadName}.
    - If no such {fragment} exists, continue with the next {selection} in
      {selectionSet}.
    - Let {fragmentType} be the type condition on {fragment}.
    - If {DoesFragmentTypeApply(objectType, fragmentType)} is {false}, continue
      with the next {selection} in {selectionSet}.
    - Let {fragmentSelectionSet} be the top-level selection set of {fragment}.
    - Let {fragmentCollectedFieldsMap} be the result of calling
      {CollectFields(objectType, fragmentSelectionSet, variableValues,
      visitedFragments)}.
    - For each {responseName} and {fragmentFields} in
      {fragmentCollectedFieldsMap}:
      - Let {fieldsForResponseName} be the _field set_ value in
        {collectedFieldsMap} for the key {responseName}; otherwise create the
        entry with an empty ordered set.
      - Add each item from {fragmentFields} to {fieldsForResponseName}.
  - If {selection} is an {InlineFragment}:
    - Let {fragmentType} be the type condition on {selection}.
    - If {fragmentType} is not {null} and {DoesFragmentTypeApply(objectType,
      fragmentType)} is {false}, continue with the next {selection} in
      {selectionSet}.
    - Let {fragmentSelectionSet} be the top-level selection set of {selection}.
    - Let {fragmentCollectedFieldsMap} be the result of calling
      {CollectFields(objectType, fragmentSelectionSet, variableValues,
      visitedFragments)}.
    - For each {responseName} and {fragmentFields} in
      {fragmentCollectedFieldsMap}:
      - Let {fieldsForResponseName} be the _field set_ value in
        {collectedFieldsMap} for the key {responseName}; otherwise create the
        entry with an empty ordered set.
      - Append each item from {fragmentFields} to {fieldsForResponseName}.
- Return {collectedFieldsMap}.

DoesFragmentTypeApply(objectType, fragmentType):

- If {fragmentType} is an Object Type:
  - If {objectType} and {fragmentType} are the same type, return {true},
    otherwise return {false}.
- If {fragmentType} is an Interface Type:
  - If {objectType} is an implementation of {fragmentType}, return {true},
    otherwise return {false}.
- If {fragmentType} is a Union:
  - If {objectType} is a possible type of {fragmentType}, return {true},
    otherwise return {false}.

Note: The steps in {CollectFields()} evaluating the `@skip` and `@include`
directives may be applied in either order since they apply commutatively.

**Merging Selection Sets**

In order to execute the sub-selections of an object typed field, all _selection
sets_ of each field with the same response name in the parent _field set_ are
merged together into a single _collected fields map_ representing the subfields
to be executed next.

An example operation illustrating parallel fields with the same name with
sub-selections.

Continuing the example above,

```graphql example
{
  a {
    subfield1
  }
  ...ExampleFragment
}

fragment ExampleFragment on Query {
  a {
    subfield2
  }
  b
}
```

After resolving the value for field `"a"`, the following multiple selection sets
are collected and merged together so `"subfield1"` and `"subfield2"` are
resolved in the same phase with the same value.

CollectSubfields(objectType, fields, variableValues):

- Let {collectedFieldsMap} be an empty ordered map of ordered sets.
- For each {field} in {fields}:
  - Let {fieldSelectionSet} be the selection set of {field}.
  - If {fieldSelectionSet} is null or empty, continue to the next field.
  - Let {fieldCollectedFieldsMap} be the result of {CollectFields(objectType,
    fieldSelectionSet, variableValues)}.
  - For each {responseName} and {subfields} in {fieldCollectedFieldsMap}:
    - Let {fieldsForResponseName} be the _field set_ value in
      {collectedFieldsMap} for the key {responseName}; otherwise create the
      entry with an empty ordered set.
    - Add each fields from {subfields} to {fieldsForResponseName}.
- Return {collectedFieldsMap}.

Note: All the {fields} passed to {CollectSubfields()} share the same _response
name_.

### Executing Collected Fields

To execute a _collected fields map_, the object type being evaluated and the
runtime value need to be known, as well as the runtime values for any variables.

Execution will recursively resolve and complete the value of every entry in the
collected fields map, producing an entry in the result map with the same
_response name_ key.

ExecuteCollectedFields(collectedFieldsMap, objectType, objectValue,
variableValues):

- Initialize {resultMap} to an empty ordered map.
- For each {responseName} and {fields} in {collectedFieldsMap}:
  - Let {fieldName} be the name of the first entry in {fields}. Note: This value
    is unaffected if an alias is used.
  - Let {fieldType} be the return type defined for the field {fieldName} of
    {objectType}.
  - If {fieldType} is defined:
    - Let {responseValue} be {ExecuteField(objectType, objectValue, fieldType,
      fields, variableValues)}.
    - Set {responseValue} as the value for {responseName} in {resultMap}.
- Return {resultMap}.

Note: {resultMap} is ordered by which fields appear first in the operation. This
is explained in greater detail in the [Field Collection](#sec-Field-Collection)
section.

**Errors and Non-Null Types**

<a name="sec-Executing-Selection-Sets.Errors-and-Non-Null-Fields">
  <!-- Legacy link, this section was previously titled "Errors and Non-Null Fields" -->
</a>

If during {ExecuteCollectedFields()} a _response position_ with a non-null type
raises an _execution error_ then that error must propagate to the parent
response position (the entire selection set in the case of a field, or the
entire list in the case of a list position), either resolving to {null} if
allowed or being further propagated to a parent response position.

If this occurs, any sibling response positions which have not yet executed or
have not yet yielded a value may be cancelled to avoid unnecessary work.

Note: See [Handling Execution Errors](#sec-Handling-Execution-Errors) for more
about this behavior.

### Normal and Serial Execution

Normally the executor can execute the entries in a _collected fields map_ in
whatever order it chooses (normally in parallel). Because the resolution of
fields other than top-level mutation fields must always be side effect-free and
idempotent, the execution order must not affect the result, and hence the
service has the freedom to execute the field entries in whatever order it deems
optimal.

For example, given the following collected fields map to be executed normally:

```graphql example
{
  birthday {
    month
  }
  address {
    street
  }
}
```

A valid GraphQL executor can resolve the four fields in whatever order it chose
(however of course `birthday` must be resolved before `month`, and `address`
before `street`).

When executing a mutation, the selections in the top most selection set will be
executed in serial order, starting with the first appearing field textually.

When executing a collected fields map serially, the executor must consider each
entry from the collected fields map in the order provided in the collected
fields map. It must determine the corresponding entry in the result map for each
item to completion before it continues on to the next entry in the collected
fields map:

For example, given the following mutation operation, the root _selection set_
must be executed serially:

```graphql example
mutation ChangeBirthdayAndAddress($newBirthday: String!, $newAddress: String!) {
  changeBirthday(birthday: $newBirthday) {
    month
  }
  changeAddress(address: $newAddress) {
    street
  }
}
```

Therefore the executor must, in serial:

- Run {ExecuteField()} for `changeBirthday`, which during {CompleteValue()} will
  execute the `{ month }` sub-selection set normally.
- Run {ExecuteField()} for `changeAddress`, which during {CompleteValue()} will
  execute the `{ street }` sub-selection set normally.

As an illustrative example, let's assume we have a mutation field
`changeTheNumber` that returns an object containing one field, `theNumber`. If
we execute the following _selection set_ serially:

```graphql example
# Note: This is a selection set, not a full document using the query shorthand.
{
  first: changeTheNumber(newNumber: 1) {
    theNumber
  }
  second: changeTheNumber(newNumber: 3) {
    theNumber
  }
  third: changeTheNumber(newNumber: 2) {
    theNumber
  }
}
```

The executor will execute the following serially:

- Resolve the `changeTheNumber(newNumber: 1)` field
- Execute the `{ theNumber }` sub-selection set of `first` normally
- Resolve the `changeTheNumber(newNumber: 3)` field
- Execute the `{ theNumber }` sub-selection set of `second` normally
- Resolve the `changeTheNumber(newNumber: 2)` field
- Execute the `{ theNumber }` sub-selection set of `third` normally

A correct executor must generate the following result for that _selection set_:

```json example
{
  "first": {
    "theNumber": 1
  },
  "second": {
    "theNumber": 3
  },
  "third": {
    "theNumber": 2
  }
}
```

## Executing Fields

Each entry in a result map is the result of executing a field on an object type
selected by the name of that field in a _collected fields map_. Field execution
first coerces any provided argument values, then resolves a value for the field,
and finally completes that value either by recursively executing another
selection set or coercing a scalar value.

ExecuteField(objectType, objectValue, fieldType, fields, variableValues):

- Let {field} be the first entry in {fields}.
- Let {fieldName} be the field name of {field}.
- Let {argumentValues} be the result of {CoerceArgumentValues(objectType, field,
  variableValues)}.
- Let {resolvedValue} be {ResolveFieldValue(objectType, objectValue, fieldName,
  argumentValues)}.
- Return the result of {CompleteValue(fieldType, fields, resolvedValue,
  variableValues)}.

### Coercing Field Arguments

Fields may include arguments which are provided to the underlying runtime in
order to correctly produce a value. These arguments are defined by the field in
the type system to have a specific input type.

At each argument position in an operation may be a literal {Value}, or a
{Variable} to be provided at runtime.

CoerceArgumentValues(objectType, field, variableValues):

- Let {coercedValues} be an empty unordered Map.
- Let {argumentValues} be the argument values provided in {field}.
- Let {fieldName} be the name of {field}.
- Let {argumentDefinitions} be the arguments defined by {objectType} for the
  field named {fieldName}.
- For each {argumentDefinition} in {argumentDefinitions}:
  - Let {argumentName} be the name of {argumentDefinition}.
  - Let {argumentType} be the expected type of {argumentDefinition}.
  - Let {defaultValue} be the default value for {argumentDefinition}.
  - Let {argumentValue} be the value provided in {argumentValues} for the name
    {argumentName}.
  - If {argumentValue} is a {Variable}:
    - Let {variableName} be the name of {argumentValue}.
    - If {variableValues} provides a value for the name {variableName}:
      - Let {hasValue} be {true}.
      - Let {value} be the value provided in {variableValues} for the name
        {variableName}.
  - Otherwise if {argumentValues} provides a value for the name {argumentName}.
    - Let {hasValue} be {true}.
    - Let {value} be {argumentValue}.
  - If {hasValue} is not {true} and {defaultValue} exists (including {null}):
    - Let {coercedDefaultValue} be the result of coercing {defaultValue}
      according to the input coercion rules of {argumentType}.
    - Add an entry to {coercedValues} named {argumentName} with the value
      {coercedDefaultValue}.
  - Otherwise if {argumentType} is a Non-Nullable type, and either {hasValue} is
    not {true} or {value} is {null}, raise an _execution error_.
  - Otherwise if {hasValue} is {true}:
    - If {value} is {null}:
      - Add an entry to {coercedValues} named {argumentName} with the value
        {null}.
    - Otherwise, if {argumentValue} is a {Variable}:
      - Add an entry to {coercedValues} named {argumentName} with the value
        {value}.
    - Otherwise:
      - If {value} cannot be coerced according to the input coercion rules of
        {argumentType}, raise an _execution error_.
      - Let {coercedValue} be the result of coercing {value} according to the
        input coercion rules of {argumentType}.
      - Add an entry to {coercedValues} named {argumentName} with the value
        {coercedValue}.
- Return {coercedValues}.

Any _request error_ raised as a result of input coercion during
{CoerceArgumentValues()} should be treated instead as an _execution error_.

Note: Variable values are not coerced because they are expected to be coerced
before executing the operation in {CoerceVariableValues()}, and valid operations
must only allow usage of variables of appropriate types.

Note: Implementations are encouraged to optimize the coercion of an argument's
default value by doing so only once and caching the resulting coerced value.

### Value Resolution

{++

Field resolution has already populated the Engine Result Tree before these execution algorithms consume it. Every {objectValue} supplied to {ResolveFieldValue()} is therefore an `ObjectEngineResult`, and resolving a field means reading its exact `EngineResultCell`.

ResolveFieldValue(objectType, objectValue, fieldName, argumentValues):

- Assert: {objectValue} is an `ObjectEngineResult` whose concrete type is
  {objectType}.
- If {fieldName} is {"__typename"}, return the name of {objectValue}'s concrete
  type.
- Let {fieldKey} be the `Key` consisting of {objectType}, {fieldName}, and
  {argumentValues}.
- Let {fieldCell} be the exact `EngineResultCell` selected by {fieldKey} in
  {objectValue}.
- Assert: {fieldCell} exists.
- Return {ReadResultCellValue(fieldCell)}.

Note: A field's response alias is not part of {fieldKey}. Aliases determine entries in the response map, while the concrete Object type, field name, and coerced argument values identify the already-produced `EngineResultCell`.

++}

### Value Completion

After resolving the value for a field, it is completed by ensuring it adheres to
the expected return type. If the return type is another Object type, then the
field execution process continues recursively by collecting and executing
subfields.

CompleteValue(fieldType, fields, result, variableValues):

- {++If {result} is an `ErrorEngineResult`, raise an _execution error_ using
  its retained error data.++}
- If the {fieldType} is a Non-Null type:
  - Let {innerType} be the inner type of {fieldType}.
  - Let {completedResult} be the result of calling {CompleteValue(innerType,
    fields, result, variableValues)}.
  - If {completedResult} is {null}, raise an _execution error_.
  - Return {completedResult}.
- If {result} is {null} (or another internal value similar to {null} such as
  {undefined}), return {null}.
- If {fieldType} is a List type:
  - {++If {result} is not a `ListEngineResult`, raise an _execution error_.++}
  - Let {innerType} be the inner type of {fieldType}.
  - {++Assert: The element type retained by {result} is compatible with
    {innerType}.++}
  - {++Let {completedItems} be an ordered list with the same number of positions
    as {result}.++}
  - {++For each {resultItemCell} and its position {index} in {result},
    _normally_ (allowing parallelization):++}
    - {++Let {resultItem} be {ReadResultCellValue(resultItemCell)}.++}
    - {++Let {completedItem} be the result of
      {CompleteValue(innerType, fields, resultItem, variableValues)}.++}
    - {++Set position {index} of {completedItems} to {completedItem}.++}
  - {++Return {completedItems}.++}
- If {fieldType} is a Scalar or Enum type:
  - Return the result of {CoerceResult(fieldType, result)}.
- If {fieldType} is an Object, Interface, or Union type:
  - {++If {result} is not an `ObjectEngineResult`, raise an _execution
    error_.++}
  - If {fieldType} is an Object type.
    - Let {objectType} be {fieldType}.
    - {++Assert: The concrete type retained by {result} is {objectType}.++}
  - Otherwise if {fieldType} is an Interface or Union type.
    - Let {objectType} be {ResolveAbstractType(fieldType, result)}.
  - Let {collectedFieldsMap} be the result of calling
    {CollectSubfields(objectType, fields, variableValues)}.
  - Return the result of evaluating {ExecuteCollectedFields(collectedFieldsMap,
    objectType, result, variableValues)} _normally_ (allowing for
    parallelization).

**Coercing Results**

{++

The Engine Result Tree retains schema-directed representations for every leaf value. Result coercion converts those internal representations to GraphQL response values.

See the Scalars
[Result Coercion and Serialization](https://spec.graphql.org/September2025/#sec-Scalars.Result-Coercion-and-Serialization)
sub-section for more detailed information about how GraphQL's built-in scalars
coerce result values.

CoerceResult(leafType, value):

- Assert: {value} is not {null}.
- If {leafType} is the `Int` scalar:
  - If {value} is not a signed 32-bit integer, raise an _execution error_.
  - Return {value}.
- If {leafType} is the `Float` scalar:
  - If {value} is not a finite double-precision floating-point number, raise an
    _execution error_.
  - Return {value}.
- If {leafType} is the `Boolean` scalar:
  - If {value} is not a Boolean, raise an _execution error_.
  - Return {value}.
- If {leafType} is the `String` scalar:
  - If {value} is not a String, raise an _execution error_.
  - Return {value}.
- If {leafType} is the `ID` scalar:
  - If {value} is not an `EngineIDResult`, raise an _execution error_.
  - Return the String retained by {value}.
- If {leafType} is an Enum type:
  - If {value} is not the canonical enum value owned by {leafType}, raise an
    _execution error_.
  - Return the name of {value}.
- Otherwise raise an _execution error_.

Note: If an `EngineResultCell` contains {null} then it is handled within
{CompleteValue()} before {CoerceResult()} is called. Therefore both the input
and output of {CoerceResult()} must not be {null}.

++}

**Resolving Abstract Types**

{++

When completing a field with an abstract return type, the `ObjectEngineResult` already retains the concrete canonical Object type selected during field resolution. Abstract type resolution reads that type witness; it does not inspect an application object or invoke a type resolver.

ResolveAbstractType(abstractType, objectValue):

- Assert: {objectValue} is an `ObjectEngineResult`.
- Let {objectType} be the concrete Object type retained by {objectValue}.
- Assert: {objectType} is a possible concrete Object type of {abstractType}.
- Return {objectType}.

++}

### Handling Execution Errors

<a name="sec-Handling-Field-Errors">
  <!-- Legacy link, this section was previously titled "Handling Execution Errors" -->
</a>

An _execution error_ is an error raised during field execution, value resolution
or coercion, at a specific _response position_. While these errors must be
reported in the response, they are "handled" by producing partial {"data"} in
the _response_.

Note: This is distinct from a _request error_ which results in a _request error
result_ with no data.

If an execution error is raised while resolving a field (either directly or
nested inside any lists), it is handled as though the _response position_ at
which the error occurred resolved to {null}, and the error must be added to the
{"errors"} list in the _execution result_.

If the result of resolving a _response position_ is {null} (either due to the
result of {ResolveFieldValue()} or because an execution error was raised), and
that position is of a `Non-Null` type, then an execution error is raised at that
position. The error must be added to the {"errors"} list in the _execution
result_.

If a _response position_ resolves to {null} because of an execution error which
has already been added to the {"errors"} list in the _execution result_, the
{"errors"} list must not be further affected. That is, only one error should be
added to the errors list per _response position_.

Since `Non-Null` response positions cannot be {null}, execution errors are
propagated to be handled by the parent _response position_. If the parent
response position may be {null} then it resolves to {null}, otherwise if it is a
`Non-Null` type, the execution error is further propagated to its parent
_response position_.

If a `List` type wraps a `Non-Null` type, and one of the _response position_
elements of that list resolves to {null}, then the entire list _response
position_ must resolve to {null}. If the `List` type is also wrapped in a
`Non-Null`, the execution error continues to propagate upwards.

If every _response position_ from the root of the request to the source of the
execution error has a `Non-Null` type, then the {"data"} entry in the _execution
result_ should be {null}.

## Chapter Appendix

{++

This appendix defines the backing data assumed by this chapter's execution algorithms. Field-resolution planning, resolver invocation, dependency scheduling, and construction of this data finish before response completion and are outside the algorithms defined here.

++}

### Engine Result Tree

{++

`EngineResult` values form a finite Engine Result Tree. A non-null `EngineResult` is exactly one of an `Int`, a finite `Double`, a `Boolean`, a `String`, an `EngineIDResult`, a canonical schema Enum value, an `ObjectEngineResult`, a `ListEngineResult`, or an `ErrorEngineResult`.

The scalar carriers are schema-directed: `String` represents only GraphQL `String`, `EngineIDResult` represents GraphQL `ID`, and a canonical schema Enum value identifies both its enum type and member name. An `Int`, finite `Double`, and `Boolean` represent GraphQL `Int`, `Float`, and `Boolean`, respectively.

An `ErrorEngineResult` retains useful diagnostic information associated with an error at one result position. It is admitted at every output type so a failure can occupy the exact position where field resolution observed it. {CompleteValue()} converts it into an _execution error_; it is not treated as a scalar, object, list, or {null}.

`ObjectEngineResult` and `ListEngineResult` values may contain other result values. Following field and list-element values always descends through finitely many results and cannot form a cycle.

++}

### Object Engine Results

{++

An `ObjectEngineResult` is the representation of one concrete object occurrence in the Engine Result Tree. It retains a canonical concrete Object type, one Boolean type-check decision, and a finite mapping from `Key` values to `EngineResultCell` values. Object occurrences use identity rather than their field contents for equality. The type-check decision is complete before response completion begins and defaults to {true} when no type access check applies.

A `Key` is a triple consisting of a concrete Object type, the name of a field on that type, and the field's coerced argument values. Declared argument defaults have been applied, and the argument values contain no unresolved variables. A `Key` contains neither a response alias nor a response path.

The Object type in every `Key` of an `ObjectEngineResult` is that result's concrete type. The value in its `EngineResultCell` conforms to the keyed field's output type, except that an `ErrorEngineResult` is permitted at every output type. The tree producer must install the exact cell for every field invocation that response completion may read; a missing cell violates the execution boundary rather than denoting GraphQL {null}.

++}

### List Engine Results

{++

A `ListEngineResult` is the representation of one list occurrence in the Engine Result Tree. It contains a fixed ordered sequence of `EngineResultCell` values and retains the element's schema type expression, including its [List and Non-Null wrappers](https://spec.graphql.org/September2025/#sec-Wrapping-Types). The type expression is a witness that every element value conforms to the expected element type.

List positions have stable identity and are addressed by zero-based indices. A list element is read through {ReadResultCellValue()}; it is not an unwrapped application collection value. Nested lists and objects remain `ListEngineResult` and `ObjectEngineResult` values until {CompleteValue()} recursively completes them.

++}

### Result Cells

{++

An `EngineResultCell` represents one object-field or list-element occurrence. At the response-completion boundary it exposes a value slot and a field-check slot.

The value slot contains GraphQL {null} or an `EngineResult`. It is complete before response completion begins and is read synchronously without invoking a tenant resolver.

The field-check slot contains a Boolean and is likewise complete before response completion begins. Tree construction supplies {true} when no field access check applies. A list-element cell also uses {true} because a list element has no independent field check.

++}

### Access Checks

{++

The Boolean in a cell's field-check slot records whether access to that field occurrence is granted. The Boolean on an `ObjectEngineResult` records whether access to that concrete object occurrence is granted. Field and type decisions are resolved independently and combine only when a checked consumer reaches the object through a field or list-element value.

For a field whose value is an `ObjectEngineResult`, enforcement uses the field decision from the containing cell and the type decision from the returned object occurrence. For a list, the field decision belongs to the cell containing the `ListEngineResult`, while each reached object occurrence supplies its own type decision. Distinct ordinary object occurrences are checked independently; a structural reference to an existing OER reuses that occurrence's one type decision.

ReadResultCellValue(cell):

- Let {result} be the value in {cell}'s value slot.
- If {result} is an `ErrorEngineResult`, return {result} without inspecting the access decisions.
- Let {fieldCheck} be the Boolean in {cell}'s field-check slot.
- If {result} is an `ObjectEngineResult`, let {typeCheck} be its Boolean type-check decision; otherwise let {typeCheck} be {true}.
- If either {fieldCheck} or {typeCheck} is {false}, raise an _execution error_ indicating that access to this response position was denied.
- Return {result}.

Returning an `ErrorEngineResult` before examining access preserves the field-resolution error when resolution and access both failed. Otherwise a value is available to response completion only when both access decisions are {true}. Type access is therefore enforced before {ResolveAbstractType()} projects the concrete type, and abstract-type resolution itself remains a pure read of the `ObjectEngineResult`.

++}

### Execution Boundary

{++

The {initialValue} supplied to root selection-set execution is the root `ObjectEngineResult` for that operation. Every nested {objectValue} is another `ObjectEngineResult` reached through a value slot, and every abstract object carries its concrete type directly. Consequently {ResolveFieldValue()} is exact key lookup, {ResolveAbstractType()} is type-witness projection, and neither algorithm calls application code.

The Engine Result Tree is quiescent while these algorithms execute: its key shape and occurrence identities are stable, every selected cell is present, and every slot that response completion reads is synchronously available. Each selected value is either schema-conforming GraphQL {null}, a schema-conforming non-error `EngineResult`, or an `ErrorEngineResult`.

++}
