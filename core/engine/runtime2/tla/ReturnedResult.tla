------------------------ MODULE ReturnedResult ------------------------
EXTENDS OccurrenceFolds, ValueConstruction

(*
Source-to-judgment refinement for the value returned by Resolver01/02.
Each completed work item contributes the one cell returned by resolveKey;
BuiltCells is therefore the object-union fold result. Resolver outputs are
the finite Project operator from snipToDemand. Resolver behavior supplies
canonical typename as ordinary passive output before projection.

The named ResultTree instance substitutes those constructed operators for
the abstract result carriers. This avoids assuming FoldCompleted,
ProjectionAlignment, or typename correctness as independent postconditions of
the resolver.
*)

ProjectedActualObservation ==
    [observation \in Observations |->
        LET cell == ObservationResolver[observation]
            outputObservation == ResultObservation[observation]
            demand == SuppliedDemand[cell]
        IN  Project(demand)[outputObservation]]

RawExpectedObservation ==
    [observation \in Observations |->
        RawObservationValue[ResultObservation[observation]]]

ReturnedTree ==
    INSTANCE ResultTree
        WITH PresentCells <- BuiltCells,
             ActualObservation <- ProjectedActualObservation,
             ExpectedObservation <- RawExpectedObservation

ReturnedResultBaseWorld ==
    /\ FoldWorld
    /\ ProjectionWorld
    /\ RootedAndWellTyped
    /\ ObservationValues = Values
    /\ ResultObservation
           \in [Observations -> OutputObservations]
    /\ SuppliedDemand \in [Cells -> SUBSET DemandTokens]
    /\ \A observation \in Observations :
           ResultObservation[observation]
               \in PassiveObservations
    /\ ConformsToTypename

ReturnedProjectionCoverage ==
    \A observation \in Observations :
        ResultObservation[observation]
            \in DOMAIN
                  Project(
                      SuppliedDemand[
                          ObservationResolver[observation]])

ReturnedResultWorld ==
    /\ ReturnedResultBaseWorld
    /\ ReturnedProjectionCoverage

ReturnedCorrect == ReturnedTree!CorrectResolution

ReturnedResultTermination ==
    <> (AllFoldsCompleted /\ ReturnedCorrect)

=============================================================================
