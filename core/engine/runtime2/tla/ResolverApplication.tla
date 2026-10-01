---------------------- MODULE ResolverApplication ----------------------
EXTENDS ReturnedResult, Materialization

(*
The algorithm applies each resolver to the dependency-complete prefix; the
correctness judgment applies the same deterministic function to the final
OER. MaterializedInputs contains every finite input map relevant to this
model. ResolverFunction is selection-independent; projection remains the
separate Project operator from ReturnedResult.
*)

CONSTANTS OutputWork, ResolverFunction

MaterializedInputs ==
    {MaterializedInput(EarlierCells(work), work) :
        work \in WorkItems}
    \cup
    {MaterializedInput(PresentCells, work) :
        work \in WorkItems}

PrefixRawObservation(outputObservation) ==
    LET work == OutputWork[outputObservation]
    IN ResolverFunction[work][
           MaterializedInput(EarlierCells(work), work)][
               outputObservation]

FinalRawObservation(outputObservation) ==
    LET work == OutputWork[outputObservation]
    IN ResolverFunction[work][
           MaterializedInput(PresentCells, work)][
               outputObservation]

FinalExpectedObservation ==
    [observation \in Observations |->
        FinalRawObservation(ResultObservation[observation])]

AppliedTree ==
    INSTANCE ResultTree
        WITH PresentCells <- BuiltCells,
             ActualObservation <- ProjectedActualObservation,
             ExpectedObservation <- FinalExpectedObservation

ResolverApplicationBaseWorld ==
    /\ ReturnedResultBaseWorld
    /\ MaterializationWorld
    /\ OutputWork \in [OutputObservations -> WorkItems]
    /\ ResolverFunction
           \in [WorkItems ->
                [MaterializedInputs ->
                 [OutputObservations -> ObservationValues]]]
    /\ RawObservationValue =
           [outputObservation \in OutputObservations |->
               PrefixRawObservation(outputObservation)]
    /\ \A observation \in Observations :
           WorkCell[
               OutputWork[ResultObservation[observation]]]
               = ObservationResolver[observation]

ResolverApplicationWorld ==
    /\ ResolverApplicationBaseWorld
    /\ ReturnedProjectionCoverage

AppliedCorrect == AppliedTree!CorrectResolution

AppliedResultTermination ==
    <> (AllFoldsCompleted /\ AppliedCorrect)

=============================================================================
