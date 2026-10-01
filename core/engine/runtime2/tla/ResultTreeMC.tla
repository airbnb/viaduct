------------------------- MODULE ResultTreeMC -------------------------
EXTENDS ResultTree

MCObjects == {"query", "user-0"}
MCCells == {"q-user", "q-typename", "u-name", "u-typename"}
MCKeys == {"user", "name", "__typename"}
MCTypes == {"Query", "User"}
MCValues == {"UserObject", "Raymie", "Query", "User"}
MCObservations == {"user-shape", "user-name"}

MCRoot == "query"
MCQueryType == "Query"
MCFragmentRootType == "Query"

MCPresentCells == MCCells

MCCellObject ==
    [cell \in MCCells |->
        CASE cell \in {"q-user", "q-typename"} -> "query"
          [] OTHER                             -> "user-0"]

MCCellKey ==
    [cell \in MCCells |->
        CASE cell = "q-user" -> "user"
          [] cell = "u-name" -> "name"
          [] OTHER           -> "__typename"]

MCCellChildren ==
    [cell \in MCCells |->
        IF cell = "q-user" THEN {"user-0"} ELSE {}]

MCObjectType ==
    [object \in MCObjects |->
        IF object = "query" THEN "Query" ELSE "User"]

MCOperationDemand ==
    [object \in MCObjects |->
        IF object = "query"
        THEN {"user", "__typename"}
        ELSE {"name", "__typename"}]

MCResolverCells == {"q-user"}
MCErrorCells == {}
MCResolverDemand == [cell \in MCCells |-> {}]

MCObservationResolver ==
    [observation \in MCObservations |-> "q-user"]

MCActualObservation ==
    [observation \in MCObservations |->
        IF observation = "user-shape" THEN "UserObject" ELSE "Raymie"]

MCExpectedObservation == MCActualObservation

MCTypenameCells == {"q-typename", "u-typename"}

MCActualCellValue ==
    [cell \in MCCells |->
        CASE cell = "q-user"     -> "UserObject"
          [] cell = "u-name"     -> "Raymie"
          [] cell = "q-typename" -> "Query"
          [] OTHER               -> "User"]

MCTypeNameValue ==
    [object \in MCObjects |-> MCObjectType[object]]

VARIABLE checked

MCvars == <<checked>>

MCInit == checked = TRUE

MCNext == UNCHANGED MCvars

MCSpec == MCInit /\ [][MCNext]_MCvars

=============================================================================
