----------------------- MODULE ResultTreeProof -----------------------
EXTENDS ResultTree, FiniteSetTheorems, TLAPS

ASSUME ResultTreeWorld == World

THEOREM ReachabilityIsLeastClosedSet ==
    /\ Root \in ReachableObjects
    /\ \A cell \in PresentCells :
           CellObject[cell] \in ReachableObjects
               => CellChildren[cell] \subseteq ReachableObjects
    /\ \A objects \in ObjectClosedSets :
           ReachableObjects \subseteq objects
<1>1. Root \in Objects
    BY ResultTreeWorld DEF World, WorldCarriers
<1>2. \A objects \in ObjectClosedSets : Root \in objects
    BY DEF ObjectClosedSets
<1>3. Root \in ReachableObjects
    BY <1>1, <1>2 DEF ReachableObjects
<1>4. \A cell \in PresentCells :
           CellObject[cell] \in ReachableObjects
               => CellChildren[cell] \subseteq ReachableObjects
    <2>. SUFFICES
            ASSUME NEW cell \in PresentCells,
                   CellObject[cell] \in ReachableObjects
            PROVE CellChildren[cell] \subseteq ReachableObjects
        OBVIOUS
    <2>1. ASSUME NEW child \in CellChildren[cell]
          PROVE child \in ReachableObjects
        <3>1. cell \in Cells
            BY ResultTreeWorld
               DEF World, WorldCarriers
        <3>2. CellChildren[cell] \subseteq Objects
            BY <3>1, ResultTreeWorld
               DEF World, WorldTree, WorldChildren
        <3>3. child \in Objects
            BY <3>2
        <3>4. ASSUME NEW objects \in ObjectClosedSets
              PROVE child \in objects
            <4>1. CellObject[cell] \in objects
                BY DEF ReachableObjects
            <4>2. CellChildren[cell] \subseteq objects
                BY <4>1 DEF ObjectClosedSets
            <4>. QED BY <4>2
        <3>. QED BY <3>3, <3>4 DEF ReachableObjects
    <2>. QED BY <2>1
<1>5. \A objects \in ObjectClosedSets :
           ReachableObjects \subseteq objects
    BY DEF ReachableObjects
<1>. QED BY <1>3, <1>4, <1>5

THEOREM ReachableObjectsAreFinite ==
    IsFiniteSet(ReachableObjects)
<1>1. ReachableObjects \subseteq Objects
    BY DEF ReachableObjects
<1>. QED
    BY <1>1, ResultTreeWorld, FS_Subset
       DEF World, WorldCarriers

THEOREM LocalJudgmentsLiftToWholeTree ==
    RootedAndWellTyped /\ AllReachableOccurrencesCorrect
        => CorrectResolution
<1>. SUFFICES
        ASSUME RootedAndWellTyped, AllReachableOccurrencesCorrect
        PROVE CorrectResolution
    OBVIOUS
<1>1. ConformsToFragment
    BY DEF AllReachableOccurrencesCorrect, OccurrenceCorrect,
           ConformsToFragment
<1>2. IsClosedUnderResolverDemand
    <2>. SUFFICES
            ASSUME NEW cell \in ActiveResolverCells
            PROVE ResolverDemand[cell]
                    \subseteq PresentKeys(CellObject[cell])
        BY DEF IsClosedUnderResolverDemand
    <2>1. cell \in PresentCells
        BY DEF ActiveResolverCells
    <2>2. CellObject[cell] \in ReachableObjects
        BY <2>1, ResultTreeWorld
           DEF World, WorldTree, WorldConnected
    <2>3. OccurrenceCorrect(CellObject[cell])
        BY <2>2 DEF AllReachableOccurrencesCorrect
    <2>. QED
        BY <2>3 DEF OccurrenceCorrect
<1>3. ConformsToResolvers
    BY ResultTreeWorld, Isa
       DEF World, WorldTree, WorldConnected, WorldObservations,
           AllReachableOccurrencesCorrect, OccurrenceCorrect,
           ConformsToResolvers, ActiveResolverCells
<1>4. ConformsToTypename
    BY ResultTreeWorld, Isa
       DEF World, WorldTree, WorldConnected,
           AllReachableOccurrencesCorrect, OccurrenceCorrect,
           ConformsToTypename
<1>. QED BY <1>1, <1>2, <1>3, <1>4
          DEF CorrectResolution

THEOREM WholeTreeJudgmentDecomposesLocally ==
    CorrectResolution
        => RootedAndWellTyped /\ AllReachableOccurrencesCorrect
BY ResultTreeWorld, Isa
   DEF World, AllReachableOccurrencesCorrect, OccurrenceCorrect,
       CorrectResolution, ConformsToFragment,
       IsClosedUnderResolverDemand, ConformsToResolvers,
       ConformsToTypename

THEOREM Resolver01PostconditionImpliesCorrectResolution ==
    Resolver01Postcondition => CorrectResolution
BY Isa
   DEF Resolver01Postcondition, CorrectResolution,
       ConformsToFragment, IsClosedUnderResolverDemand

=============================================================================
