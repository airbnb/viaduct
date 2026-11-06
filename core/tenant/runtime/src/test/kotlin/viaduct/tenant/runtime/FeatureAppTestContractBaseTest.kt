package viaduct.tenant.runtime

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.AbstractFeatureAppTestContractBase
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.NaiveTenantModuleInjectorFactory

class FeatureAppTestContractBaseTest {
    @Test
    fun `ordinary build exceptions are wrapped with the original cause`() {
        val failure = IllegalStateException("could not read flags")
        val fixture = Fixture()
        fixture.initViaductBuilder()
        fixture.withViaductBuilder {
            withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = throw failure
            })
        }

        val thrown = assertThrows<RuntimeException> { fixture.tryBuildViaductService() }

        assertSame(failure, thrown.cause)
    }

    @Test
    fun `build errors escape unchanged`() {
        val failure = LinkageError("could not load flag implementation")
        val fixture = Fixture()
        fixture.initViaductBuilder()
        fixture.withViaductBuilder {
            withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = throw failure
            })
        }

        val thrown = assertThrows<LinkageError> { fixture.tryBuildViaductService() }

        assertSame(failure, thrown)
    }

    @Test
    fun `a failed build can be retried and the successful service is reused`() {
        val fixture = Fixture()
        fixture.initViaductBuilder()
        fixture.withViaductBuilder {
            withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = error("could not read flags")
            })
        }
        assertThrows<RuntimeException> { fixture.tryBuildViaductService() }
        fixture.withViaductBuilder { withFlagManager(FlagManager.Disabled) }

        fixture.tryBuildViaductService()
        val service = fixture.viaductService
        fixture.withViaductBuilder {
            withFlagManager(object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = error("unexpected rebuild")
            })
        }
        fixture.tryBuildViaductService()

        assertSame(service, fixture.viaductService)
    }

    @TestSchema("extend type Query { value: String }")
    private class Fixture : AbstractFeatureAppTestContractBase() {
        override fun moduleConfigSources() = emptyList<ModuleConfigSource>()

        override fun tenantModuleInjectorFactory() = NaiveTenantModuleInjectorFactory
    }
}
