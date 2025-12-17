package viaduct.tenant.runtime.bootstrap

import com.google.inject.Injector
import com.google.inject.Provider
import viaduct.apiannotations.TestingApi
import viaduct.service.api.spi.TenantCodeInjector

@TestingApi
class GuiceTenantCodeInjector(val injector: Injector) : TenantCodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> = injector.getProvider(clazz)
}
