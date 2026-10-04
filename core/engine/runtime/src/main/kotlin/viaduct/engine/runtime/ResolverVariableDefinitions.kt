package viaduct.engine.runtime

import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.api.spi.VariableFromFunctionDefinitions

/** Explicit variable declarations needed to compile a resolver's required selections. */
data class ResolverVariableDefinitions(
    val fromArguments: VariableFromArgumentDefinitions,
    val fromObjectFields: VariableFromFieldDefinitions,
    val fromQueryFields: VariableFromFieldDefinitions,
    val fromFunction: VariableFromFunctionDefinitions?,
) {
    companion object {
        @JvmField
        val EMPTY =
            ResolverVariableDefinitions(
                fromArguments = VariableFromArgumentDefinitions.EMPTY,
                fromObjectFields = VariableFromFieldDefinitions.EMPTY,
                fromQueryFields = VariableFromFieldDefinitions.EMPTY,
                fromFunction = null,
            )
    }
}
