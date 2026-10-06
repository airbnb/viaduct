------------------- MODULE Resolver03ProjectionMC -------------------
EXTENDS ValueConstructionMC, Resolver03Projection

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

R3MCvars == <<coreN, checked>>

R3MCInit ==
    /\ Core03!Init
    /\ checked = TRUE

R3MCNext ==
    /\ Core03!Next
    /\ UNCHANGED checked

R3MCSpec ==
    R3MCInit
        /\ [][R3MCNext]_R3MCvars
        /\ WF_R3MCvars(R3MCNext)

=============================================================================
