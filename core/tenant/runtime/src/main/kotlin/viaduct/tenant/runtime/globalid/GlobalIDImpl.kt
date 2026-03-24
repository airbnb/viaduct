package viaduct.tenant.runtime.globalid

import viaduct.api.globalid.GlobalIDImpl as ApiGlobalIDImpl

/**
 * Type alias for backward compatibility.
 * The canonical GlobalIDImpl is now in viaduct.api.globalid package.
 */
typealias GlobalIDImpl<T> = ApiGlobalIDImpl<T>
