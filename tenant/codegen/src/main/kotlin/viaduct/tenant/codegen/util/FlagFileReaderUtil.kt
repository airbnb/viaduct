package viaduct.tenant.codegen.util

import java.io.File

fun shouldUseBinarySchema(flagFile: File): Boolean {
    return parsePythonDict(flagFile)["enable_binary_schema"]?.equals("True", ignoreCase = true) ?: false
}

private fun parsePythonDict(file: File): Map<String, String> {
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
