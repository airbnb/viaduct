package viaduct.devserve.fixtures

import viaduct.service.api.Viaduct
import viaduct.service.api.ViaductApplication
import viaduct.service.api.ViaductFactory

/**
 * Test fixture: A valid factory with @ViaductApplication annotation
 */
@ViaductApplication
class ValidTestFactory : ViaductFactory {
    override fun createViaduct(): Viaduct {
        throw NotImplementedError("Test factory - should not be called")
    }
}

/**
 * Test fixture: Another valid factory without annotation
 * (To test multiple factory detection, this would need to be annotated,
 * but leaving it unannotated allows other tests to pass)
 */
class AnotherValidTestFactory : ViaductFactory {
    override fun createViaduct(): Viaduct {
        throw NotImplementedError("Test factory - should not be called")
    }
}

/**
 * Test fixture: Factory without annotation (should be ignored)
 */
class FactoryWithoutAnnotation : ViaductFactory {
    override fun createViaduct(): Viaduct {
        throw NotImplementedError("Test factory - should not be called")
    }
}

/**
 * Test fixture: Annotated class that doesn't implement ViaductFactory
 */
@ViaductApplication
class AnnotatedNonFactory {
    fun doSomething() = "not a factory"
}

/**
 * Test fixture: Factory without no-arg constructor
 */
@ViaductApplication
class FactoryWithoutNoArgConstructor(private val param: String) : ViaductFactory {
    override fun createViaduct(): Viaduct {
        throw NotImplementedError("Test factory - should not be called")
    }
}
