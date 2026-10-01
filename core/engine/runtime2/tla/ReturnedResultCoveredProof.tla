----------------- MODULE ReturnedResultCoveredProof -----------------
EXTENDS ReturnedResultProof

ASSUME ReturnedResultProjectionAssumption ==
    ReturnedProjectionCoverage

LEMMA CoveredReturnedResultIsCorrect ==
    AllFoldsCompleted => ReturnedCorrect
BY CompletedReturnedResultIsCorrect,
   ReturnedResultProjectionAssumption

THEOREM Resolver01And02ReturnedResultCorrect ==
    FoldSpec => ReturnedResultTermination
BY AllOccurrenceFoldsComplete, CoveredReturnedResultIsCorrect, PTL
   DEF FoldTermination, ReturnedResultTermination

=============================================================================
