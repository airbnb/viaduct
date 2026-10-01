----------------------- MODULE MaterializationMC -----------------------
EXTENDS ResultTreeMC, Materialization

MCMaterialResolverDemand ==
    [cell \in MCCells |->
        IF cell = "q-user" THEN {"__typename"} ELSE {}]

MCMaterialResolverKeyUniverse ==
    [object \in MCObjects |->
        IF object = "query" THEN {"user"} ELSE {}]

MCMaterialDirectDemand ==
    [object \in MCObjects |->
        [key \in MCKeys |->
            IF object = "query" /\ key = "user"
            THEN {"__typename"}
            ELSE {}]]

MCMaterialOrder ==
    [object \in MCObjects |->
        IF object = "query"
        THEN <<"__typename", "user">>
        ELSE <<"name", "__typename">>]

MCMaterialWorkItems ==
    {<<"query", 1>>, <<"query", 2>>,
     <<"user-0", 1>>, <<"user-0", 2>>}

MCMaterialWorkCell ==
    [work \in MCMaterialWorkItems |->
        CASE work = <<"query", 1>>  -> "q-typename"
          [] work = <<"query", 2>>  -> "q-user"
          [] work = <<"user-0", 1>> -> "u-name"
          [] OTHER                   -> "u-typename"]

MCCellValue == MCActualCellValue

MaterialMCvars == <<remainingWork, n, checked>>

MaterialMCInit ==
    /\ FoldInit
    /\ checked = TRUE

MaterialMCNext ==
    /\ FoldNext
    /\ UNCHANGED checked

MaterialMCSpec ==
    MaterialMCInit
        /\ [][MaterialMCNext]_MaterialMCvars
        /\ WF_MaterialMCvars(MaterialMCNext)

=============================================================================
