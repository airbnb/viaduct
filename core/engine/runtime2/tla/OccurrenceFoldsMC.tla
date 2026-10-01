----------------------- MODULE OccurrenceFoldsMC -----------------------
EXTENDS TreeConstructionMC, OccurrenceFolds

MCConstructionOrderAt ==
    [object \in MCObjects |->
        IF object = "query"
        THEN <<"user", "__typename">>
        ELSE <<"name", "__typename">>]

MCWorkItems ==
    {<<"query", 1>>, <<"query", 2>>,
     <<"user-0", 1>>, <<"user-0", 2>>}

MCWorkCell ==
    [work \in MCWorkItems |->
        CASE work = <<"query", 1>>  -> "q-user"
          [] work = <<"query", 2>>  -> "q-typename"
          [] work = <<"user-0", 1>> -> "u-name"
          [] OTHER                   -> "u-typename"]

FoldMCvars ==
    <<remainingWork, n, checked>>

FoldMCInit ==
    /\ FoldInit
    /\ checked = TRUE

FoldMCNext ==
    /\ FoldNext
    /\ UNCHANGED checked

FoldMCSpec ==
    FoldMCInit
        /\ [][FoldMCNext]_FoldMCvars
        /\ WF_FoldMCvars(FoldMCNext)

=============================================================================
