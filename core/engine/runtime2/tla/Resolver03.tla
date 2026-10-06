-------------------------- MODULE Resolver03 --------------------------
EXTENDS ResolverCoreProof

(*
Requirement tokens retain their complete guarded identity: containing-object
path, concrete-type branch, exact key, and argument tuple. Treating them as
opaque values prevents an extension from proving coverage by dropping a guard
or retargeting an argument tuple.
*)

CONSTANTS Requirements, NestedOccurrences, ActivatedNested,
          OwnerProducer, RequiredByOccurrence, ExtendedByOccurrence,
          DirectOutputDemand

AggregatedExtendedDemand(producer) ==
    {requirement \in Requirements :
        \E occurrence \in ActivatedNested :
            /\ OwnerProducer[occurrence] = producer
            /\ requirement \in ExtendedByOccurrence[occurrence]}

ProducerSuppliedDemand(producer) ==
    DirectOutputDemand[producer] \cup AggregatedExtendedDemand(producer)

Resolver03World ==
    /\ IsFiniteSet(Requirements)
    /\ IsFiniteSet(NestedOccurrences)
    /\ ActivatedNested \subseteq NestedOccurrences
    /\ OwnerProducer \in [NestedOccurrences -> ResolverKeys]
    /\ RequiredByOccurrence
           \in [NestedOccurrences -> SUBSET Requirements]
    /\ ExtendedByOccurrence
           \in [NestedOccurrences -> SUBSET Requirements]
    /\ DirectOutputDemand \in [ResolverKeys -> SUBSET Requirements]
    /\ \A occurrence \in NestedOccurrences :
           RequiredByOccurrence[occurrence]
               \subseteq ExtendedByOccurrence[occurrence]

ASSUME Resolver03Assumptions == Resolver03World

GuardedProducerComplete ==
    \A occurrence \in ActivatedNested :
        RequiredByOccurrence[occurrence]
            \subseteq ProducerSuppliedDemand(
                OwnerProducer[occurrence])

THEOREM ExtendedDemandIsProducerComplete ==
    GuardedProducerComplete
BY Resolver03Assumptions, Zenon
   DEF Resolver03World, GuardedProducerComplete,
       ProducerSuppliedDemand, AggregatedExtendedDemand

Resolver03Correct ==
    /\ Done
    /\ ResultCorrect
    /\ GuardedProducerComplete

THEOREM Resolver03LocalCorrectness ==
    Spec => <>Resolver03Correct
BY ResolverCoreCorrectness, ExtendedDemandIsProducerComplete, PTL
   DEF Resolver03Correct

(*
resolveValue creates an independent core instance for every object and list
element. Structural equality never identifies two members of OEROccurrences.
*)
CONSTANTS OEROccurrences, LocalOneApplication

OccurrenceWorld ==
    /\ IsFiniteSet(OEROccurrences)
    /\ LocalOneApplication \in [OEROccurrences -> BOOLEAN]

OneApplicationPerOEROccurrence ==
    \A occurrence \in OEROccurrences :
        LocalOneApplication[occurrence]

THEOREM Resolver03RecursiveLifting ==
    OccurrenceWorld
    /\ (\A occurrence \in OEROccurrences :
            LocalOneApplication[occurrence])
    => OneApplicationPerOEROccurrence
BY DEF OccurrenceWorld, OneApplicationPerOEROccurrence

=============================================================================
