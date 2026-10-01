-------------------------- MODULE Resolver01 --------------------------
EXTENDS ResolverCoreProof

(*
Resolver01 is scoped here to the stage requested for this proof: resolver
object fragments are empty. The Kotlin implementation is more general because
ordinary resolver object fragments may have direct requirements; that larger
local-demand case is covered by Resolver02. This TLA abstraction does not model
the Kotlin resolvers' symbolic root-field-reference execution.
*)

Resolver01World ==
    /\ World
    /\ \A k \in ResolverKeys : DirectDemand[k] = {}

ASSUME Resolver01EmptyFragments ==
    \A k \in ResolverKeys : DirectDemand[k] = {}

THEOREM Resolver01DemandIsUnchanged ==
    ClosedDemand = InitialDemand
BY DemandClosure, Resolver01EmptyFragments
   DEF ClosedDemand, ClosedSets

Resolver01Correct ==
    /\ Done
    /\ resolved = InitialDemand
    /\ OneApplication

LEMMA Resolver01CompletedState ==
    TypeOK /\ Done /\ ResultCorrect => Resolver01Correct
BY PrefixAtDone, Resolver01DemandIsUnchanged
   DEF Resolver01Correct, ResultCorrect

THEOREM Resolver01Correctness ==
    Spec => <>Resolver01Correct
<1>1. Spec => <>(Done /\ ResultCorrect)
    BY ResolverCoreCorrectness
<1>2. Spec => []TypeOK
    BY TypeSafety
<1>. QED BY <1>1, <1>2, Resolver01CompletedState, PTL

=============================================================================
