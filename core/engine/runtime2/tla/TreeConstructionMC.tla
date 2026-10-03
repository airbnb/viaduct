---------------------- MODULE TreeConstructionMC ----------------------
EXTENDS ResultTreeMC, TreeConstruction

MCResolverKeyUniverse ==
    [object \in MCObjects |->
        IF object = "query" THEN {"user"} ELSE {}]

MCDirectDemandByKey ==
    [object \in MCObjects |->
        [key \in MCKeys |-> {}]]

=============================================================================
