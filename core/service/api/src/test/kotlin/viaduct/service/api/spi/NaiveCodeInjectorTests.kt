package viaduct.service.api.spi

import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NaiveCodeInjectorTests {
    private val subject = CodeInjector.Naive as NaiveCodeInjector

    @Test
    fun `When good fixture then succeed`() {
        val result = subject.getProvider(GoodFixture::class.java).get().shouldBeInstanceOf<GoodFixture>()
        assertEquals(1, result.f)
    }

    @Test
    fun `When constructor is inaccessible then fail`() {
        assertThrows<Exception> { subject.getProvider(BadFixtureNotAccessible::class.java).get() }
    }

    @Test
    fun `When no no-arg constructor then fail`() {
        assertThrows<Exception> { subject.getProvider(BadFixtureNoNoArgs::class.java).get() }
    }

    @Test
    fun `When interface then fail`() {
        assertThrows<Exception> { subject.getProvider(BadFixtureNoNoArgs::class.java).get() }
    }

    @Test
    fun `When constructorCache is broken then fail`() {
        subject.constructorCache.computeIfAbsent(BadFixtureKey::class.java) {
            GoodFixture::class.java.getDeclaredConstructor()
        }
        assertThrows<IllegalStateException> { subject.getProvider(BadFixtureKey::class.java).get() }
    }
}
