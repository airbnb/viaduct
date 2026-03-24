package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Tagging interface for object types
 */
@StableApi
interface Object : Struct, RecordOutput
