package viaduct.remote

import com.google.protobuf.ByteString
import viaduct.remote.api.EncodedRemoteResolverContext
import viaduct.remote.api.RemoteResolverContextException
import viaduct.remote.grpc.EncodedRemoteContext

internal fun EncodedRemoteResolverContext.toWire(): EncodedRemoteContext =
    EncodedRemoteContext.newBuilder()
        .setFormat(format)
        .setVersion(version)
        .setPayload(ByteString.copyFrom(payload))
        .build()

internal fun EncodedRemoteContext.fromWire(): EncodedRemoteResolverContext =
    try {
        EncodedRemoteResolverContext(
            format = format,
            version = version,
            payload = payload.toByteArray(),
        )
    } catch (e: IllegalArgumentException) {
        throw RemoteResolverContextException("Invalid remote resolver context metadata", e)
    }
