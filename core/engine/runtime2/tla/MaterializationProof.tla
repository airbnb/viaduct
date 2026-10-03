--------------------- MODULE MaterializationProof ---------------------
EXTENDS Materialization, OccurrenceFoldsProof, TLAPS

ASSUME MaterializationAssumptions == MaterializationWorld

LEMMA WorkItemCoordinates ==
    \A work \in WorkItems :
        /\ WorkObject(work) \in ReachableObjects
        /\ WorkPosition(work)
               \in 1..Len(ConstructionOrderAt[WorkObject(work)])
        /\ work = <<WorkObject(work), WorkPosition(work)>>
BY Isa
   DEF WorkItems, WorkObject, WorkPosition

LEMMA RequiredKeyHasEarlierWork ==
    \A work \in WorkItems :
        \A key \in InputKeys(work) :
            \E earlier \in EarlierWork(work) :
                WorkKey(earlier) = key
<1>. SUFFICES
        ASSUME NEW work \in WorkItems,
               NEW key \in InputKeys(work)
        PROVE
            \E earlier \in EarlierWork(work) :
                WorkKey(earlier) = key
    OBVIOUS
<1>1. /\ WorkObject(work) \in ReachableObjects
      /\ WorkPosition(work)
             \in 1..Len(ConstructionOrderAt[WorkObject(work)])
      /\ work = <<WorkObject(work), WorkPosition(work)>>
    BY WorkItemCoordinates
<1>2. PICK position \in 1..(WorkPosition(work) - 1) :
           ConstructionOrderAt[WorkObject(work)][position] = key
    BY <1>1, MaterializationAssumptions
       DEF MaterializationWorld, FoldWorld, InputKeys, WorkKey
<1>3. <<WorkObject(work), position>> \in EarlierWork(work)
    BY <1>2 DEF EarlierWork
<1>4. WorkKey(<<WorkObject(work), position>>) = key
    BY <1>2 DEF WorkKey, WorkObject, WorkPosition
<1>. QED BY <1>3, <1>4

LEMMA EarlierCellsArePresent ==
    \A work \in WorkItems :
        EarlierCells(work) \subseteq PresentCells
<1>. SUFFICES
        ASSUME NEW work \in WorkItems,
               NEW cell \in EarlierCells(work)
        PROVE cell \in PresentCells
    OBVIOUS
<1>1. PICK earlier \in EarlierWork(work) :
           WorkCell[earlier] = cell
    BY DEF EarlierCells
<1>2. earlier \in WorkItems
    BY <1>1, WorkItemCoordinates
       DEF EarlierWork, WorkItems, WorkObject, WorkPosition
<1>. QED BY <1>1, <1>2, MaterializationAssumptions
          DEF MaterializationWorld, FoldWorld

LEMMA RequiredCellExistsInPrefix ==
    \A work \in WorkItems :
        \A key \in InputKeys(work) :
            \E cell \in EarlierCells(work) :
                /\ CellObject[cell] = WorkObject(work)
                /\ CellKey[cell] = key
<1>. SUFFICES
        ASSUME NEW work \in WorkItems,
               NEW key \in InputKeys(work)
        PROVE
            \E cell \in EarlierCells(work) :
                /\ CellObject[cell] = WorkObject(work)
                /\ CellKey[cell] = key
    OBVIOUS
<1>1. PICK earlier \in EarlierWork(work) :
           WorkKey(earlier) = key
    BY RequiredKeyHasEarlierWork
<1>2. earlier \in WorkItems
    BY WorkItemCoordinates
       DEF EarlierWork, WorkItems, WorkObject, WorkPosition
<1>3. WorkCell[earlier] \in EarlierCells(work)
    BY <1>1 DEF EarlierCells
<1>4. /\ CellObject[WorkCell[earlier]] = WorkObject(work)
      /\ CellKey[WorkCell[earlier]] = key
    BY <1>1, <1>2, MaterializationAssumptions
       DEF MaterializationWorld, FoldWorld,
           EarlierWork, WorkObject, WorkPosition
<1>. QED BY <1>3, <1>4

LEMMA PrefixAndFinalChooseSameCell ==
    \A work \in WorkItems :
        \A key \in InputKeys(work) :
            CellForKey(
                EarlierCells(work),
                WorkObject(work),
                key)
            =
            CellForKey(
                PresentCells,
                WorkObject(work),
                key)
<1>. SUFFICES
        ASSUME NEW work \in WorkItems,
               NEW key \in InputKeys(work)
        PROVE
            CellForKey(
                EarlierCells(work),
                WorkObject(work),
                key)
            =
            CellForKey(
                PresentCells,
                WorkObject(work),
                key)
    OBVIOUS
<1> DEFINE
        prefixCell ==
            CellForKey(
                EarlierCells(work),
                WorkObject(work),
                key)
        finalCell ==
            CellForKey(
                PresentCells,
                WorkObject(work),
                key)
<1>1. \E cell \in EarlierCells(work) :
           /\ CellObject[cell] = WorkObject(work)
           /\ CellKey[cell] = key
    BY RequiredCellExistsInPrefix
<1>2. /\ prefixCell \in EarlierCells(work)
      /\ CellObject[prefixCell] = WorkObject(work)
      /\ CellKey[prefixCell] = key
    BY <1>1 DEF CellForKey
<1>3. prefixCell \in PresentCells
    BY <1>2, EarlierCellsArePresent
<1>4. \E cell \in PresentCells :
           /\ CellObject[cell] = WorkObject(work)
           /\ CellKey[cell] = key
    BY <1>2, <1>3
<1>5. /\ finalCell \in PresentCells
      /\ CellObject[finalCell] = WorkObject(work)
      /\ CellKey[finalCell] = key
    BY <1>4 DEF CellForKey
<1>6. WorldUniqueKeys
    BY MaterializationAssumptions
       DEF MaterializationWorld, FoldWorld, ConstructionWorld,
           World, WorldTree
<1>7. /\ prefixCell \in PresentCells
      /\ finalCell \in PresentCells
      /\ CellObject[prefixCell] = CellObject[finalCell]
      /\ CellKey[prefixCell] = CellKey[finalCell]
    BY <1>2, <1>3, <1>5
<1>8. prefixCell = finalCell
    BY <1>6, <1>7 DEF WorldUniqueKeys
<1>. QED BY <1>8 DEF prefixCell, finalCell

THEOREM PrefixMaterializationEqualsFinalMaterialization ==
    MaterializedInputsStable
<1>. SUFFICES
        ASSUME NEW work \in WorkItems
        PROVE
            MaterializedInput(EarlierCells(work), work) =
                MaterializedInput(PresentCells, work)
    BY DEF MaterializedInputsStable
<1>1. \A key \in InputKeys(work) :
          CellForKey(
              EarlierCells(work),
              WorkObject(work),
              key)
          =
          CellForKey(
              PresentCells,
              WorkObject(work),
              key)
    BY PrefixAndFinalChooseSameCell
<1>. QED BY <1>1 DEF MaterializedInput

=============================================================================
