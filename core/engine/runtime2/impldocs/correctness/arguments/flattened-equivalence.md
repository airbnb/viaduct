# Argument for [flattened-equivalence]

This claim concerns the use of selection sets for Viaduct field resolution, not GraphQL field completion. "Supported post-validation selections" means that validation has succeeded, named fragment spreads have already been inlined, and directive-controlled applicability currently deferred by the model is absent. Given fixed global assumptions and runtime object values, the observable behavior is the unordered forest of field-resolution obligations applicable to those object occurrences. Each obligation identifies its object occurrence, a `ObjectEngineResult.Key` carrying a canonical output field and arguments, its runtime applicability guard, and recursively the obligations for returned object occurrences. Aliases, response keys, source order, and the intermediate traversal of inline fragments are not observable at this boundary.

Consider an interpreter for spec selections. A field emits one resolution obligation when every enclosing type condition admits the concrete runtime type. Entering an inline fragment is a silent step: a fragment without a type condition leaves the context unchanged, while a typed fragment changes the nominal type and adds its possible runtime types to the conditions that must all hold. Descending through a field starts a new context based on that field's result type.

The flattened interpreter emits a selection exactly when the concrete runtime type belongs to its `possibleTypes`. It obtains the field coordinate and immediate field-lookup context directly from `key.field`, then interprets `subselections` against returned object occurrences.

These flattened `ObjectEngineResult.Key` values are selection keys outside an OER, so their fields may belong to abstract nominal types and their arguments may contain variable templates. Any later materialization of one of these obligations in an OER must use the corresponding field of the applicable concrete object type and occurrence-instantiated arguments; keys actually present in an OER never carry interface or union field coordinates or uninstantiated variable templates, although they may retain resolver-occurrence variable instances until their bindings complete. Resolver-visible EODs use response-key strings rather than this key hierarchy.

Flattening preserves the following invariant for every field occurrence:

- `key` preserves the canonical schema output field and arguments while discarding the unobservable alias;
- `key.field.containingType` records the innermost typed fragment since the last field boundary, or the type inherited at that boundary when there is no such fragment;
- `possibleTypes` is the intersection of the possible types admitted by every enclosing typed fragment and the type inherited at the field boundary; and
- `subselections` are recursively flattened after resetting the context to the field's result type.

For any concrete parent type, satisfying every nested spec type condition is equivalent to membership in their intersection. The interpreters therefore emit corresponding obligations for each field occurrence. The field case establishes the same key and field-lookup context and invokes the induction hypothesis on its result; a typeless fragment preserves the hypothesis; and a typed fragment preserves it by adding one set to the intersection. Structural induction over each finite selection forest yields the same unordered forest of resolution obligations.

Field collection is deliberately downstream of this equivalence. Duplicate obligations remain distinct, so any future collector that depends only on these resolution observations may group equal `ObjectEngineResult.Key` values, combine child demand, or retain duplicates without invalidating the argument. A collector that depends on erased information such as aliases or response order is outside the claim because it performs field completion rather than field resolution keyed by `ObjectEngineResult.Key`.
