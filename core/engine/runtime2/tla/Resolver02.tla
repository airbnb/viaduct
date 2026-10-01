-------------------------- MODULE Resolver02 --------------------------
EXTENDS ResolverCoreProof

(*
DirectDemand[k] is the set of exact, applicable sibling keys in resolver k's
argument-dependent objectFragment. ClosedDemand is therefore the least local
resolver-demand closure used by resolver02.closeResolverDemand.
*)

Resolver02World == World

ResolverDemandClosed ==
    \A k \in resolved \cap ResolverKeys :
        DirectDemand[k] \subseteq resolved

Resolver02Correct ==
    /\ Done
    /\ InitialDemand \subseteq resolved
    /\ ResolverDemandClosed
    /\ InputsComplete
    /\ OneApplication

THEOREM Resolver02LocalCorrectness ==
    Spec => <>Resolver02Correct
BY ResolverCoreCorrectness, PTL
   DEF Resolver02World, Resolver02Correct, ResultCorrect,
       ResolverDemandClosed

(*
ResultTreeProof and TreeConstructionProof replace the former assumption-shaped
recursive lifting statement. They encode the finite OER extensionally, derive
its least root-reachable occurrence set, and prove that completed local folds
establish whole-tree fragment and resolver-demand conformance.
*)

=============================================================================
