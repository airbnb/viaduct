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
 */
internal class ResolverParamsJsonCodec(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true),
) {
    fun encode(descriptorFile: ResolverDescriptorFile): String {
        return objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(descriptorFile) + TRAILING_NEWLINE
    }

    fun decode(json: String): ResolverDescriptorFile {
        return objectMapper.readValue(json, ResolverDescriptorFile::class.java)
    }

    private companion object {
        const val TRAILING_NEWLINE = "\n"
    }
}
