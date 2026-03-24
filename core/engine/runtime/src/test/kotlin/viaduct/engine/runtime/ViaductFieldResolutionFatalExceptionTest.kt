package viaduct.engine.runtime

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest

/**
 * Tests that ensure fatal exceptions during field resolution are NOT swallowed by the engine.
 *
 * Field resolution is designed to capture [viaduct.engine.runtime.exceptions.FieldFetchingException]
 * and [viaduct.engine.runtime.execution.InternalEngineException] to allow for partial results.
 * However, other "fatal" exceptions that occur outside of field fetching (such as in instrumentation methods like `beginFieldExecution`)
 * should bubble up and fail the operation.
 *
 * This may change in the future but these tests capture the current behavior.
 */
class ViaductFieldResolutionFatalExceptionTest {
    @Test
    fun `beginFieldExecution instrumentation throws at top level - request fails fatally`() {
        val sdl = """
            extend type Query {
                hello: String
            }
        """

        val throwingInstrumentation = throwingFieldInstrumentation(
            onBegin = { throw RuntimeException("Explosion in beginFieldExecution") }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "hello") {
                resolver {
                    fn { _, _, _, _, _ -> "world" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ hello }")
            }

            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""

            assertTrue(
                message.contains("Explosion in beginFieldExecution") || causeMessage.contains("Explosion in beginFieldExecution"),
                "Expected exception to contain 'Explosion in beginFieldExecution', but got: $exception"
            )
        }
    }

    @Test
    fun `beginFieldExecution instrumentation throws two levels deep - request fails fatally`() {
        val sdl = """
            extend type Query {
                nested: Nested
            }
            type Nested {
                leaf: String
            }
        """

        val throwingInstrumentation = throwingFieldInstrumentation(
            onBegin = { parameters ->
                if (parameters.field.name == "leaf") {
                    throw RuntimeException("Explosion in beginFieldExecution for leaf")
                }
            }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "nested") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mapOf("leaf" to "val")
                    }
                }
            }
            field("Nested" to "leaf") {
                resolver {
                    fn { _, _, _, _, _ -> "val" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ nested { leaf } }")
            }

            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""

            assertTrue(
                message.contains("Explosion in beginFieldExecution for leaf") || causeMessage.contains("Explosion in beginFieldExecution for leaf"),
                "Expected exception to contain 'Explosion in beginFieldExecution for leaf', but got: $exception"
            )
        }
    }

    @Test
    fun `beginFieldExecution instrumentation throws three levels deep - request fails fatally`() {
        val sdl = """
            extend type Query {
                level1: Level1
            }
            type Level1 {
                level2: Level2
            }
            type Level2 {
                leaf: String
            }
        """

        val throwingInstrumentation = throwingFieldInstrumentation(
            onBegin = { parameters ->
                if (parameters.field.name == "leaf") {
                    throw RuntimeException("Explosion in beginFieldExecution for leaf")
                }
            }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "level1") {
                resolver {
                    fn { _, _, _, _, _ -> mapOf("level2" to "val") }
                }
            }
            field("Level1" to "level2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        delay(10) // artifical delay to ensure we're creating Value.AsyncDeferred's
                        mapOf("leaf" to "val")
                    }
                }
            }
            field("Level2" to "leaf") {
                resolver {
                    fn { _, _, _, _, _ -> "val" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ level1 { level2 { leaf } } }")
            }

            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""

            assertTrue(
                message.contains("Explosion in beginFieldExecution for leaf") || causeMessage.contains("Explosion in beginFieldExecution for leaf"),
                "Expected exception to contain 'Explosion in beginFieldExecution for leaf', but got: $exception"
            )
        }
    }

    @Test
    fun `field instrumentation onDispatched throwing aborts execution`() {
        val sdl = """
            extend type Query {
                hello: String
            }
        """

        val throwingInstrumentation = throwingFieldInstrumentation(
            onDispatched = { throw RuntimeException("Explosion in onDispatched") }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "hello") {
                resolver {
                    fn { _, _, _, _, _ -> "world" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ hello }")
            }
            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""
            assertTrue(
                message.contains("Explosion in onDispatched") || causeMessage.contains("Explosion in onDispatched"),
                "Expected exception to contain 'Explosion in onDispatched', but got: $exception"
            )
        }
    }

    @Test
    fun `field instrumentation onCompleted throwing after data fetch aborts execution`() {
        val sdl = """
            extend type Query {
                hello: String
            }
        """

        val throwingInstrumentation = throwingFieldInstrumentation(
            onCompleted = { _, _ -> throw RuntimeException("Explosion in onCompleted") }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "hello") {
                resolver {
                    fn { _, _, _, _, _ -> "world" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ hello }")
            }
            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""
            assertTrue(
                message.contains("Explosion in onCompleted") || causeMessage.contains("Explosion in onCompleted"),
                "Expected exception to contain 'Explosion in onCompleted', but got: $exception"
            )
        }
    }

    @Test
    fun `field instrumentation onCompleted throwing during error propagation aborts execution`() {
        val sdl = """
            extend type Query {
                hello: String
            }
        """

        val throwingInstrumentation = throwingFieldInstrumentation(
            onCompleted = { _, t ->
                if (t != null) {
                    throw RuntimeException("Explosion in onCompleted (error path)")
                }
            }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "hello") {
                resolver {
                    fn { _, _, _, _, _ -> throw RuntimeException("Resolver error") }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ hello }")
            }
            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""
            assertTrue(
                message.contains("Explosion in onCompleted (error path)") || causeMessage.contains("Explosion in onCompleted (error path)"),
                "Expected exception to contain 'Explosion in onCompleted (error path)', but got: $exception"
            )
        }
    }

    @Test
    fun `fetchObject instrumentation onDispatched throwing aborts execution`() {
        val sdl = """
            extend type Query {
                hello: String
            }
        """

        val throwingInstrumentation = throwingFetchObjectInstrumentation(
            onDispatched = { throw RuntimeException("Explosion in onDispatched (Object)") }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "hello") {
                resolver {
                    fn { _, _, _, _, _ -> "world" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ hello }")
            }
            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""
            assertTrue(
                message.contains("Explosion in onDispatched (Object)") || causeMessage.contains("Explosion in onDispatched (Object)"),
                "Expected exception to contain 'Explosion in onDispatched (Object)', but got: $exception"
            )
        }
    }

    @Test
    fun `fetchObject instrumentation onCompleted throwing on error aborts execution`() {
        val sdl = """
            extend type Query {
                hello: String
            }
        """

        val throwingInstrumentation = throwingFetchObjectInstrumentation(
            onCompleted = { _, _ -> throw RuntimeException("Explosion in onCompleted (Object error path)") }
        )

        MockTenantModuleBootstrapper(sdl) {
            field("Query" to "hello") {
                resolver {
                    fn { _, _, _, _, _ -> "world" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfig(throwingInstrumentation)) {
            val exception = assertThrows<Exception> {
                runQuery("{ hello }")
            }
            val message = exception.message ?: ""
            val causeMessage = exception.cause?.message ?: ""
            assertTrue(
                message.contains("Explosion in onCompleted (Object error path)") || causeMessage.contains("Explosion in onCompleted (Object error path)"),
                "Expected exception to contain 'Explosion in onCompleted (Object error path)', but got: $exception"
            )
        }
    }

    private fun engineConfig(instrumentation: ViaductInstrumentationBase) =
        EngineConfiguration.featureTestDefault.copy(
            additionalInstrumentation = instrumentation.asStandardInstrumentation()
        )

    private fun throwingFieldInstrumentation(
        onBegin: ((InstrumentationFieldParameters) -> Unit)? = null,
        onDispatched: (() -> Unit)? = null,
        onCompleted: ((Any?, Throwable?) -> Unit)? = null
    ) = object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldExecution {
        override fun beginFieldExecution(
            parameters: InstrumentationFieldParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Any> {
            onBegin?.invoke(parameters)
            return object : InstrumentationContext<Any> {
                override fun onDispatched() {
                    onDispatched?.invoke()
                }

                override fun onCompleted(
                    result: Any?,
                    t: Throwable?
                ) {
                    onCompleted?.invoke(result, t)
                }
            }
        }
    }

    private fun throwingFetchObjectInstrumentation(
        onDispatched: (() -> Unit)? = null,
        onCompleted: ((Unit?, Throwable?) -> Unit)? = null
    ) = object : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFetchObject {
        override fun beginFetchObject(
            parameters: InstrumentationExecutionStrategyParameters,
            state: InstrumentationState?
        ): InstrumentationContext<Unit> {
            return object : InstrumentationContext<Unit> {
                override fun onDispatched() {
                    onDispatched?.invoke()
                }

                override fun onCompleted(
                    result: Unit?,
                    t: Throwable?
                ) {
                    onCompleted?.invoke(result, t)
                }
            }
        }
    }
}
