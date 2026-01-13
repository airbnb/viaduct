package viaduct.serve.fixtures

import viaduct.serve.ViaductProvider
import viaduct.serve.ViaductServerConfiguration
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.Viaduct

/**
 * Test fixture: A valid provider with @ViaductServerConfiguration annotation.
 * Creates a minimal Viaduct instance for testing purposes.
 * Uses BasicViaductFactory with the test fixtures package prefix.
 */
@ViaductServerConfiguration
class ValidTestProvider : ViaductProvider {
    override fun getViaduct(): Viaduct {
        // Create a minimal Viaduct using BasicViaductFactory
        // This will discover any @Resolver annotated test resolvers
        return BasicViaductFactory.create(
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "viaduct.serve.fixtures"
            )
        )
    }
}

/**
 * Test fixture: Provider without annotation (should be ignored).
 */
class ProviderWithoutAnnotation : ViaductProvider {
    override fun getViaduct(): Viaduct = throw NotImplementedError("Test provider - should not be called")
}

/**
 * Test fixture: Annotated class that doesn't implement ViaductProvider.
 */
@ViaductServerConfiguration
class AnnotatedNonProvider {
    fun doSomething() = "not a provider"
}

/**
 * Test fixture: Provider without no-arg constructor.
 * This should be skipped during discovery with a warning.
 */
@ViaductServerConfiguration
class ProviderWithoutNoArgConstructor(
    private val param: String
) : ViaductProvider {
    override fun getViaduct(): Viaduct = throw NotImplementedError("Test provider - should not be called")
}
