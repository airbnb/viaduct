package com.example.starwars.common

import io.micronaut.runtime.http.scope.RequestScope

/**
 * Request-scoped context for managing security access control.
 * Each HTTP request gets its own instance to prevent data leakage between requests.
 */
@RequestScope
open class SecurityAccessContext {
    private var securityAccess: String? = null

    companion object {
        private const val ADMIN_ACCESS = "admin"
    }

    /**
     * Sets the security access level for the current request.
     */
    open fun setSecurityAccess(securityAccess: String?) {
        this.securityAccess = securityAccess
    }

    /**
     * Executes the given block only if the user has admin access.
     *
     * @throws SecurityException if the user lacks admin permissions
     */
    open fun <T> validateAccess(block: () -> T): T {
        if (securityAccess != ADMIN_ACCESS) {
            throw SecurityException("Insufficient permissions!")
        }
        return block()
    }
}
