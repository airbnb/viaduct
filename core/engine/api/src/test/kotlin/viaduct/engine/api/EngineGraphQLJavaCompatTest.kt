@file:Suppress("DEPRECATION")

package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.EngineFactory
import viaduct.engine.airbnb.EngineGraphQLJavaCompat
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.EngineExecutionContextImpl

class EngineGraphQLJavaCompatTest {
    @Test
    fun `context creation defaults to incremental execution`() {
        val engine = createEngine()
        val requestContext = Any()

        val context = engine.createEngineExecutionContext(requestContext) as EngineExecutionContextImpl

        assertEquals(true, context.incrementalExecutionEnabled)
        assertSame(requestContext, context.requestContext)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `context creation respects the request option`(incrementalExecution: Boolean) {
        val engine = createEngine()
        val requestContext = Any()

        val context = engine.createEngineExecutionContext(requestContext, incrementalExecution) as EngineExecutionContextImpl

        assertEquals(incrementalExecution, context.incrementalExecutionEnabled)
        assertSame(requestContext, context.requestContext)
    }

    @Test
    fun `legacy Kotlin callers retain the default incremental execution option`() {
        val engine = createEngine()
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

        assertEquals(true, context.incrementalExecutionEnabled)
        assertSame(requestContext, context.requestContext)
    }

    private fun createEngine(): EngineGraphQLJavaCompat {
        val schema = createSchemaWithWiring("extend type Query { value: String }")
        return EngineFactory().create(schema = schema, fullSchema = FullSchema(schema)) as EngineGraphQLJavaCompat
    }
}
