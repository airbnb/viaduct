package viaduct.tenant.runtime.bootstrap

import com.google.inject.Injector
import com.google.inject.Provider
import viaduct.service.api.spi.CodeInjector

class GuiceCodeInjector(val injector: Injector) : CodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> = injector.getProvider(clazz)
}
