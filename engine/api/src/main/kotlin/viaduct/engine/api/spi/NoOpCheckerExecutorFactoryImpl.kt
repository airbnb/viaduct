package viaduct.engine.api.spi

import com.google.inject.Inject
import javax.inject.Singleton
import viaduct.engine.api.ViaductSchema

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
