------------------- MODULE ResolverApplicationProof -------------------
EXTENDS ResolverApplication, ReturnedResultProof,
        MaterializationProof

ASSUME ResolverApplicationAssumptions ==
    ResolverApplicationBaseWorld

THEOREM PrefixAndFinalRawOutputsAgree ==
    \A outputObservation \in OutputObservations :
        PrefixRawObservation(outputObservation) =
            FinalRawObservation(outputObservation)
<1>. SUFFICES
        ASSUME NEW outputObservation \in OutputObservations
        PROVE
            PrefixRawObservation(outputObservation) =
                FinalRawObservation(outputObservation)
    OBVIOUS
<1>1. OutputWork[outputObservation] \in WorkItems
    BY ResolverApplicationAssumptions
       DEF ResolverApplicationBaseWorld
<1>2. MaterializedInput(
          EarlierCells(OutputWork[outputObservation]),
          OutputWork[outputObservation])
       =
       MaterializedInput(
          PresentCells,
          OutputWork[outputObservation])
    BY <1>1, PrefixMaterializationEqualsFinalMaterialization
       DEF MaterializedInputsStable
<1>. QED BY <1>2
          DEF PrefixRawObservation, FinalRawObservation

LEMMA FinalExpectedMatchesProjectedRaw ==
    \A observation \in Observations :
        FinalExpectedObservation[observation] =
            RawExpectedObservation[observation]
<1>. SUFFICES
        ASSUME NEW observation \in Observations
        PROVE
            FinalExpectedObservation[observation] =
                RawExpectedObservation[observation]
    OBVIOUS
<1>1. ResultObservation[observation]
          \in OutputObservations
    BY ResolverApplicationAssumptions
       DEF ResolverApplicationBaseWorld, ReturnedResultBaseWorld
<1>2. FinalRawObservation(ResultObservation[observation])
       =
       PrefixRawObservation(ResultObservation[observation])
    BY <1>1, PrefixAndFinalRawOutputsAgree
<1>3. RawObservationValue[ResultObservation[observation]]
       =
       PrefixRawObservation(ResultObservation[observation])
    BY <1>1, ResolverApplicationAssumptions
       DEF ResolverApplicationBaseWorld
<1>. QED BY <1>2, <1>3
          DEF FinalExpectedObservation, RawExpectedObservation

LEMMA ReturnedResolverConformanceImpliesApplied ==
    ReturnedTree!ConformsToResolvers =>
        AppliedTree!ConformsToResolvers
<1>. SUFFICES
        ASSUME ReturnedTree!ConformsToResolvers,
               NEW observation \in Observations
        PROVE
            ProjectedActualObservation[observation] =
                FinalExpectedObservation[observation]
    BY DEF AppliedTree!ConformsToResolvers
<1>1. ProjectedActualObservation[observation] =
          RawExpectedObservation[observation]
    BY DEF ReturnedTree!ConformsToResolvers
<1>2. FinalExpectedObservation[observation] =
          RawExpectedObservation[observation]
    BY FinalExpectedMatchesProjectedRaw
<1>. QED BY <1>1, <1>2

LEMMA CompletedAppliedResultIsCorrect ==
    AllFoldsCompleted /\ ReturnedProjectionCoverage =>
        AppliedCorrect
<1>. SUFFICES
        ASSUME AllFoldsCompleted,
               ReturnedProjectionCoverage
        PROVE AppliedCorrect
    OBVIOUS
<1>1. ReturnedCorrect
    BY CompletedReturnedResultIsCorrect
<1>2. /\ ReturnedTree!RootedAndWellTyped
      /\ ReturnedTree!ConformsToFragment
      /\ ReturnedTree!IsClosedUnderResolverDemand
      /\ ReturnedTree!ConformsToResolvers
      /\ ReturnedTree!ConformsToTypename
    BY <1>1
       DEF ReturnedCorrect, ReturnedTree!CorrectResolution
<1>3. AppliedTree!RootedAndWellTyped
    BY <1>2
       DEF AppliedTree!RootedAndWellTyped,
           ReturnedTree!RootedAndWellTyped
<1>4. AppliedTree!ConformsToFragment
    BY <1>2, Isa
       DEF AppliedTree!ConformsToFragment,
           AppliedTree!ReachableObjects,
           AppliedTree!ObjectClosedSets,
           AppliedTree!PresentKeys,
           ReturnedTree!ConformsToFragment,
           ReturnedTree!ReachableObjects,
           ReturnedTree!ObjectClosedSets,
           ReturnedTree!PresentKeys
<1>5. AppliedTree!IsClosedUnderResolverDemand
    BY <1>2, Isa
       DEF AppliedTree!IsClosedUnderResolverDemand,
           AppliedTree!ActiveResolverCells,
           AppliedTree!PresentKeys,
           ReturnedTree!IsClosedUnderResolverDemand,
           ReturnedTree!ActiveResolverCells,
           ReturnedTree!PresentKeys
<1>6. AppliedTree!ConformsToResolvers
    BY <1>2, ReturnedResolverConformanceImpliesApplied
<1>7. AppliedTree!ConformsToTypename
    BY <1>2
       DEF AppliedTree!ConformsToTypename,
           ReturnedTree!ConformsToTypename
<1>. QED BY <1>3, <1>4, <1>5, <1>6, <1>7
          DEF AppliedCorrect, AppliedTree!CorrectResolution

=============================================================================
