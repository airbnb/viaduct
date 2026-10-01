------------------------ MODULE OccurrenceFolds ------------------------
EXTENDS TreeConstruction, Naturals, Sequences

(*
Product state machine for every finite object-occurrence fold. A work item is
one position in one occurrence's dependency-first construction order. Folds
may interleave, but positions within one occurrence become ready only after
their predecessors. Once all work is complete, Publish snapshots every
occurrence's built key set as the result.
*)

CONSTANTS ConstructionOrderAt, WorkCell

OrderKeys(object) ==
    {ConstructionOrderAt[object][position] :
        position \in 1..Len(ConstructionOrderAt[object])}

WorkItems ==
    UNION
        {{<<object, position>> :
            position \in 1..Len(ConstructionOrderAt[object])} :
         object \in ReachableObjects}

WorkObject(work) == work[1]
WorkPosition(work) == work[2]

WorkKey(work) ==
    ConstructionOrderAt[WorkObject(work)][WorkPosition(work)]

EarlierWork(work) ==
    {<<WorkObject(work), position>> :
        position \in 1..(WorkPosition(work) - 1)}

FoldWorld ==
    /\ ConstructionWorld
    /\ ConstructionOrderAt \in [Objects -> Seq(Keys)]
    /\ \A object \in ReachableObjects :
           OrderKeys(object) = ClosedDemandAt(object)
    /\ \A object \in ReachableObjects :
           \A first, second \in
                    1..Len(ConstructionOrderAt[object]) :
               ConstructionOrderAt[object][first] =
                   ConstructionOrderAt[object][second]
                       => first = second
    /\ \A object \in ReachableObjects :
           \A position \in 1..Len(ConstructionOrderAt[object]) :
               \A dependency \in
                       DirectDemandByKey[object][
                           ConstructionOrderAt[object][position]] :
                   \E earlier \in 1..(position - 1) :
                       ConstructionOrderAt[object][earlier] = dependency
    /\ IsFiniteSet(WorkItems)
    /\ \A work \in WorkItems : WorkKey(work) \in Keys
    /\ WorkCell \in [WorkItems -> PresentCells]
    /\ PresentCells = {WorkCell[work] : work \in WorkItems}
    /\ \A work \in WorkItems :
           /\ CellObject[WorkCell[work]] = WorkObject(work)
           /\ CellKey[WorkCell[work]] = WorkKey(work)
    /\ \A pending \in (SUBSET WorkItems) \ {{}} :
           \E work \in pending :
               EarlierWork(work) \cap pending = {}

VARIABLES remainingWork, n

foldVars == <<remainingWork, n>>

CompletedWork == WorkItems \ remainingWork

BuiltCells ==
    {WorkCell[work] : work \in CompletedWork}

BuiltKeys(object) ==
    {WorkKey(work) :
        work \in {candidate \in CompletedWork :
            WorkObject(candidate) = object}}

FoldInit ==
    /\ remainingWork = WorkItems
    /\ n = Cardinality(WorkItems)

Ready(work) ==
    /\ work \in remainingWork
    /\ EarlierWork(work) \cap remainingWork = {}

Process(work) ==
    /\ Ready(work)
    /\ remainingWork' = remainingWork \ {work}
    /\ n' = n - 1

FoldNext ==
    \E work \in WorkItems : Process(work)

FoldSpec ==
    FoldInit
        /\ [][FoldNext]_foldVars
        /\ WF_foldVars(FoldNext)

FoldTypeOK ==
    /\ remainingWork \subseteq WorkItems
    /\ IsFiniteSet(remainingWork)
    /\ n = Cardinality(remainingWork)

FoldDone == remainingWork = {}

AllFoldsCompleted ==
    /\ FoldDone
    /\ \A object \in ReachableObjects :
           BuiltKeys(object) = ClosedDemandAt(object)

FoldTermination == <>AllFoldsCompleted

OutputAlignment ==
    /\ FoldDone
    /\ PresentCells = BuiltCells
    /\ \A object \in ReachableObjects :
           PresentKeys(object) = BuiltKeys(object)

=============================================================================
