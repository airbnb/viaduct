package viaduct.tenant.codegen.util

import viaduct.codegen.ConnectionArgumentsDirection
import viaduct.codegen.SchemaAnalysis
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

data class ConnectionArgumentsInfo(
    val interfaceToAdd: KmName?,
    val overrideFieldNames: Set<String>
) {
    companion object {
        private val FORWARD_FIELDS = SchemaAnalysis.FORWARD_CONNECTION_ARG_NAMES
        private val BACKWARD_FIELDS = SchemaAnalysis.BACKWARD_CONNECTION_ARG_NAMES

        val NONE = ConnectionArgumentsInfo(null, emptySet())
        val FORWARD = ConnectionArgumentsInfo(
            cfg.FORWARD_CONNECTION_ARGUMENTS.asKmName,
            FORWARD_FIELDS
        )
        val BACKWARD = ConnectionArgumentsInfo(
            cfg.BACKWARD_CONNECTION_ARGUMENTS.asKmName,
            BACKWARD_FIELDS
        )
        val MULTIDIRECTIONAL = ConnectionArgumentsInfo(
            cfg.MULTIDIRECTIONAL_CONNECTION_ARGUMENTS.asKmName,
            FORWARD_FIELDS + BACKWARD_FIELDS
        )

        /**
         * Determines the appropriate ConnectionArguments interface for a field returning a
         * `@connection` type. Delegates the direction decision to the shared
         * [SchemaAnalysis.connectionArgumentsDirection] so the Kotlin and Java codegens agree;
         * this only maps that direction to the Kotlin interface KmName + the field names it
         * overrides. A `first`-only field is FORWARD and a `last`-only field is BACKWARD (there is
         * no bare-`ConnectionArguments` case — see [SchemaAnalysis.connectionArgumentsDirection]).
         */
        fun from(field: ViaductSchema.Field?): ConnectionArgumentsInfo {
            if (field == null) return NONE
            return when (SchemaAnalysis.connectionArgumentsDirection(field)) {
                ConnectionArgumentsDirection.NONE -> NONE
                ConnectionArgumentsDirection.FORWARD -> FORWARD
                ConnectionArgumentsDirection.BACKWARD -> BACKWARD
                ConnectionArgumentsDirection.MULTIDIRECTIONAL -> MULTIDIRECTIONAL
            }
        }
    }
}
