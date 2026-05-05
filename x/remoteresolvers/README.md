# Remote Resolvers (Experimental)

Experimental module for executing Viaduct node resolvers over gRPC.

## Overview

A proxy installed in the main Viaduct process (`RemoteNodeProxyExecutor`)
forwards resolver invocations to a `RemoteResolverService` over gRPC. The
remote service can re-enter the engine through an `EngineCallbackService`
to satisfy subquery calls.

The module supports in-process and networked gRPC transports interchangeably.

## Status

Experimental. APIs may change without notice.
