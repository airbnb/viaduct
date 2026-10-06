-------------------------- MODULE Resolver03MC --------------------------
EXTENDS ResolverCore

MCKeys == {"producer", "bridge", "provider-resolver", "helper"}
MCResolverKeys == {"producer", "bridge", "provider-resolver"}
MCInitialDemand == {"producer"}
MCDirectDemand ==
    [k \in MCKeys |->
        CASE k = "producer" -> {"bridge"}
          [] OTHER          -> {}]
MCConstructionOrder == <<"bridge", "producer">>

MCRequirements == {"B/path/helper", "B/path/bridge"}
MCNestedOccurrences == {"nested-bridge"}
MCActivatedNested == MCNestedOccurrences
MCOwnerProducer == [occurrence \in MCNestedOccurrences |-> "producer"]
MCRequiredByOccurrence ==
    [occurrence \in MCNestedOccurrences |->
        {"B/path/helper", "B/path/bridge"}]
MCExtendedByOccurrence == MCRequiredByOccurrence
MCDirectOutputDemand ==
    [producer \in MCResolverKeys |->
        IF producer = "producer" THEN {"B/path/bridge"} ELSE {}]

AggregatedExtendedDemand(producer) ==
    {requirement \in MCRequirements :
        \E occurrence \in MCActivatedNested :
            /\ MCOwnerProducer[occurrence] = producer
            /\ requirement \in MCExtendedByOccurrence[occurrence]}

ProducerSuppliedDemand(producer) ==
    MCDirectOutputDemand[producer] \cup AggregatedExtendedDemand(producer)

ProducerComplete ==
    \A occurrence \in MCActivatedNested :
        MCRequiredByOccurrence[occurrence]
            \subseteq ProducerSuppliedDemand(
                MCOwnerProducer[occurrence])

=============================================================================
