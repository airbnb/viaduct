package viaduct.service.runtime

import viaduct.apiannotations.InternalApi

@InternalApi
class ViaductSchemaLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
