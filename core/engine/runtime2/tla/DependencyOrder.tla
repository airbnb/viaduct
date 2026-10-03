------------------------ MODULE DependencyOrder ------------------------
EXTENDS Naturals, FiniteSets, FiniteSetTheorems, TLAPS,
        NaturalsInduction

(*
This module models the recursive dependencyOrder operation shared by the
resolver implementations. A step chooses any unresolved key whose exact
sibling dependencies are absent from the unresolved suffix. The trace of
chosen keys is therefore a dependency-first order, but the proof records only
the semantic facts used by materialization rather than a list representation.
*)

CONSTANTS Keys, Dependencies

OrderWorld ==
    /\ IsFiniteSet(Keys)
    /\ Dependencies \in [Keys -> SUBSET Keys]
    /\ \A pending \in (SUBSET Keys) \ {{}} :
           \E ready \in pending :
               Dependencies[ready] \cap pending = {}

ASSUME DependencyOrderWorld == OrderWorld

VARIABLES remaining, resolved, applications, n

vars == <<remaining, resolved, applications, n>>

Init ==
    /\ remaining = Keys
    /\ resolved = {}
    /\ applications = {}
    /\ n = Cardinality(Keys)

Ready(key) ==
    /\ key \in remaining
    /\ Dependencies[key] \cap remaining = {}

ResolveReady(key) ==
    /\ Ready(key)
    /\ remaining' = remaining \ {key}
    /\ resolved' = resolved \cup {key}
    /\ applications' = applications \cup {key}
    /\ n' = n - 1

Next == \E key \in Keys : ResolveReady(key)

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

InputsComplete ==
    \A key \in resolved : Dependencies[key] \subseteq resolved

NoReapplication ==
    /\ applications = resolved
    /\ applications \cap remaining = {}

TypeOK ==
    /\ remaining \subseteq Keys
    /\ resolved = Keys \ remaining
    /\ NoReapplication
    /\ InputsComplete
    /\ IsFiniteSet(remaining)
    /\ n = Cardinality(remaining)

Done == remaining = {}

CorrectOrderConstruction ==
    /\ Done
    /\ resolved = Keys
    /\ applications = Keys
    /\ InputsComplete
    /\ NoReapplication

Termination == <>Done

LEMMA InitTypeOK == Init => TypeOK
BY DependencyOrderWorld, FS_CardinalityType
   DEF OrderWorld, Init, TypeOK, InputsComplete, NoReapplication

LEMMA ReadyExists ==
    TypeOK /\ remaining # {} => \E key \in Keys : Ready(key)
BY DependencyOrderWorld
   DEF OrderWorld, TypeOK, Ready

LEMMA ReadyInputsAreResolved ==
    \A key \in Keys :
        TypeOK /\ Ready(key) => Dependencies[key] \subseteq resolved
BY DependencyOrderWorld
   DEF OrderWorld, TypeOK, Ready

LEMMA ResolveReadyPreservesTypeOK ==
    \A key \in Keys : TypeOK /\ ResolveReady(key) => TypeOK'
<1>. SUFFICES
        ASSUME NEW key \in Keys, TypeOK, ResolveReady(key)
        PROVE TypeOK'
    OBVIOUS
<1>1. Dependencies[key] \subseteq resolved
    BY ReadyInputsAreResolved DEF ResolveReady
<1>2. InputsComplete'
    BY <1>1, Isa
       DEF TypeOK, ResolveReady, InputsComplete
<1>3. NoReapplication'
    BY Isa
       DEF TypeOK, ResolveReady, Ready, NoReapplication
<1>4. /\ IsFiniteSet(remaining')
      /\ Cardinality(remaining') = Cardinality(remaining) - 1
    BY FS_RemoveElement
       DEF TypeOK, ResolveReady, Ready
<1>5. /\ remaining' \subseteq Keys
      /\ resolved' = Keys \ remaining'
    BY Isa
       DEF TypeOK, ResolveReady, Ready
<1>6. n' = Cardinality(remaining')
    BY <1>4 DEF TypeOK, ResolveReady
<1>. QED
    BY <1>2, <1>3, <1>4, <1>5, <1>6
       DEF TypeOK

LEMMA NextTypeOK == TypeOK /\ [Next]_vars => TypeOK'
<1>1. TypeOK /\ Next => TypeOK'
    BY ResolveReadyPreservesTypeOK DEF Next
<1>2. TypeOK /\ UNCHANGED vars => TypeOK'
    BY Isa
       DEF TypeOK, InputsComplete, NoReapplication, vars
<1>. QED BY <1>1, <1>2 DEF Next, vars

THEOREM TypeSafety == Spec => []TypeOK
BY InitTypeOK, NextTypeOK, PTL DEF Spec

LEMMA ProgressEnabled ==
    TypeOK /\ n > 0 => ENABLED <<Next>>_vars
<1>. SUFFICES ASSUME TypeOK, n > 0
               PROVE ENABLED <<Next>>_vars
    OBVIOUS
<1>1. remaining # {}
    BY FS_EmptySet DEF TypeOK
<1>2. PICK key \in Keys : Ready(key)
    BY <1>1, ReadyExists
<1>3. ENABLED <<ResolveReady(key)>>_vars
    BY <1>2, ExpandENABLED, AutoUSE
       DEF ResolveReady, Ready, vars
<1>. QED BY <1>3, ExpandENABLED, AutoUSE
          DEF Next, ResolveReady, Ready, vars

THEOREM ConstructionTerminates == Spec => Termination
<1> DEFINE P(m) == Spec => <>(n = m)
<1> HIDE DEF P
<1>1. P(0)
    <2>1. P(Cardinality(Keys))
        BY PTL DEF P, Spec, Init
    <2>2. \A m \in 1..Cardinality(Keys) : P(m) => P(m - 1)
        <3>1. SUFFICES
                ASSUME NEW m \in 1..Cardinality(Keys)
                PROVE Spec /\ <>(n = m) => <>(n = m - 1)
            BY DEF P
        <3>2. TypeOK /\ (n = m) /\ [Next]_vars
                => (n = m)' \/ (n = m - 1)'
            BY DEF TypeOK, Next, ResolveReady, vars
        <3>3. TypeOK /\ (n = m) /\ <<Next>>_vars
                => (n = m - 1)'
            BY DEF TypeOK, Next, ResolveReady, vars
        <3>4. TypeOK /\ (n = m) => ENABLED <<Next>>_vars
            BY ProgressEnabled
        <3>. QED
            BY TypeSafety, <3>2, <3>3, <3>4, PTL DEF Spec
    <2>3. Cardinality(Keys) \in Nat
        BY DependencyOrderWorld, FS_CardinalityType DEF OrderWorld
    <2>. QED
        BY <2>1, <2>2, <2>3, DownwardNatInduction, Isa
<1>2. TypeOK /\ n = 0 => Done
    BY FS_EmptySet DEF TypeOK, Done
<1>. QED BY TypeSafety, <1>1, <1>2, PTL DEF P, Termination

LEMMA CompletedStateIsCorrect ==
    TypeOK /\ Done => CorrectOrderConstruction
BY DEF TypeOK, Done, CorrectOrderConstruction,
       InputsComplete, NoReapplication

THEOREM DependencyOrderCorrectness ==
    Spec => <>CorrectOrderConstruction
<1>1. Spec => <>Done
    BY ConstructionTerminates DEF Termination
<1>2. Spec => []TypeOK
    BY TypeSafety
<1>3. TypeOK /\ Done => CorrectOrderConstruction
    BY CompletedStateIsCorrect
<1>. QED BY <1>1, <1>2, <1>3, PTL

=============================================================================
