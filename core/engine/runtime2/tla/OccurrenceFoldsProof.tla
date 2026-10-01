--------------------- MODULE OccurrenceFoldsProof ---------------------
EXTENDS OccurrenceFolds, FiniteSetTheorems, TLAPS,
        NaturalsInduction

ASSUME OccurrenceFoldAssumptions == FoldWorld

LEMMA FoldInitTypeOK == FoldInit => FoldTypeOK
BY OccurrenceFoldAssumptions, FS_CardinalityType
   DEF FoldWorld, FoldInit, FoldTypeOK

LEMMA ReadyWorkExists ==
    FoldTypeOK /\ remainingWork # {}
        => \E work \in WorkItems : Ready(work)
BY OccurrenceFoldAssumptions
   DEF FoldWorld, FoldTypeOK, Ready

LEMMA ProcessPreservesTypeOK ==
    \A work \in WorkItems :
        FoldTypeOK /\ Process(work) => FoldTypeOK'
<1>. SUFFICES
        ASSUME NEW work \in WorkItems,
               FoldTypeOK,
               Process(work)
        PROVE FoldTypeOK'
    OBVIOUS
<1>1. /\ IsFiniteSet(remainingWork')
      /\ Cardinality(remainingWork') =
             Cardinality(remainingWork) - 1
    BY FS_RemoveElement DEF FoldTypeOK, Process, Ready
<1>. QED BY <1>1, Isa
          DEF FoldTypeOK, Process, Ready

LEMMA FoldNextTypeOK ==
    FoldTypeOK /\ [FoldNext]_foldVars => FoldTypeOK'
<1>1. FoldTypeOK /\ FoldNext => FoldTypeOK'
    BY ProcessPreservesTypeOK DEF FoldNext
<1>2. FoldTypeOK /\ UNCHANGED foldVars => FoldTypeOK'
    BY Isa DEF FoldTypeOK, foldVars
<1>. QED BY <1>1, <1>2 DEF foldVars

THEOREM FoldTypeSafety ==
    FoldSpec => []FoldTypeOK
BY FoldInitTypeOK, FoldNextTypeOK, PTL DEF FoldSpec

LEMMA WorkProgressEnabled ==
    FoldTypeOK /\ n > 0 => ENABLED <<FoldNext>>_foldVars
<1>. SUFFICES
        ASSUME FoldTypeOK, n > 0
        PROVE ENABLED <<FoldNext>>_foldVars
    OBVIOUS
<1>1. remainingWork # {}
    BY FS_EmptySet DEF FoldTypeOK
<1>2. PICK work \in WorkItems : Ready(work)
    BY <1>1, ReadyWorkExists
<1>3. ENABLED <<Process(work)>>_foldVars
    BY <1>2, ExpandENABLED, AutoUSE
       DEF Process, Ready, foldVars
<1>. QED BY <1>2, <1>3, ExpandENABLED, AutoUSE
          DEF FoldNext, Process, Ready, foldVars

THEOREM AllWorkTerminates ==
    FoldSpec => <>FoldDone
<1> DEFINE P(m) == FoldSpec => <>(n = m)
<1> HIDE DEF P
<1>1. P(0)
    <2>1. P(Cardinality(WorkItems))
        BY PTL DEF P, FoldSpec, FoldInit
    <2>2. \A m \in 1..Cardinality(WorkItems) :
               P(m) => P(m - 1)
        <3>1. SUFFICES
                ASSUME NEW m \in 1..Cardinality(WorkItems)
                PROVE FoldSpec /\ <>(n = m)
                          => <>(n = m - 1)
            BY DEF P
        <3>2. (n = m) /\ [FoldNext]_foldVars
                  => (n = m)' \/ (n = m - 1)'
            BY DEF FoldNext, Process, foldVars
        <3>3. (n = m) /\ <<FoldNext>>_foldVars
                  => (n = m - 1)'
            BY DEF FoldNext, Process, foldVars
        <3>4. FoldTypeOK /\ (n = m)
                  => ENABLED <<FoldNext>>_foldVars
            BY WorkProgressEnabled
        <3>. QED
            BY FoldTypeSafety, <3>2, <3>3, <3>4, PTL
               DEF FoldSpec
    <2>3. Cardinality(WorkItems) \in Nat
        BY OccurrenceFoldAssumptions, FS_CardinalityType
           DEF FoldWorld
    <2>. QED
        BY <2>1, <2>2, <2>3, DownwardNatInduction, Isa
<1>2. FoldTypeOK /\ n = 0 => FoldDone
    BY FS_EmptySet DEF FoldTypeOK, FoldDone
<1>. QED BY FoldTypeSafety, <1>1, <1>2, PTL
          DEF P

LEMMA BuiltKeysAtDone ==
    FoldDone =>
        \A object \in ReachableObjects :
            BuiltKeys(object) = OrderKeys(object)
<1>. SUFFICES
        ASSUME FoldDone, NEW object \in ReachableObjects
        PROVE BuiltKeys(object) = OrderKeys(object)
    OBVIOUS
<1>1. BuiltKeys(object) \subseteq OrderKeys(object)
    BY Isa
       DEF FoldDone, BuiltKeys, CompletedWork, WorkItems,
           OrderKeys, WorkObject, WorkPosition, WorkKey
<1>2. ASSUME NEW key \in OrderKeys(object)
      PROVE key \in BuiltKeys(object)
    <2>1. PICK position \in
                     1..Len(ConstructionOrderAt[object]) :
               ConstructionOrderAt[object][position] = key
        BY DEF OrderKeys
    <2>2. <<object, position>> \in WorkItems
        BY <2>1 DEF WorkItems
    <2>3. <<object, position>> \in CompletedWork
        BY <2>2 DEF FoldDone, CompletedWork
    <2>4. /\ WorkObject(<<object, position>>) = object
          /\ WorkKey(<<object, position>>) = key
        BY <2>1
           DEF WorkObject, WorkPosition, WorkKey
    <2>. QED BY <2>3, <2>4 DEF BuiltKeys
<1>. QED BY <1>1, <1>2

LEMMA CompletedStateIsComplete ==
    FoldDone => AllFoldsCompleted
BY BuiltKeysAtDone, OccurrenceFoldAssumptions
   DEF AllFoldsCompleted, FoldWorld

LEMMA BuiltCellsAtDone ==
    FoldDone => BuiltCells = PresentCells
BY OccurrenceFoldAssumptions, Isa
   DEF FoldWorld, FoldDone, BuiltCells, CompletedWork

LEMMA BuiltKeysAlignWithPresentCells ==
    FoldDone =>
        \A object \in ReachableObjects :
            PresentKeys(object) = BuiltKeys(object)
<1>. SUFFICES
        ASSUME FoldDone, NEW object \in ReachableObjects
        PROVE PresentKeys(object) = BuiltKeys(object)
    OBVIOUS
<1>1. PresentCells = BuiltCells
    BY FoldDone, BuiltCellsAtDone
<1>2. ASSUME NEW key \in PresentKeys(object)
      PROVE key \in BuiltKeys(object)
    <2>1. PICK cell \in PresentCells :
               /\ CellObject[cell] = object
               /\ CellKey[cell] = key
        BY <1>2 DEF PresentKeys
    <2>2. cell \in BuiltCells
        BY <1>1, <2>1
    <2>3. PICK work \in CompletedWork :
               WorkCell[work] = cell
        BY <2>2 DEF BuiltCells
    <2>4. work \in WorkItems
        BY <2>3 DEF CompletedWork
    <2>5. /\ WorkObject(work) = object
          /\ WorkKey(work) = key
        BY <2>1, <2>3, <2>4, OccurrenceFoldAssumptions
           DEF FoldWorld
    <2>. QED BY <2>3, <2>5 DEF BuiltKeys
<1>3. ASSUME NEW key \in BuiltKeys(object)
      PROVE key \in PresentKeys(object)
    <2>1. PICK work \in CompletedWork :
               /\ WorkObject(work) = object
               /\ WorkKey(work) = key
        BY <1>3 DEF BuiltKeys
    <2>2. work \in WorkItems
        BY <2>1 DEF CompletedWork
    <2>3. WorkCell[work] \in BuiltCells
        BY <2>1 DEF BuiltCells
    <2>4. WorkCell[work] \in PresentCells
        BY <1>1, <2>3
    <2>5. /\ CellObject[WorkCell[work]] = object
          /\ CellKey[WorkCell[work]] = key
        BY <2>1, <2>2, OccurrenceFoldAssumptions
           DEF FoldWorld
    <2>. QED BY <2>4, <2>5 DEF PresentKeys
<1>. QED BY <1>2, <1>3

THEOREM CompletedFoldAlignsWithOutput ==
    FoldDone => OutputAlignment
BY BuiltCellsAtDone, BuiltKeysAlignWithPresentCells
   DEF OutputAlignment

THEOREM AllOccurrenceFoldsComplete ==
    FoldSpec => FoldTermination
BY AllWorkTerminates, CompletedStateIsComplete, PTL
   DEF FoldTermination

THEOREM CompletedResultRefinesFoldCompleted ==
    AllFoldsCompleted => FoldCompleted
BY CompletedFoldAlignsWithOutput
   DEF AllFoldsCompleted, OutputAlignment, FoldCompleted

=============================================================================
