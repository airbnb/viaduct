package viaduct.service.runtime

import viaduct.apiannotations.InternalApi

@InternalApi
class ViaductInvalidConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
