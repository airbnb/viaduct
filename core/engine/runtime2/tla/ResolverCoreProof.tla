----------------------- MODULE ResolverCoreProof -----------------------
EXTENDS ResolverCore, TLAPS, NaturalsInduction

ASSUME ResolverWorld == World

THEOREM DemandClosure ==
    /\ InitialDemand \subseteq ClosedDemand
    /\ ClosedDemand \subseteq Keys
    /\ \A k \in ClosedDemand \cap ResolverKeys :
           DirectDemand[k] \subseteq ClosedDemand
    /\ \A demand \in ClosedSets : ClosedDemand \subseteq demand
<1>1. ASSUME NEW k \in InitialDemand
      PROVE k \in ClosedDemand
    <2>1. InitialDemand \subseteq Keys
        BY ResolverWorld DEF World
    <2>2. k \in Keys
        BY <2>1
    <2>3. \A demand \in ClosedSets : k \in demand
        BY DEF ClosedSets
    <2>. QED BY <2>2, <2>3 DEF ClosedDemand
<1>2. ASSUME NEW k \in ClosedDemand \cap ResolverKeys,
             NEW dependency \in DirectDemand[k]
      PROVE dependency \in ClosedDemand
    <2>1. k \in Keys
        BY DEF ClosedDemand
    <2>2. DirectDemand[k] \subseteq Keys
        BY <2>1, ResolverWorld DEF World
    <2>3. dependency \in Keys
        BY <2>2
    <2>4. \A demand \in ClosedSets : dependency \in demand
        BY DEF ClosedDemand, ClosedSets
    <2>. QED BY <2>3, <2>4 DEF ClosedDemand
<1>. QED BY <1>1, <1>2 DEF ClosedDemand

LEMMA InitImpliesTypeOK == Init => TypeOK
BY DEF Init, TypeOK

LEMMA NextPreservesTypeOK == TypeOK /\ [Next]_vars => TypeOK'
BY DEF World, TypeOK, Next, vars

THEOREM TypeSafety == Spec => []TypeOK
BY InitImpliesTypeOK, NextPreservesTypeOK, PTL DEF Spec

LEMMA PrefixAtDone ==
    TypeOK /\ Done => resolved = ClosedDemand
BY ResolverWorld DEF World, TypeOK, Done, resolved, BuiltCount

LEMMA OrderedDependenciesAreResolved ==
    TypeOK =>
        \A k \in resolved :
            DirectDemand[k] \subseteq resolved
BY ResolverWorld DEF World, TypeOK, resolved, PrefixKeys, BuiltCount

LEMMA ApplicationPositionsAreUnique ==
    TypeOK => OneApplication
BY ResolverWorld DEF World, TypeOK, OneApplication, applications,
       resolved, PrefixKeys, BuiltCount

THEOREM CompletedConstructionIsCorrect ==
    TypeOK /\ Done => ResultCorrect
BY DemandClosure, PrefixAtDone, OrderedDependenciesAreResolved,
   ApplicationPositionsAreUnique
   DEF ResultCorrect, InputsComplete, completeInputs, applications

THEOREM ConstructionTerminates == Spec => Termination
<1> DEFINE P(m) == Spec => <>(n = m)
<1> HIDE DEF P
<1>1. P(0)
    <2>1. P(Len(ConstructionOrder))
        BY PTL DEF P, Spec, Init
    <2>2. \A m \in 1..Len(ConstructionOrder) : P(m) => P(m - 1)
        <3>1. SUFFICES
                ASSUME NEW m \in 1..Len(ConstructionOrder)
                PROVE Spec /\ <>(n = m) => <>(n = m - 1)
            BY DEF P
        <3>2. (n = m) /\ [Next]_vars => (n = m)' \/ (n = m - 1)'
            BY DEF Next, vars
        <3>3. (n = m) /\ <<Next>>_vars => (n = m - 1)'
            BY DEF Next, vars
        <3>4. (n = m) => ENABLED <<Next>>_vars
            BY ExpandENABLED DEF Next, vars
        <3>. QED
            BY <3>2, <3>3, <3>4, PTL DEF Spec
    <2>3. Len(ConstructionOrder) \in Nat
        BY ResolverWorld DEF World
    <2>. QED
        BY <2>1, <2>2, <2>3, DownwardNatInduction, Isa
<1>. QED BY <1>1, PTL DEF P, Termination, Done

THEOREM ResolverCoreCorrectness ==
    Spec => <>(Done /\ ResultCorrect)
<1>1. Spec => [](Done => ResultCorrect)
    BY TypeSafety, CompletedConstructionIsCorrect, PTL
<1>2. [] (Done => ResultCorrect) /\ <>Done
        => <>(Done /\ ResultCorrect)
    BY PTL
<1>. QED BY ConstructionTerminates, <1>1, <1>2 DEF Termination

=============================================================================
