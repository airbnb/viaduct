------------- MODULE Resolver01And02ApplicationProof -------------
EXTENDS ResolverApplicationProof

ASSUME Resolver01And02ProjectionAssumption ==
    ReturnedProjectionCoverage

LEMMA CoveredAppliedResultIsCorrect ==
    AllFoldsCompleted => AppliedCorrect
BY CompletedAppliedResultIsCorrect,
   Resolver01And02ProjectionAssumption

THEOREM Resolver01And02ApplicationsAreCorrect ==
    FoldSpec => AppliedResultTermination
BY AllOccurrenceFoldsComplete, CoveredAppliedResultIsCorrect, PTL
   DEF FoldTermination, AppliedResultTermination

==================================================================
