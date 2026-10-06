package viaduct.api.internal

import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.tenant.runtime.jvm.OverlayEngineObjectData as SharedOverlayEngineObjectData

/** Retains the constructor used by existing framework consumers. */
@InternalApi
class OverlayEngineObjectData(
    overlay: EngineObjectData.Sync,
    base: EngineObjectData.Sync
) : EngineObjectData.Sync by SharedOverlayEngineObjectData(overlay, base)
