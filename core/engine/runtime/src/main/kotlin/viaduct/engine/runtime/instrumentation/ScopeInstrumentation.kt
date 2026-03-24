package viaduct.engine.runtime.instrumentation

import graphql.execution.ExecutionContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.context.findLocalContextForType
import viaduct.engine.runtime.context.isIntrospective
import viaduct.engine.runtime.context.updateCompositeLocalContext

class ScopeInstrumentation : ViaductInstrumentationBase() {
    override fun instrumentExecutionContext(
        executionContext: ExecutionContext,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionContext {
        return executionContext.transform { contextBuilder ->
            // If introspective, we need to use the scoped schema, which is the correct schema to return the introspective query scoped.
            val engineExecutionContext = executionContext.findLocalContextForType<EngineExecutionContextImpl>().let { currentExecutionContext ->
                if (executionContext.isIntrospective) {
                    val scopedExecutionContext = currentExecutionContext.copy(activeSchema = currentExecutionContext.scopedSchema)

                    // Introspective queries needs to update the local context with the scoped execution context.
                    contextBuilder.localContext(
                        executionContext.updateCompositeLocalContext<EngineExecutionContextImpl> { scopedExecutionContext }
                    )

                    scopedExecutionContext
                } else {
                    currentExecutionContext
                }
            }

            // update the execution to use activeSchema, which is fullSchema or scopedSchema depending on introspection
            contextBuilder.graphQLSchema(
                engineExecutionContext.activeSchema.schema
            )
        }
    }
}
