------------------------ MODULE Materialization ------------------------
EXTENDS OccurrenceFolds

(*
Finite observation of EngineResult.Object.materialize. A resolver work item
reads exactly its direct input keys from the already-built prefix of its
containing object. CellValue denotes the immutable structural value stored in
each cell, including any recursively constructed object or list descendants.
*)

CONSTANT CellValue

InputKeys(work) ==
    DirectDemandByKey[WorkObject(work)][WorkKey(work)]

EarlierCells(work) ==
    {WorkCell[earlier] : earlier \in EarlierWork(work)}

CellForKey(cells, object, key) ==
    CHOOSE cell \in cells :
        /\ CellObject[cell] = object
        /\ CellKey[cell] = key

MaterializedInput(cells, work) ==
    [key \in InputKeys(work) |->
        CellValue[
            CellForKey(cells, WorkObject(work), key)]]

MaterializationWorld ==
    /\ FoldWorld
    /\ CellValue \in [Cells -> Values]

MaterializedInputsStable ==
    \A work \in WorkItems :
        MaterializedInput(EarlierCells(work), work) =
            MaterializedInput(PresentCells, work)

=============================================================================
