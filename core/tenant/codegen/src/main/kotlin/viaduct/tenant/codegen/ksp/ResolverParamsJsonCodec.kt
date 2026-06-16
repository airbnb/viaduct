@file:Suppress("DEPRECATION") // Jackson configure() deprecated in newer versions

package viaduct.tenant.codegen.ksp

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * JSON codec for the intermediate file-scoped resolver descriptor.
 *
 * The extractor writes these descriptors as build artifacts, so deterministic output matters:
 * stable property ordering and stable pretty-printing help keep generated files byte-identical
 * when the underlying extracted data has not changed.
 *
 * Uses a plain [ObjectMapper] (no Kotlin module) for encoding because the KSP processor
 * runs in an isolated classloader under KSP2 where `kotlin-reflect` (needed by
 * jackson-module-kotlin) is not available. Encoding via getter introspection works without it.
 *
 * Uses [jacksonObjectMapper] (with Kotlin module) for decoding because deserialization
 * needs Kotlin-aware constructor handling for data classes with default parameters. The
 * decode path only runs in the CLI (process-isolated worker JVM), never inside KSP.
 */
class ResolverParamsJsonCodec {
    private val encoder: ObjectMapper = ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)

    private val encoderWriter = encoder.writerWithDefaultPrettyPrinter()

    private val decoder: ObjectMapper by lazy {
        jacksonObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
    }

    fun encode(descriptorFile: ResolverDescriptorFile): String {
        return encoderWriter.writeValueAsString(descriptorFile) + TRAILING_NEWLINE
    }

    fun decode(json: String): ResolverDescriptorFile {
        return decoder.readValue(json, ResolverDescriptorFile::class.java)
    }

    private companion object {
        const val TRAILING_NEWLINE = "\n"
    }
}
