----------------------- MODULE ValueConstruction -----------------------
EXTENDS TreeConstruction, Projection

(*
Refinement of Resolver01's value-producing branches into ResultTree.
ResultObservation maps each passive observation made by conformsToResolvers
to the corresponding observation in the resolver's raw output. The algorithm
stores the projected value; the correctness judgment independently compares
against that same raw value.
*)

CONSTANTS ResultObservation, SuppliedDemand

ValueConstructionWorld ==
    /\ ConstructionWorld
    /\ ProjectionWorld
    /\ ObservationValues = Values
    /\ ResultObservation
           \in [Observations -> OutputObservations]
    /\ SuppliedDemand \in [Cells -> SUBSET DemandTokens]
    /\ \A observation \in Observations :
           ResultObservation[observation] \in PassiveObservations

ProjectionAlignment ==
    \A observation \in Observations :
        LET cell == ObservationResolver[observation]
            outputObservation == ResultObservation[observation]
            demand == SuppliedDemand[cell]
        IN  /\ outputObservation \in DOMAIN Project(demand)
            /\ ActualObservation[observation] =
                   Project(demand)[outputObservation]
            /\ ExpectedObservation[observation] =
                   RawObservationValue[outputObservation]

Resolver01ValueConstruction ==
    /\ RootedAndWellTyped
    /\ ProjectionAlignment
    /\ ConformsToTypename

Resolver01FullConstruction ==
    /\ FoldCompleted
    /\ NoObjectFragments
    /\ Resolver01ValueConstruction

Resolver02FullConstruction ==
    /\ FoldCompleted
    /\ Resolver01ValueConstruction

Resolver03FullConstruction == Resolver02FullConstruction

=============================================================================
