# Argument for [dependency-order-worklist-proof]

`tla/DependencyOrder.tla` models the recursive `SiblingDependencyLogic.order` operation as a finite worklist. A step may remove only a key whose dependencies do not intersect the unresolved set; the invariant proves that removed keys and unresolved keys partition the carrier, every removed key's dependencies are resolved, and the application set equals the resolved set. A cardinality countdown plus weak fairness proves termination, and TLAPS discharges the complete safety and liveness argument.

The theorem assumes every nonempty subset has a ready member, which is the finite acyclicity property established by registry assembly in the Kotlin reasoning world. `DependencyOrderMC` exhaustively explores a four-key graph with two transitive dependency edges; that TLC run is finite counterexample-finding evidence separate from the symbolic proof.
