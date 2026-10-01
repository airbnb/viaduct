------------------------- MODULE ProjectionMC -------------------------
EXTENDS Projection

MCOutputObservations ==
    {"object-shape", "passive-name", "nested-resolver"}

MCDemandTokens == {"select-object", "select-name", "select-nested"}

MCObservationValues == {"User", "Raymie", "nested-output"}

MCPassiveObservations == {"object-shape", "passive-name"}
MCBehavioralObservations == {"nested-resolver"}

MCSelectionCoverage ==
    [observation \in MCOutputObservations |->
        CASE observation = "object-shape" ->
                 {"select-object", "select-name", "select-nested"}
          [] observation = "passive-name" -> {"select-name"}
          [] OTHER                        -> {"select-nested"}]

MCRawObservationValue ==
    [observation \in MCOutputObservations |->
        CASE observation = "object-shape" -> "User"
          [] observation = "passive-name" -> "Raymie"
          [] OTHER                        -> "nested-output"]

MCFirstDemand == {"select-object", "select-name"}
MCSecondDemand == {"select-name", "select-nested"}

MCExpectedFirstDomain ==
    DOMAIN Project(MCFirstDemand) =
        {"object-shape", "passive-name"}

MCExpectedSecondDomain ==
    DOMAIN Project(MCSecondDemand) =
        {"object-shape", "passive-name"}

MCProjectionCorrect ==
    /\ ProjectionSuppliesDemand(MCFirstDemand)
    /\ ProjectionSuppliesDemand(MCSecondDemand)

MCStopsAtBehavioralBoundaries ==
    /\ DOMAIN Project(MCFirstDemand)
           \cap MCBehavioralObservations = {}
    /\ DOMAIN Project(MCSecondDemand)
           \cap MCBehavioralObservations = {}

MCProjectionCoherence ==
    ProjectionsAgreeOnOverlap(MCFirstDemand, MCSecondDemand)

VARIABLE checked

MCvars == <<checked>>
MCInit == checked = TRUE
MCNext == UNCHANGED MCvars
MCSpec == MCInit /\ [][MCNext]_MCvars

=============================================================================
