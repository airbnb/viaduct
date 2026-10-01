----------------------- MODULE ProjectionProof -----------------------
EXTENDS Projection, TLAPS

ASSUME ProjectionAssumptions == ProjectionWorld

THEOREM ProjectionDomain ==
    \A demand \in SUBSET DemandTokens :
        DOMAIN Project(demand) = RetainedObservations(demand)
BY DEF Project

THEOREM ProjectionUsesRawValues ==
    \A demand \in SUBSET DemandTokens :
        \A observation \in DOMAIN Project(demand) :
            Project(demand)[observation] =
                RawObservationValue[observation]
BY DEF Project

THEOREM ProjectionIsCompleteAndSound ==
    \A demand \in SUBSET DemandTokens :
        ProjectionSuppliesDemand(demand)
BY ProjectionDomain, ProjectionUsesRawValues
   DEF ProjectionSuppliesDemand

THEOREM ProjectionStopsAtBehavioralBoundaries ==
    \A demand \in SUBSET DemandTokens :
        DOMAIN Project(demand) \cap BehavioralObservations = {}
BY ProjectionAssumptions
   DEF ProjectionWorld, Project, RetainedObservations

THEOREM ProjectionIsMonotone ==
    \A firstDemand, secondDemand \in SUBSET DemandTokens :
        firstDemand \subseteq secondDemand
            => DOMAIN Project(firstDemand)
                   \subseteq DOMAIN Project(secondDemand)
BY DEF Project, RetainedObservations

THEOREM ProjectionCoherence ==
    \A firstDemand, secondDemand \in SUBSET DemandTokens :
        ProjectionsAgreeOnOverlap(firstDemand, secondDemand)
BY ProjectionUsesRawValues
   DEF ProjectionsAgreeOnOverlap

=============================================================================
