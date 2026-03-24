package viaduct.engine.runtime.instrumentation

import graphql.ExecutionResult
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.GraphQLObjectType
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.utils.slf4j.logger

/**
 * Instrumentation that provides metrics for operation execution
 */
class TaggedMetricInstrumentation(
    val meterRegistry: MeterRegistry
) : ViaductInstrumentationBase(),
    IViaductInstrumentation.WithBeginFieldFetch {
    companion object {
        const val VIADUCT_EXECUTION_METER_NAME = "viaduct.execution"
        const val VIADUCT_OPERATION_METER_NAME = "viaduct.operation"
        const val VIADUCT_FIELD_METER_NAME = "viaduct.field"

        val log by logger()
    }

    init {
        meterRegistry.config().meterFilter(object : MeterFilter {
            override fun configure(
                id: Meter.Id,
                config: DistributionStatisticConfig
            ): DistributionStatisticConfig? {
                val metersWithDetailedPercentile = setOf(
                    VIADUCT_EXECUTION_METER_NAME,
                    VIADUCT_OPERATION_METER_NAME,
                    VIADUCT_FIELD_METER_NAME
                )

                if (metersWithDetailedPercentile.contains(id.name)) {
                    return config.merge(
                        DistributionStatisticConfig.builder()
                            .percentiles(0.5, 0.75, 0.9, 0.95)
                            .build()
                    )
                }
                return config
            }
        })
    }

    override fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? {
        // Record the current time.
        val timerSample = Timer.start(meterRegistry)
        val timer = Timer.builder(VIADUCT_EXECUTION_METER_NAME)
        parameters.executionInput.operationName?.let {
            timer.tag("operation_name", it)
        }

        return SimpleInstrumentationContext.whenCompleted { executionResult, throwable ->

            val success = throwable == null && executionResult.errors.isEmpty()
            timer.tag("success", success.toString())
            // Emit metrics
            timerSample.stop(
                timer.register(meterRegistry)
            )
        }
    }

    override fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? {
        val timerSample = Timer.start(meterRegistry)
        val timer = Timer.builder(VIADUCT_OPERATION_METER_NAME)
        parameters.executionContext.operationDefinition.name?.let {
            timer.tag("operation_name", it)
        }

        return SimpleInstrumentationContext.whenCompleted { executionResult, throwable ->

            val success = throwable == null && executionResult.errors.isEmpty()
            timer.tag("success", success.toString())
            timerSample.stop(
                timer.register(meterRegistry)
            )
        }
    }

    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? {
        val timerSample = Timer.start(meterRegistry)
        val timer = Timer.builder(VIADUCT_FIELD_METER_NAME)
        parameters.executionContext.operationDefinition.name?.let {
            timer.tag("operation_name", it)
        }

        val parentType = parameters.executionStepInfo.parent?.type as? GraphQLObjectType
        if (parentType != null) {
            timer.tag("field", "${parentType.name}.${parameters.field.name}")
        } else {
            timer.tag("field", "${parameters.field.name}")
        }

        return SimpleInstrumentationContext.whenCompleted { _, throwable ->

            val success = throwable == null
            timer.tag("success", success.toString())
            timerSample.stop(
                timer.register(meterRegistry)
            )
        }
    }
}
