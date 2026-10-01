--------------------- MODULE ResolverApplicationMC ---------------------
EXTENDS MaterializationMC, ValueConstructionMC,
        ResolverApplication

MCOutputWork ==
    [outputObservation \in MCOutputObservations |->
        <<"query", 2>>]

MCResolverFunction ==
    [work \in WorkItems |->
        [input \in MaterializedInputs |->
            [outputObservation \in MCOutputObservations |->
                MCRawObservationValue[outputObservation]]]]

=============================================================================
