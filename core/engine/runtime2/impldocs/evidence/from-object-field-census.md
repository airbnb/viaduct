# `FromObjectField` Production Census

> **Historical evidence.** This census describes application source at commit `36cf5db45cd8c`. It informed representative Runtime2 fixtures and early contract alternatives; it is not a current production inventory or the Runtime2 support contract.

## Scope

Modern usage is an exact search for `fromObjectField`; classic usage is a source census of DFP and component delegation. Framework tests and examples are excluded from production counts.

The modern sample is exhaustive but narrow: all 11 declarations belong to four resolver classes in one PDP Stays migration cohort. Classic evidence is broader but is not the same abstraction.

## Modern Census

| Owner | Variable | Provider path | Shape | Provider arguments | Use |
| --- | --- | --- | --- | --- | --- |
| `HighlightsResolver` | `containerPropertyListingIds` | `containerPropertyListingIds` | direct sibling resolver, `[String!]!` | none | object-fragment argument |
| `HighlightsResolver` | `compactCountryCode` | `listing.location.countryCode` | nested node-backed data, `String` | none | object-fragment argument |
| `HighlightsResolver` | `promotionListingId` | `listingInternalId` | direct sibling resolver, `Long` | none | query-fragment argument |
| `HighlightsResolver` | `promotionMarket` | `stayListing.supplyListing.location.defaultAddress.market` | nested node-backed data, `String` | none | query-fragment argument |
| `HighlightsResolver` | `fetchPromotionHighlight` | `shouldFetchPromotionHighlight` | direct sibling resolver, `Boolean!` | none | query-fragment directive |
| `HighlightsResolver` | `skipListingHighlights` | `shouldSkipListingHighlights` | direct sibling resolver, `Boolean!` | terminal field takes `pdpSurface` from `FromArgument` | object-fragment directive |
| `ReportTermsDisclaimerResolver` | `isEligible` | `reportTermsDisclaimerIsEligible` | direct sibling resolver, `Boolean!` | none | query-fragment directive |
| `ReportTermsDisclaimerResolver` | `listingId` | `listingInternalId` | direct sibling resolver, `Long` | none | query-fragment argument |
| `ReportTermsDisclaimerResolver` | `market` | `stayListing.supplyListing.location.defaultAddress.market` | nested node-backed data, `String` | none | query-fragment argument |
| `HostInfoResolver` | `isPropertyHost` | `isPropertyHeroSectionEnabled` | direct sibling resolver, `Boolean!` | none | object-fragment directives |
| `HeroMediaResolver` | `isPropertyListing` | `isPropertyHeroSectionEnabled` | direct sibling resolver, `Boolean!` | none | object-fragment directive |

The declarations are at:

* `modules/presentation/pdp/stays/.../HighlightsResolver.kt:61-72`
* `modules/presentation/pdp/stays/.../ReportStatusResolver.kt:161-168`
* `modules/presentation/pdp/stays/.../HostInfoResolver.kt:50-110`
* `modules/presentation/pdp/stays/.../HeroMediaResolver.kt:26-126`

The corresponding schema is in `DemandStayListingExtensions.graphqls:1007-1063`.

### Distribution

* 11 declarations, 8 unique paths, 4 owner resolvers, 1 application module.
* 8 of 11 paths are one component long.
* The other paths have lengths 3 (once) and 5 (twice).
* 8 of 11 read a direct sibling field resolver.
* 3 of 11 read nested concrete node-backed data.
* 0 of 11 terminate at `id`.
* 1 of 11 has an argument-bearing provider field. It is the terminal field, and its argument is immediately available through `FromArgument`.
* 0 of 11 has an argument-bearing intermediate field.
* 0 of 11 has a provider key that depends on another `FromObjectField`.
* 0 of 11 traverses a list. One path terminates in a scalar list.
* No declaration has a variable-use site below a list-valued field.
* 0 of 11 uses an alias, lossy type condition, abstract intermediate type, or conditional provider occurrence.
* Every path is selected unconditionally in its owner's fixed `objectValueFragment`, as the production validator requires.

Every declaration reaches outside the containing producer's OSS. The direct cases cross to a sibling field resolver. The nested cases cross node-backed resolver boundaries and read a non-ID scalar inside the downstream node data. Consequently, an "outside the OSS means node `id` only" rule accepts none of current modern production.

## Classic Equivalent

Classic DFPs and components have no declarative `fromObjectField` equivalent. The equivalent operation is:

1. select data in the DFP or component fragment;
2. read it from `fragmentResult`; and
3. pass a value to `DerivedField(...)`, a child `QueryComponent(...).loadAndCreate(context)`, or a loader.

`FragmentVariables` carry caller or constructor values; they do not identify object paths.

A production `src/main` census found 137 source lines containing `DerivedField(...)` across 125 files. Of those files, 119 directly access `fragmentResult`. A lexical sample found 222 unique file/line/path mentions, 97 of which end literally in `id`. This makes identity common but not a sufficient compatibility rule. Other terminals include ID-like fields, confirmation codes, dates, enums, counts, coordinates, locale, booleans, and business keys.

Argument-bearing source selections are uncommon but real. Examples include `legacyBill(...)`, `node(id:)`, and `inAppNotifications(surfaceType:)`. A same-line lexical scan found them in 6 of the 125 files. A focused scan found three list-related DFP files. These are lower bounds. The list-derived handoffs use imperative indexing, filtering, mapping, or aggregation rather than plain path extraction. Those cases support keeping list traversal outside the declarative feature.

Classic sources remain inside the owning DFP/component fragment. Classic therefore does not provide evidence for arbitrary fetching outside declared input demand, nor does it validate an outside-OSS `id` exception.

The classic counts are source-based lower bounds where fragments, aliases, or helpers can conceal the exact source shape.

## Restricted Contract Considered

The smallest contract supported by this census would restrict `FromObjectField` to a direct sibling field:

1. The path has exactly one component and is an unconditional selection in the owner's fixed object fragment.
2. The provider terminates in a scalar, enum, or nested list of simple values.
3. A provider field may have arguments only when every argument is already ground from a literal, schema default, or the defining occurrence's `FromArgument`. A `FromObjectField` may not ground a provider key.
4. Provider chains are rejected.
5. A `FromObjectField` variable may not be used below a list-valued field.
6. Aliases and narrowing type conditions are rejected for provider paths.

That alternative would admit 8 of the 11 declarations unchanged. The remaining three declarations represent only two unique nested values: `listing.location.countryCode` and `stayListing.supplyListing.location.defaultAddress.market`. Two direct helper fields on `DemandStayListingPdpPresentationContainer` would bring the entire observed modern sample into the restricted contract.

This is a materially smaller problem than generalized runtime path traversal. The provider resolver occurrence is known at the owner OER, so resolution does not need to discover descendant provider instances through a dynamically published subtree. Resolution retains symbolic consumer keys through grounding, so distinct symbolic keys may invoke the same grounded arguments separately.

The next-smallest alternative adds argument-free traversal through singular concrete node-backed objects. That covers all 11 observed declarations, but preserves the dynamic descendant-provider problem and its runtime occurrence-specific traversal. Neither alternative is the current contract; current semantics are documented in the [semantic model](../architecture/model.md#variables-and-keys) and [Resolution design](../architecture/resolution.md#binding-declaration).

## Relevance To Runtime2

The census supports keeping direct sibling providers, singular nested object paths, scalar-list terminals, and already-ground provider arguments visible in representative fixtures. It provides no evidence that list traversal, provider chains, alias-sensitive identity, or late-bound provider arguments were required by the observed modern cohort. Absence from this dated sample is not evidence that a shape is invalid or unused now.

These observations constrain fixture selection; they do not define Resolution architecture or the alpha feature surface.
