package viaduct.engine.api.spi

import viaduct.engine.api.ViaductSchema

interface CheckerExecutorFactory {
    fun checkerExecutorForField(
        schema: ViaductSchema,
        typeName: String,
        fieldName: String
    ): CheckerExecutor?

    fun checkerExecutorForType(
        schema: ViaductSchema,
        typeName: String
    ): CheckerExecutor?
}
