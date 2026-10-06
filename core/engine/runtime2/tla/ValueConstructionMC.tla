---------------------- MODULE ValueConstructionMC ----------------------
EXTENDS TreeConstructionMC, ValueConstruction

MCOutputObservations ==
    {"raw-user-shape", "raw-user-name", "raw-nested-resolver"}

MCDemandTokens ==
    {"select-user", "select-name", "select-nested"}

MCObservationValues == MCValues

MCPassiveObservations ==
    {"raw-user-shape", "raw-user-name"}

MCBehavioralObservations == {"raw-nested-resolver"}

MCSelectionCoverage ==
    [observation \in MCOutputObservations |->
        CASE observation = "raw-user-shape" ->
                 {"select-user", "select-name", "select-nested"}
          [] observation = "raw-user-name" -> {"select-name"}
          [] OTHER                        -> {"select-nested"}]

MCRawObservationValue ==
    [observation \in MCOutputObservations |->
        CASE observation = "raw-user-shape" -> "UserObject"
          [] observation = "raw-user-name"  -> "Raymie"
          [] OTHER                          -> "UserObject"]

MCResultObservation ==
    [observation \in MCObservations |->
        IF observation = "user-shape"
        THEN "raw-user-shape"
        ELSE "raw-user-name"]

MCSuppliedDemand ==
    [cell \in MCCells |->
        IF cell = "q-user"
        THEN {"select-user", "select-name"}
        ELSE {}]

=============================================================================
