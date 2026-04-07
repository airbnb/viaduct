package viaduct.tenant.codegen.util

import java.io.File

fun shouldUseBinarySchema(flagFile: File?): Boolean {
    if (flagFile == null) return false
    return parsePythonDict(flagFile)["enable_binary_schema"]?.equals("True", ignoreCase = true) ?: false
}

/**
 * Returns whether the flag file explicitly contains the `enable_binary_schema` key,
 * regardless of its value. Returns `false` if the flag file is null or doesn't
 * contain the key.
 */
fun hasBinarySchemaFlag(flagFile: File?): Boolean {
    if (flagFile == null) return false
    return parsePythonDict(flagFile).containsKey("enable_binary_schema")
}

internal fun parsePythonDict(file: File): Map<String, String> {
    val content = file.readText()

    // Extract content between { }
    val dictContent = content
        .substringAfter("{")
        .substringBefore("}")

    // Parse each "key": "value" pair
    return """"([^"]+)":\s*"([^"]+)"""".toRegex()
        .findAll(dictContent)
        .associate { it.groupValues[1] to it.groupValues[2] }
}
