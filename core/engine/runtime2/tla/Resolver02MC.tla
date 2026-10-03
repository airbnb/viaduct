-------------------------- MODULE Resolver02MC --------------------------
EXTENDS ResolverCore

MCKeys == {"source", "consumer", "helper", "leaf"}
MCResolverKeys == {"source", "consumer", "helper"}
MCInitialDemand == {"source", "consumer"}
MCDirectDemand ==
    [k \in MCKeys |->
        CASE k = "consumer" -> {"helper"}
          [] k = "helper"   -> {"leaf"}
          [] OTHER          -> {}]

MCConstructionOrder ==
    <<"source", "leaf", "helper", "consumer">>

MCObjectOccurrences == {"query", "nested-0", "nested-1"}
MCLocalClosed == [occurrence \in MCObjectOccurrences |-> TRUE]

ResolverDemandClosed ==
    \A k \in resolved \cap ResolverKeys :
        DirectDemand[k] \subseteq resolved

=============================================================================
