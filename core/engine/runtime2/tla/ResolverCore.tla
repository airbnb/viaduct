-------------------------- MODULE ResolverCore --------------------------
EXTENDS Naturals, Sequences, FiniteSets, TLC

(*
One instance describes construction of one concrete OER object occurrence.
Keys are specialized to that occurrence's runtime type and include exact
argument tuples.
*)

CONSTANTS Keys, ResolverKeys, InitialDemand, DirectDemand, ConstructionOrder

ClosedSets ==
    {demand \in SUBSET Keys :
        /\ InitialDemand \subseteq demand
        /\ \A k \in demand \cap ResolverKeys :
               DirectDemand[k] \subseteq demand}

ClosedDemand ==
    {k \in Keys : \A demand \in ClosedSets : k \in demand}

PrefixKeys(count) ==
    {ConstructionOrder[position] : position \in 1..count}

World ==
    /\ IsFiniteSet(Keys)
    /\ ResolverKeys \subseteq Keys
    /\ InitialDemand \subseteq Keys
    /\ DirectDemand \in [Keys -> SUBSET Keys]
    /\ \A k \in Keys : DirectDemand[k] \subseteq Keys
    /\ \A k \in Keys \ ResolverKeys : DirectDemand[k] = {}
    /\ ConstructionOrder \in Seq(Keys)
    /\ PrefixKeys(Len(ConstructionOrder)) = ClosedDemand
    /\ \A first, second \in 1..Len(ConstructionOrder) :
           ConstructionOrder[first] = ConstructionOrder[second]
               => first = second
    /\ \A position \in 1..Len(ConstructionOrder) :
           \A dependency \in DirectDemand[ConstructionOrder[position]] :
               \E earlier \in 1..(position - 1) :
                   ConstructionOrder[earlier] = dependency

VARIABLE n

vars == <<n>>

Init == n = Len(ConstructionOrder)

Next ==
    /\ n > 0
    /\ n' = n - 1

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

BuiltCount == Len(ConstructionOrder) - n
resolved == PrefixKeys(BuiltCount)
applications == resolved \cap ResolverKeys
completeInputs == applications

Done == n = 0

TypeOK == n \in 0..Len(ConstructionOrder)

InputsComplete ==
    \A k \in resolved \cap ResolverKeys :
        k \in completeInputs /\ DirectDemand[k] \subseteq resolved

OneApplication ==
    \A k \in applications :
        \E position \in 1..BuiltCount :
            /\ ConstructionOrder[position] = k
            /\ \A other \in 1..BuiltCount :
                   ConstructionOrder[other] = k => other = position

ResultCorrect ==
    /\ InitialDemand \subseteq resolved
    /\ \A k \in resolved \cap ResolverKeys :
           DirectDemand[k] \subseteq resolved
    /\ InputsComplete
    /\ OneApplication

Termination == <>Done

=============================================================================
