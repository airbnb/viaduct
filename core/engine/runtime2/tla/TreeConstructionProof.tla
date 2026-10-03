-------------------- MODULE TreeConstructionProof --------------------
EXTENDS TreeConstruction, FiniteSetTheorems, TLAPS

ASSUME TreeConstructionWorld == ConstructionWorld

THEOREM OccurrenceDemandClosure ==
    \A object \in Objects :
        /\ OperationDemand[object] \subseteq ClosedDemandAt(object)
        /\ ClosedDemandAt(object) \subseteq Keys
        /\ \A key \in ClosedDemandAt(object)
                         \cap ResolverKeyUniverse[object] :
               DirectDemandByKey[object][key]
                   \subseteq ClosedDemandAt(object)
        /\ \A demand \in ClosedSetsAt(object) :
               ClosedDemandAt(object) \subseteq demand
<1>. SUFFICES
        ASSUME NEW object \in Objects
        PROVE
            /\ OperationDemand[object] \subseteq ClosedDemandAt(object)
            /\ ClosedDemandAt(object) \subseteq Keys
            /\ \A key \in ClosedDemandAt(object)
                             \cap ResolverKeyUniverse[object] :
                   DirectDemandByKey[object][key]
                       \subseteq ClosedDemandAt(object)
            /\ \A demand \in ClosedSetsAt(object) :
                   ClosedDemandAt(object) \subseteq demand
    OBVIOUS
<1>1. ASSUME NEW key \in OperationDemand[object]
      PROVE key \in ClosedDemandAt(object)
    <2>1. key \in Keys
        BY TreeConstructionWorld
           DEF ConstructionWorld, World, WorldFunctions
    <2>2. \A demand \in ClosedSetsAt(object) : key \in demand
        BY DEF ClosedSetsAt
    <2>. QED BY <2>1, <2>2 DEF ClosedDemandAt
<1>2. ClosedDemandAt(object) \subseteq Keys
    BY DEF ClosedDemandAt
<1>3. ASSUME NEW key \in ClosedDemandAt(object)
                           \cap ResolverKeyUniverse[object],
             NEW dependency \in DirectDemandByKey[object][key]
      PROVE dependency \in ClosedDemandAt(object)
    <2>1. dependency \in Keys
        BY TreeConstructionWorld
           DEF ConstructionWorld
    <2>2. \A demand \in ClosedSetsAt(object) : dependency \in demand
        BY DEF ClosedDemandAt, ClosedSetsAt
    <2>. QED BY <2>1, <2>2 DEF ClosedDemandAt
<1>4. \A demand \in ClosedSetsAt(object) :
           ClosedDemandAt(object) \subseteq demand
    BY DEF ClosedDemandAt
<1>. QED BY <1>1, <1>2, <1>3, <1>4

THEOREM EmptyFragmentsLeaveDemandUnchanged ==
    NoObjectFragments
        => \A object \in Objects :
               ClosedDemandAt(object) = OperationDemand[object]
<1>. SUFFICES
        ASSUME NoObjectFragments, NEW object \in Objects
        PROVE ClosedDemandAt(object) = OperationDemand[object]
    OBVIOUS
<1>1. OperationDemand[object] \subseteq ClosedDemandAt(object)
    BY OccurrenceDemandClosure
<1>2. OperationDemand[object] \subseteq Keys
    BY TreeConstructionWorld
       DEF ConstructionWorld, World, WorldFunctions
<1>3. \A key \in OperationDemand[object]
                         \cap ResolverKeyUniverse[object] :
           DirectDemandByKey[object][key]
               \subseteq OperationDemand[object]
    BY DEF NoObjectFragments
<1>4. OperationDemand[object] \in ClosedSetsAt(object)
    BY <1>2, <1>3 DEF ClosedSetsAt
<1>5. ClosedDemandAt(object) \subseteq OperationDemand[object]
    BY <1>4, OccurrenceDemandClosure
<1>. QED BY <1>1, <1>5

THEOREM CompletedFoldsConformToFragment ==
    FoldCompleted => ConformsToFragment
<1>. SUFFICES ASSUME FoldCompleted
               PROVE ConformsToFragment
    OBVIOUS
<1>1. ASSUME NEW object \in ReachableObjects
      PROVE OperationDemand[object] \subseteq PresentKeys(object)
    <2>1. object \in Objects
        BY DEF ReachableObjects
    <2>2. OperationDemand[object] \subseteq ClosedDemandAt(object)
        BY <2>1, OccurrenceDemandClosure
    <2>3. PresentKeys(object) = ClosedDemandAt(object)
        BY DEF FoldCompleted
    <2>. QED BY <2>2, <2>3
<1>. QED BY <1>1 DEF ConformsToFragment

THEOREM CompletedFoldsCloseResolverDemand ==
    FoldCompleted => IsClosedUnderResolverDemand
<1>. SUFFICES ASSUME FoldCompleted
               PROVE IsClosedUnderResolverDemand
    OBVIOUS
<1>1. ASSUME NEW cell \in ActiveResolverCells
      PROVE ResolverDemand[cell]
                \subseteq PresentKeys(CellObject[cell])
    <2>1. CellObject[cell] \in ReachableObjects
        BY TreeConstructionWorld
           DEF ConstructionWorld, World, WorldTree, WorldConnected,
               ActiveResolverCells
    <2>2. CellObject[cell] \in Objects
        BY <2>1 DEF ReachableObjects
    <2>3. CellKey[cell] \in PresentKeys(CellObject[cell])
        BY DEF PresentKeys, ActiveResolverCells
    <2>4. CellKey[cell] \in ClosedDemandAt(CellObject[cell])
        BY <2>1, <2>3 DEF FoldCompleted
    <2>5. CellKey[cell] \in
              ClosedDemandAt(CellObject[cell])
                  \cap ResolverKeyUniverse[CellObject[cell]]
        BY <2>4, TreeConstructionWorld DEF ConstructionWorld
    <2>6. DirectDemandByKey[CellObject[cell]][CellKey[cell]]
              \subseteq ClosedDemandAt(CellObject[cell])
        BY <2>2, <2>5, OccurrenceDemandClosure
    <2>. QED
        BY <2>1, <2>6, TreeConstructionWorld
           DEF ConstructionWorld, FoldCompleted
<1>. QED BY <1>1 DEF IsClosedUnderResolverDemand

THEOREM Resolver01HasExactlyOperationDemand ==
    Resolver01Constructed
        => \A object \in ReachableObjects :
               PresentKeys(object) = OperationDemand[object]
<1>. SUFFICES
        ASSUME Resolver01Constructed,
               NEW object \in ReachableObjects
        PROVE PresentKeys(object) = OperationDemand[object]
    OBVIOUS
<1>1. object \in Objects
    BY DEF ReachableObjects
<1>2. ClosedDemandAt(object) = OperationDemand[object]
    BY <1>1, EmptyFragmentsLeaveDemandUnchanged
       DEF Resolver01Constructed
<1>3. PresentKeys(object) = ClosedDemandAt(object)
    BY DEF Resolver01Constructed, FoldCompleted
<1>. QED BY <1>2, <1>3

THEOREM Resolver01TreeCorrectness ==
    Resolver01Constructed => CorrectResolution
BY CompletedFoldsConformToFragment,
   CompletedFoldsCloseResolverDemand
   DEF Resolver01Constructed, ValueConstructionCorrect,
       CorrectResolution

THEOREM Resolver02TreeCorrectness ==
    Resolver02Constructed => CorrectResolution
BY CompletedFoldsConformToFragment,
   CompletedFoldsCloseResolverDemand
   DEF Resolver02Constructed, ValueConstructionCorrect,
       CorrectResolution

=============================================================================
