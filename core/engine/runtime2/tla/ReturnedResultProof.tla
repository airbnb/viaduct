--------------------- MODULE ReturnedResultProof ---------------------
EXTENDS ReturnedResult, OccurrenceFoldsProof, ProjectionProof,
        TreeConstructionProof

ASSUME ReturnedResultAssumptions == ReturnedResultBaseWorld

LEMMA ReturnedCellsAreFinal ==
    AllFoldsCompleted => BuiltCells = PresentCells
BY CompletedFoldAlignsWithOutput
   DEF AllFoldsCompleted, OutputAlignment

LEMMA ReturnedReachabilityIsFinal ==
    AllFoldsCompleted =>
        ReturnedTree!ReachableObjects = ReachableObjects
BY ReturnedCellsAreFinal, Isa
   DEF ReturnedTree!ReachableObjects,
       ReturnedTree!ObjectClosedSets,
       ReachableObjects, ObjectClosedSets

LEMMA ReturnedConformsToFragment ==
    AllFoldsCompleted => ReturnedTree!ConformsToFragment
BY ReturnedCellsAreFinal, ReturnedReachabilityIsFinal,
   CompletedResultRefinesFoldCompleted,
   CompletedFoldsConformToFragment
   DEF ReturnedTree!ConformsToFragment,
       ReturnedTree!PresentKeys, ConformsToFragment, PresentKeys

LEMMA ReturnedClosesResolverDemand ==
    AllFoldsCompleted =>
        ReturnedTree!IsClosedUnderResolverDemand
BY ReturnedCellsAreFinal, CompletedResultRefinesFoldCompleted,
   CompletedFoldsCloseResolverDemand
   DEF ReturnedTree!IsClosedUnderResolverDemand,
       ReturnedTree!ActiveResolverCells,
       ReturnedTree!PresentKeys,
       IsClosedUnderResolverDemand, ActiveResolverCells,
       PresentKeys

LEMMA ReturnedConformsToResolvers ==
    ReturnedProjectionCoverage =>
        ReturnedTree!ConformsToResolvers
<1>. SUFFICES
        ASSUME ReturnedProjectionCoverage,
               NEW observation \in Observations
        PROVE
            ProjectedActualObservation[observation] =
                RawExpectedObservation[observation]
    BY DEF ReturnedTree!ConformsToResolvers
<1>1. ResultObservation[observation]
          \in DOMAIN
                Project(
                    SuppliedDemand[
                        ObservationResolver[observation]])
    BY DEF ReturnedProjectionCoverage
<1>. QED BY <1>1 DEF ProjectedActualObservation,
                    RawExpectedObservation, Project

LEMMA ReturnedConformsToTypename ==
    ReturnedTree!ConformsToTypename
BY ReturnedResultAssumptions
   DEF ReturnedResultBaseWorld, ReturnedTree!ConformsToTypename,
       ConformsToTypename

THEOREM CompletedReturnedResultIsCorrect ==
    AllFoldsCompleted /\ ReturnedProjectionCoverage =>
        ReturnedCorrect
BY ReturnedConformsToFragment, ReturnedClosesResolverDemand,
   ReturnedConformsToResolvers, ReturnedConformsToTypename,
   ReturnedResultAssumptions
   DEF ReturnedCorrect, ReturnedTree!CorrectResolution,
       ReturnedTree!RootedAndWellTyped, ReturnedResultBaseWorld,
       FoldWorld, ConstructionWorld, World,
       RootedAndWellTyped

=============================================================================
