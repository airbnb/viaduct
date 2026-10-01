--------------------- MODULE Resolver03Projection ---------------------
EXTENDS ValueConstruction

(*
Connects Resolver03's opaque guarded requirement tokens to the finite
snipToDemand observation semantics. ObservationRequirement chooses one exact
path/type/key/argument token whose coverage demands each result observation.
It is either direct producer demand or belongs to an activated nested
occurrence covered by that occurrence's exact extended fragment.
*)

CONSTANTS
    ResolverKeys, InitialDemand, DirectDemand, ConstructionOrder,
    Requirements, NestedOccurrences, ActivatedNested,
    OwnerProducer, RequiredByOccurrence, ExtendedByOccurrence,
    DirectOutputDemand, OEROccurrences, LocalOneApplication,
    CellProducer, ObservationRequirement

VARIABLE coreN

Core03 ==
    INSTANCE Resolver03
        WITH Keys <- Keys,
             ResolverKeys <- ResolverKeys,
             InitialDemand <- InitialDemand,
             DirectDemand <- DirectDemand,
             ConstructionOrder <- ConstructionOrder,
             n <- coreN,
             Requirements <- Requirements,
             NestedOccurrences <- NestedOccurrences,
             ActivatedNested <- ActivatedNested,
             OwnerProducer <- OwnerProducer,
             RequiredByOccurrence <- RequiredByOccurrence,
             ExtendedByOccurrence <- ExtendedByOccurrence,
             DirectOutputDemand <- DirectOutputDemand,
             OEROccurrences <- OEROccurrences,
             LocalOneApplication <- LocalOneApplication

Resolver03ProjectionWorld ==
    /\ Core03!Resolver03World
    /\ ValueConstructionWorld
    /\ Requirements = DemandTokens
    /\ CellProducer \in [Cells -> ResolverKeys]
    /\ ObservationRequirement
           \in [Observations -> Requirements]
    /\ \A cell \in ActiveResolverCells :
           SuppliedDemand[cell] =
               Core03!ProducerSuppliedDemand(
                   CellProducer[cell])
    /\ \A observation \in Observations :
           LET cell == ObservationResolver[observation]
               producer == CellProducer[cell]
               requirement ==
                   ObservationRequirement[observation]
           IN
           /\ requirement
                  \in SelectionCoverage[
                      ResultObservation[observation]]
           /\ (\/ requirement
                     \in DirectOutputDemand[producer]
               \/ \E occurrence \in ActivatedNested :
                     /\ OwnerProducer[occurrence] = producer
                     /\ requirement
                           \in RequiredByOccurrence[occurrence])

Resolver03ProjectionCoverage ==
    \A observation \in Observations :
        ResultObservation[observation]
            \in DOMAIN
                  Project(
                      SuppliedDemand[
                          ObservationResolver[observation]])

=============================================================================
