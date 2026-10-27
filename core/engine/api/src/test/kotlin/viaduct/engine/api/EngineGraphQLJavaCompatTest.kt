@file:Suppress("DEPRECATION")

package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.EngineConfiguration
import viaduct.engine.EngineFactory
import viaduct.engine.airbnb.EngineGraphQLJavaCompat
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.service.api.spi.FlagManager

class EngineGraphQLJavaCompatTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `context creation defaults to incremental execution when the feature is enabled`(featureEnabled: Boolean) {
        val engine = createEngine(featureEnabled)
        val requestContext = Any()

        val context = engine.createEngineExecutionContext(requestContext) as EngineExecutionContextImpl

        assertEquals(featureEnabled, context.incrementalExecutionEnabled)
        assertSame(requestContext, context.requestContext)
    }

    @ParameterizedTest
    @CsvSource(
        "false, false, false",
        "false, true, false",
        "true, false, false",
        "true, true, true",
    )
    fun `context creation respects the request option and feature flag`(
        featureEnabled: Boolean,
        incrementalExecution: Boolean,
        expectedIncrementalExecution: Boolean,
    ) {
        val engine = createEngine(featureEnabled)
        val requestContext = Any()

        val context = engine.createEngineExecutionContext(requestContext, incrementalExecution) as EngineExecutionContextImpl

        assertEquals(expectedIncrementalExecution, context.incrementalExecutionEnabled)
        assertSame(requestContext, context.requestContext)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `legacy Kotlin callers retain the default incremental execution option`(featureEnabled: Boolean) {
        val engine = createEngine(featureEnabled)
        val requestContext = Any()
        // Kotlin source cannot call the generated bridge used by older compiled callers.
        val defaultMethod = Class.forName("${EngineGraphQLJavaCompat::class.java.name}\$DefaultImpls").getMethod(
            "createEngineExecutionContext\$default",
            EngineGraphQLJavaCompat::class.java,
            Any::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Integer.TYPE,
            Any::class.java,
        )

        val context = defaultMethod.invoke(null, engine, requestContext, false, 0b10, null) as EngineExecutionContextImpl

        assertEquals(featureEnabled, context.incrementalExecutionEnabled)
        assertSame(requestContext, context.requestContext)
    }

    private fun createEngine(featureEnabled: Boolean): EngineGraphQLJavaCompat {
        val schema = createSchemaWithWiring("extend type Query { value: String }")
        val config = EngineConfiguration.default.copy(
            flagManager = object : FlagManager {
                override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENABLE_INCREMENTAL_EXECUTION && featureEnabled
            },
        )
        return EngineFactory(config).create(schema = schema, fullSchema = FullSchema(schema)) as EngineGraphQLJavaCompat
    }
}
