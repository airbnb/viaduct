package viaduct.ksp.validation

import java.io.File

// Util class that facilitates wrapping and upwrapping of compilation
// schema sdl from a Kt file used for tenant defined fragments validation
object CompilationSchemaWrapperKtUtils {
    // It turns out that we can only pass in kt files into a kotlin compiler plugin
    // so we have a special kt file that wraps compilation schema sdl inside
    // the file as a comment and we parse the sdl string here. If we find a better
    // way to pass compilation schema sdl, we can change this
    const val COMPILATION_SCHEMA_WRAPPER_KT_FILE = "compilation_schema_wrapper_class.kt"

    /**
     * We wrap compilation schema sdl into a kt file because
     * it's impossible to pass in non kt file into a ksp plugin otherwise.
     * If and when we find a better approach, please change me!
     */
    fun createCompilationSchemaSDLWrapperKt(schemaSDL: String) =
        """
package com.airbnb.bazel.empty

/**
* This file exists only to provide a way for compilation schema sdl
* to be made accessible to kotlin compiler plugin validate
* tenant defined resolver required selection sets in tenant code
*/
object UNRESERVED_OBJECT_NAME_DO_NOT_USE__
/**
* $schemaSDL
*/
        """.trimIndent()

    // returns parsed compilation schema sdl from wrapper kt class
    fun unwrapCompilationSchemaSDLFromWrapperKt(wrapperKtFile: File) = wrapperKtFile.readText().substringBeforeLast("\n*/").substringAfterLast("/**\n*")
}
