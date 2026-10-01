------------------- MODULE Resolver03ApplicationMC -------------------
EXTENDS ResolverApplicationMC, Resolver03Application

MCR3ResolverKeys == {"user"}
MCR3InitialDemand == {"user"}
MCR3DirectDemand ==
    [key \in MCKeys |->
        IF key = "user" THEN {"__typename"} ELSE {}]
MCR3ConstructionOrder == <<"__typename", "user">>

MCR3Requirements == MCDemandTokens
MCR3NestedOccurrences == {"nested-name"}
MCR3ActivatedNested == MCR3NestedOccurrences
MCR3OwnerProducer ==
    [occurrence \in MCR3NestedOccurrences |-> "user"]
MCR3RequiredByOccurrence ==
    [occurrence \in MCR3NestedOccurrences |-> {"select-name"}]
MCR3ExtendedByOccurrence == MCR3RequiredByOccurrence
MCR3DirectOutputDemand ==
    [producer \in MCR3ResolverKeys |-> {"select-user"}]
MCR3OEROccurrences == MCObjects
MCR3LocalOneApplication ==
    [object \in MCR3OEROccurrences |-> TRUE]

MCR3CellProducer ==
    [cell \in MCCells |-> "user"]

MCR3ObservationRequirement ==
    [observation \in MCObservations |->
        IF observation = "user-shape"
        THEN "select-user"
        ELSE "select-name"]

R3AppVars == <<remainingWork, n, checked, coreN>>

R3AppInit ==
    /\ MaterialMCInit
    /\ Core03!Init

R3AppNext ==
    \/ /\ MaterialMCNext
       /\ UNCHANGED coreN
    \/ /\ Core03!Next
       /\ UNCHANGED MaterialMCvars

R3AppSpec ==
    R3AppInit
        /\ [][R3AppNext]_R3AppVars
        /\ WF_R3AppVars(R3AppNext)

=============================================================================
