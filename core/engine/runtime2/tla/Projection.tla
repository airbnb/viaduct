-------------------------- MODULE Projection --------------------------
EXTENDS FiniteSets

(*
Finite observation semantics for snipToDemand. An observation atom identifies
one result-shape, list-position, scalar, or passive object-field fact in a
selection-independent raw resolver output. SelectionCoverage preserves the
complete concrete path, type guard, exact key, and arguments that can demand
that observation. Behavioral observations are boundaries and are never copied
from the owning resolver's raw output.
*)

CONSTANTS
    OutputObservations, DemandTokens, ObservationValues,
    PassiveObservations, BehavioralObservations,
    SelectionCoverage, RawObservationValue

ProjectionWorld ==
    /\ IsFiniteSet(OutputObservations)
    /\ IsFiniteSet(DemandTokens)
    /\ PassiveObservations \subseteq OutputObservations
    /\ BehavioralObservations \subseteq OutputObservations
    /\ PassiveObservations \cap BehavioralObservations = {}
    /\ PassiveObservations \cup BehavioralObservations =
           OutputObservations
    /\ SelectionCoverage
           \in [OutputObservations -> SUBSET DemandTokens]
    /\ RawObservationValue
           \in [OutputObservations -> ObservationValues]

RetainedObservations(demand) ==
    {observation \in PassiveObservations :
        SelectionCoverage[observation] \cap demand # {}}

Project(demand) ==
    [observation \in RetainedObservations(demand) |->
        RawObservationValue[observation]]

ProjectionSuppliesDemand(demand) ==
    /\ DOMAIN Project(demand) = RetainedObservations(demand)
    /\ \A observation \in DOMAIN Project(demand) :
           Project(demand)[observation] =
               RawObservationValue[observation]

ProjectionsAgreeOnOverlap(firstDemand, secondDemand) ==
    \A observation \in
            DOMAIN Project(firstDemand)
                \cap DOMAIN Project(secondDemand) :
        Project(firstDemand)[observation] =
            Project(secondDemand)[observation]

=============================================================================
