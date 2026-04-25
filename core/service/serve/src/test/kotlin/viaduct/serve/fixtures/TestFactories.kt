package viaduct.serve.fixtures

import viaduct.serve.ViaductFactory
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
class ValidTestProvider : ViaductFactory {
    override fun mkViaduct(): Viaduct {
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
class ProviderWithoutAnnotation : ViaductFactory {
    override fun mkViaduct(): Viaduct = throw NotImplementedError("Test provider - should not be called")
}

/**
 * Test fixture: Annotated class that doesn't implement ViaductFactory.
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
) : ViaductFactory {
    override fun mkViaduct(): Viaduct = throw NotImplementedError("Test provider - should not be called")
}
