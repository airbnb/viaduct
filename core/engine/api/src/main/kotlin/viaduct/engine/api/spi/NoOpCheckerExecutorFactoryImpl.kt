package viaduct.engine.api.spi

import javax.inject.Inject
import javax.inject.Singleton
import viaduct.engine.api.ViaductSchema

/**
 * No-op [CheckerExecutorFactory] that always returns `null`, effectively disabling all
 * field- and type-level access checks.
 *
 * This is the default implementation used when no access-control policy has been configured.
 * Production deployments that require access checks should replace this with a real
 * [CheckerExecutorFactory] implementation via the engine's wiring layer.
 */
@Singleton
@Suppress("ktlint:standard:indent")
class NoOpCheckerExecutorFactoryImpl
    @Inject
    constructor() : CheckerExecutorFactory {
        override fun checkerExecutorForField(
            schema: ViaductSchema,
            typeName: String,
            fieldName: String
        ): CheckerExecutor? = null

        override fun checkerExecutorForType(
            schema: ViaductSchema,
            typeName: String
        ): CheckerExecutor? = null
    }
