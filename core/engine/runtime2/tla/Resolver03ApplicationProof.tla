----------------- MODULE Resolver03ApplicationProof -----------------
EXTENDS Resolver03Application, ResolverApplicationProof,
        Resolver03ProjectionProof

ASSUME Resolver03ApplicationAssumptions ==
    Resolver03ApplicationWorld

LEMMA Resolver03CoverageIsReturnedCoverage ==
    Resolver03ProjectionCoverage =>
        ReturnedProjectionCoverage
BY DEF Resolver03ProjectionCoverage,
       ReturnedProjectionCoverage

LEMMA Resolver03CompletedApplicationIsCorrect ==
    AllFoldsCompleted => AppliedCorrect
BY GuardedExtensionSuppliesObservedDemand,
   Resolver03CoverageIsReturnedCoverage,
   CompletedAppliedResultIsCorrect

THEOREM Resolver03ApplicationsAreCorrect ==
    FoldSpec => AppliedResultTermination
BY AllOccurrenceFoldsComplete,
   Resolver03CompletedApplicationIsCorrect, PTL
   DEF FoldTermination, AppliedResultTermination

=============================================================================
