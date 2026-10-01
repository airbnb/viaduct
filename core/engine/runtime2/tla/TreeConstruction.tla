----------------------- MODULE TreeConstruction -----------------------
EXTENDS ResultTree

(*
Occurrence-indexed version of ResolverCore's least demand closure. It gives
every finite object occurrence its own exact-key universe and direct
argument-dependent resolver demand while retaining one extensional result
tree. The fold implementation is connected by FoldCompleted: the keys
actually present at an occurrence are exactly its least closed demand.
*)

CONSTANTS ResolverKeyUniverse, DirectDemandByKey

ClosedSetsAt(object) ==
    {demand \in SUBSET Keys :
        /\ OperationDemand[object] \subseteq demand
        /\ \A key \in demand \cap ResolverKeyUniverse[object] :
               DirectDemandByKey[object][key] \subseteq demand}

ClosedDemandAt(object) ==
    {key \in Keys :
        \A demand \in ClosedSetsAt(object) : key \in demand}

ConstructionWorld ==
    /\ World
    /\ ResolverKeyUniverse \in [Objects -> SUBSET Keys]
    /\ DirectDemandByKey \in [Objects -> [Keys -> SUBSET Keys]]
    /\ \A object \in Objects :
           \A key \in Keys :
               DirectDemandByKey[object][key] \subseteq Keys
    /\ \A object \in Objects :
           \A key \in Keys \ ResolverKeyUniverse[object] :
               DirectDemandByKey[object][key] = {}
    /\ \A cell \in ActiveResolverCells :
           /\ CellKey[cell] \in ResolverKeyUniverse[CellObject[cell]]
           /\ ResolverDemand[cell] =
                  DirectDemandByKey[CellObject[cell]][CellKey[cell]]

FoldCompleted ==
    \A object \in ReachableObjects :
        PresentKeys(object) = ClosedDemandAt(object)

NoObjectFragments ==
    \A object \in Objects :
        \A key \in ResolverKeyUniverse[object] :
            DirectDemandByKey[object][key] = {}

ValueConstructionCorrect ==
    /\ RootedAndWellTyped
    /\ ConformsToResolvers
    /\ ConformsToTypename

Resolver01Constructed ==
    /\ FoldCompleted
    /\ NoObjectFragments
    /\ ValueConstructionCorrect

Resolver02Constructed ==
    /\ FoldCompleted
    /\ ValueConstructionCorrect

=============================================================================
