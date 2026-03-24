package viaduct.tenant.codegen.bytecode.config

import viaduct.codegen.utils.KmName

/**
 * Configuration utilities for InputTypeFactory method name resolution.
 * Centralizes the mapping between tagging interfaces and their corresponding
 * InputTypeFactory method names.
 */
object InputTypeFactoryConfig {
    /**
     * Get the InputTypeFactory method name for a given tagging interface (KmName version).
     *
     * @param taggingInterface The KmName of the tagging interface (Arguments or Input)
     * @return The corresponding InputTypeFactory method name
     * @throws IllegalArgumentException if the tagging interface is unknown
     */
    fun getFactoryMethodName(taggingInterface: KmName): String =
        when (taggingInterface) {
            cfg.ARGUMENTS_GRT.asKmName -> "argumentsInputType"
            cfg.INPUT_GRT.asKmName -> "inputObjectInputType"
            else -> error("Unknown input tagging interface: $taggingInterface")
        }

    /**
     * Get the InputTypeFactory method name for a given tagging interface (String version).
     *
     * @param taggingInterface The fully qualified name of the tagging interface
     * @return The corresponding InputTypeFactory method name
     * @throws IllegalArgumentException if the tagging interface is unknown
     */
    fun getFactoryMethodName(taggingInterface: String): String =
        when (taggingInterface) {
            "viaduct.api.types.Arguments" -> "argumentsInputType"
            "viaduct.api.types.Input" -> "inputObjectInputType"
            else -> error("Unknown tagging interface: $taggingInterface")
        }
}
