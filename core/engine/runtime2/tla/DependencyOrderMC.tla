---------------------- MODULE DependencyOrderMC ----------------------
EXTENDS Naturals, FiniteSets

(*
Executable TLC harness for DependencyOrder. The proof module imports
proof-only TLAPS libraries, so this module repeats the small transition
relation while retaining the same operators checked by the proof.
*)

CONSTANTS Keys, Dependencies

VARIABLES remaining, resolved, applications, n

vars == <<remaining, resolved, applications, n>>

Init ==
    /\ remaining = Keys
    /\ resolved = {}
    /\ applications = {}
    /\ n = Cardinality(Keys)

Ready(key) ==
    /\ key \in remaining
    /\ Dependencies[key] \cap remaining = {}

ResolveReady(key) ==
    /\ Ready(key)
    /\ remaining' = remaining \ {key}
    /\ resolved' = resolved \cup {key}
    /\ applications' = applications \cup {key}
    /\ n' = n - 1

Next == \E key \in Keys : ResolveReady(key)

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

InputsComplete ==
    \A key \in resolved : Dependencies[key] \subseteq resolved

NoReapplication ==
    /\ applications = resolved
    /\ applications \cap remaining = {}

TypeOK ==
    /\ remaining \subseteq Keys
    /\ resolved = Keys \ remaining
    /\ NoReapplication
    /\ InputsComplete
    /\ IsFiniteSet(remaining)
    /\ n = Cardinality(remaining)

Done == remaining = {}
Termination == <>Done

MCKeys == {"source", "consumer", "helper", "leaf"}

MCDependencies ==
    [key \in MCKeys |->
        CASE key = "consumer" -> {"helper"}
          [] key = "helper"   -> {"leaf"}
          [] OTHER            -> {}]

=============================================================================
