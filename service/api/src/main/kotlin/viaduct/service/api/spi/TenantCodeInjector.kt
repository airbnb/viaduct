package viaduct.service.api.spi

import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Provider
import viaduct.apiannotations.StableApi

/**
 * The Viaduct engine always invokes tenant code by first
 * instantiating a containing class (e.g., a @Resolver class), and
 * then calling a member of that instance.  A new instance is created
 * for _each_ invocation of a tenant function, even when that function
 * is invoked multiple times per request.
 */
@StableApi
interface TenantCodeInjector {
    fun <T> getProvider(clazz: Class<T>): Provider<T>

    companion object {
        /**
         * Intended for testing and very simple applications.  Real
         * applications should use a DI framework.  This
         * implementation assumes that containing classes always have
         * an accessible, zero-arg constructors and will throw a runtime
         * error if asked to provide an object without such a constructor.
         */
        val Naive: TenantCodeInjector = NaiveTenantCodeInjector()
    }
}

@StableApi
class NaiveTenantCodeInjector : TenantCodeInjector {
    val constructorCache: ConcurrentHashMap<Class<*>, Constructor<*>> =
        ConcurrentHashMap()

    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        val ctor = constructorCache.computeIfAbsent(clazz) {
            it.getDeclaredConstructor()
        }

        @Suppress("UNCHECKED_CAST")
        return Provider {
            (ctor.newInstance() as T).also {
                if (!clazz.isInstance(it)) throw IllegalStateException("$it is not an instance of $clazz")
            }
        }
    }
}
