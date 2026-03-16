package viaduct.tenant.codegen.util

import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.hasConnectionDirective

data class ConnectionArgumentsInfo(
    val interfaceToAdd: KmName?,
    val overrideFieldNames: Set<String>
) {
    companion object {
        /** Field names declared by ForwardConnectionArguments. */
        private val FORWARD_FIELDS = setOf("first", "after")

        /** Field names declared by BackwardConnectionArguments. */
        private val BACKWARD_FIELDS = setOf("last", "before")

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
        val BASE = ConnectionArgumentsInfo(
            cfg.CONNECTION_ARGUMENTS.asKmName,
            emptySet()
        )

        /**
         * Determines the appropriate ConnectionArguments interface based on which
         * pagination arguments are present on a field that returns a Connection type:
         * - ForwardConnectionArguments: requires both 'first' AND 'after'
         * - BackwardConnectionArguments: requires both 'last' AND 'before'
         * - MultidirectionalConnectionArguments: requires all four
         * - ConnectionArguments (base): requires at least 'first' or 'last'
         */

        fun from(field: ViaductSchema.Field?): ConnectionArgumentsInfo {
            if (field == null) return NONE
            if (!field.type.baseTypeDef.hasConnectionDirective) return NONE

            val argNames = field.args.map { it.name }.toSet()

            val hasFirst = "first" in argNames
            val hasAfter = "after" in argNames
            val hasLast = "last" in argNames
            val hasBefore = "before" in argNames

            val hasFullForward = hasFirst && hasAfter
            val hasFullBackward = hasLast && hasBefore

            return when {
                hasFullForward && hasFullBackward -> MULTIDIRECTIONAL
                hasFullForward -> FORWARD
                hasFullBackward -> BACKWARD
                hasFirst || hasLast -> BASE
                else -> NONE
            }
        }
    }
}
