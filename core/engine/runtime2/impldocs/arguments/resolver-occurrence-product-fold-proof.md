# Argument for [resolver-occurrence-product-fold-proof]

`tla/OccurrenceFolds.tla` forms one finite work-item set from every position in every reachable object occurrence's duplicate-free construction order. A step may choose a ready position from any occurrence, but an occurrence's later positions are unavailable while one of its predecessors remains. The world assumption that every nonempty pending subset has a ready work item is the product form of the finite acyclic dependency-order property.

`tla/OccurrenceFoldsProof.tla` proves type safety, strict decrease of the remaining-work cardinality, weak-fairness termination, and equality between each terminal built-key image and its complete construction-order image. Because every order image equals that occurrence's least closed demand, all occurrence folds complete simultaneously. A separate `OutputAlignment` relation identifies these terminal built keys with the returned result tree's `PresentKeys`; proving that relation from Kotlin `resolvePassiveValues` and object union remains part of the structural refinement boundary.

TLC exhaustively checks a two-occurrence model whose Query and nested-object folds may interleave while preserving each local order. The nine-state search checks safety and eventual simultaneous completion; it is counterexample-finding evidence rather than a proof of the finite-world assumptions.
