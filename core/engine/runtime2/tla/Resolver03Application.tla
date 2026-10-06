-------------------- MODULE Resolver03Application --------------------
EXTENDS ResolverApplication, Resolver03Projection

(*
Resolver01/02 application correctness takes projection coverage as a
premise. Resolver03 derives that premise from exact guarded extension
tokens, while retaining the same prefix/final materialization argument.
*)

Resolver03ApplicationWorld ==
    /\ ResolverApplicationBaseWorld
    /\ Resolver03ProjectionWorld

=============================================================================
