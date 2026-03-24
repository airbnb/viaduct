package viaduct.service.api.spi

import org.junit.jupiter.api.Test
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure

class NaiveTenantCodeInjectorTests {
    private val subject = TenantCodeInjector.Naive as NaiveTenantCodeInjector

    @Test
    fun `When good fixture then succeed`() {
        expectThat(subject.getProvider(GoodFixture::class.java).get())
            .isA<GoodFixture>()
            .get { f }
            .isEqualTo(1)
    }

    @Test
    fun `When constructor is inaccessible then fail`() {
        expectCatching { subject.getProvider(BadFixtureNotAccessible::class.java).get() }
            .isFailure()
    }

    @Test
    fun `When no no-arg constructor then fail`() {
        expectCatching { subject.getProvider(BadFixtureNoNoArgs::class.java).get() }
            .isFailure()
    }

    @Test
    fun `When interface then fail`() {
        expectCatching { subject.getProvider(BadFixtureNoNoArgs::class.java).get() }
            .isFailure()
    }

    @Test
    fun `When constructorCache is broken then fail`() {
        subject.constructorCache.computeIfAbsent(BadFixtureKey::class.java) {
            GoodFixture::class.java.getDeclaredConstructor()
        }
        expectCatching { subject.getProvider(BadFixtureKey::class.java).get() }
            .isFailure()
            .isA<IllegalStateException>()
    }
}
