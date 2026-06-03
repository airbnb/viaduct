package com.example.rrs

import com.google.inject.Injector
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector

/**
 * Guice-backed [CodeInjector] for the standalone RRS process. Generic across tenants —
 * pass an [Injector] configured with whichever Guice modules your tenant requires and
 * Viaduct will instantiate resolver classes through it.
 */
class RrsCodeInjector(private val injector: Injector) : CodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> = Provider { injector.getInstance(clazz) }
}
