------------------ MODULE Resolver03ProjectionProof ------------------
EXTENDS Resolver03Projection, ProjectionProof

ASSUME Resolver03ProjectionAssumptions ==
    Resolver03ProjectionWorld

THEOREM GuardedExtensionSuppliesObservedDemand ==
    Resolver03ProjectionCoverage
<1>. SUFFICES
        ASSUME NEW observation \in Observations
        PROVE
            ResultObservation[observation]
                \in DOMAIN
                      Project(
                          SuppliedDemand[
                              ObservationResolver[observation]])
    BY DEF Resolver03ProjectionCoverage
<1> DEFINE
        cell == ObservationResolver[observation]
        producer == CellProducer[cell]
        requirement == ObservationRequirement[observation]
        outputObservation == ResultObservation[observation]
<1>1. /\ requirement
             \in SelectionCoverage[outputObservation]
      /\ (\/ requirement \in DirectOutputDemand[producer]
          \/ \E occurrence \in ActivatedNested :
                /\ OwnerProducer[occurrence] = producer
                /\ requirement
                      \in RequiredByOccurrence[occurrence])
    BY Resolver03ProjectionAssumptions
       DEF Resolver03ProjectionWorld
<1>2. requirement
          \in Core03!ProducerSuppliedDemand(producer)
    <2> CASE requirement \in DirectOutputDemand[producer]
        <3>. QED
            BY DEF Core03!ProducerSuppliedDemand
    <2> CASE
            \E occurrence \in ActivatedNested :
                /\ OwnerProducer[occurrence] = producer
                /\ requirement
                      \in RequiredByOccurrence[occurrence]
        <3>1. PICK occurrence \in ActivatedNested :
                  /\ OwnerProducer[occurrence] = producer
                  /\ requirement
                        \in RequiredByOccurrence[occurrence]
            OBVIOUS
        <3>2. requirement
                  \in ExtendedByOccurrence[occurrence]
            BY <3>1, Resolver03ProjectionAssumptions
               DEF Resolver03ProjectionWorld,
                   Core03!Resolver03World
        <3>3. requirement \in Requirements
            BY Resolver03ProjectionAssumptions
               DEF Resolver03ProjectionWorld
        <3>4. \E nested \in ActivatedNested :
                  /\ OwnerProducer[nested] = producer
                  /\ requirement
                        \in ExtendedByOccurrence[nested]
            BY <3>1, <3>2
        <3>5. requirement
                  \in Core03!AggregatedExtendedDemand(producer)
            BY <3>3, <3>4
               DEF Core03!AggregatedExtendedDemand
        <3>. QED BY <3>5
                    DEF Core03!ProducerSuppliedDemand
    <2>. QED BY <1>1
<1>3. cell \in ActiveResolverCells
    BY Resolver03ProjectionAssumptions
       DEF Resolver03ProjectionWorld, ValueConstructionWorld,
           ConstructionWorld, World, WorldObservations,
           cell
<1>4. SuppliedDemand[cell] =
          Core03!ProducerSuppliedDemand(producer)
    BY <1>3, Resolver03ProjectionAssumptions
       DEF Resolver03ProjectionWorld, cell, producer
<1>5. requirement
          \in SelectionCoverage[outputObservation]
                 \cap SuppliedDemand[cell]
    BY <1>1, <1>2, <1>4
<1>6. outputObservation \in PassiveObservations
    BY Resolver03ProjectionAssumptions
       DEF Resolver03ProjectionWorld, ValueConstructionWorld
<1>. QED BY <1>5, <1>6
          DEF Project, RetainedObservations,
              cell, outputObservation, requirement

=============================================================================
