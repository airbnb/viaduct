package viaduct.gradle

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import org.gradle.api.GradleException
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.service.api.scoping.SchemaScopeDefinitions
import viaduct.service.api.scoping.SchemaScoping
import viaduct.service.api.scoping.ScopingErrorCodes

/**
 * Reads an application's schema scope definitions from `src/main/viaduct/scopes.yaml`.
 *
 * The only YAML-aware layer; [SchemaScopeDefinitions] owns every rule.
 */
@InternalApi
@OptIn(ExperimentalApi::class)
object ViaductScopesYaml {
    /** Directory, relative to an application project, holding the definitions file. */
    const val SOURCE_DIRECTORY: String = "src/main/viaduct"

    /** Conventional location within an application project. */
    const val RELATIVE_PATH: String = "$SOURCE_DIRECTORY/${SchemaScopeDefinitions.SOURCE_FILE_NAME}"

    /**
     * `STRICT_DUPLICATE_DETECTION` is the only way to catch a repeated mapping key: YAML permits it and
     * keeps the last value, so a duplicated `schemaScopes:` is silently collapsed before any map exists.
     */
    private val yamlFactory: YAMLFactory =
        YAMLFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) }

    private val mapper: ObjectMapper = ObjectMapper(yamlFactory)

    /**
     * Reads and validates [file], returning [SchemaScoping.EMPTY] when it is absent or holds nothing.
     *
     * @throws GradleException with every finding, each rendered as `[CODE] message`.
     */
    fun read(file: File?): SchemaScoping {
        if (file == null || !file.isFile) return SchemaScoping.EMPTY
        return when (val result = SchemaScopeDefinitions.fromDecoded(decode(file))) {
            is SchemaScopeDefinitions.Result.Success -> result.scoping
            is SchemaScopeDefinitions.Result.Failure -> throw GradleException(
                buildString {
                    append(file.invariantSeparatorsPath)
                    append(" declares invalid schema scope definitions:")
                    result.errors.forEach { append("\n  - [${it.code}] ${it.message}") }
                },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(file: File): Map<String, Any?>? {
        rejectAliases(file)
        val tree = parseSingleDocument(file)
        if (tree == null || tree.isMissingNode || tree.isNull) return null
        if (!tree.isObject) {
            throw malformed(
                file,
                "must contain a YAML mapping at its top level, with keys such as 'schemaScopes' and " +
                    "'scopedSchemas'.",
            )
        }
        return mapper.convertValue(tree, Map::class.java) as Map<String, Any?>
    }

    /**
     * An alias resolves to its anchor's *name* rather than the anchored value, so `[*public]` against
     * `[&public internal]` silently selects `public`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun rejectAliases(file: File) {
        val aliased = try {
            yamlFactory.createParser(file).use { parser ->
                var found = false
                while (!found && parser.nextToken() != null) {
                    found = parser.isCurrentAlias
                }
                found
            }
        } catch (e: Exception) {
            throw malformed(file, "could not be parsed: ${e.message}", e)
        }
        if (aliased) {
            throw malformed(
                file,
                "uses a YAML alias ('*name'). Write each scope and scoped-schema ID out literally; an " +
                    "anchor on its own is fine, but a reference to one is not resolved.",
            )
        }
    }

    /** Separate parse from [rejectAliases] because reading the tree consumes the tokens it inspects. */
    @Suppress("TooGenericExceptionCaught")
    private fun parseSingleDocument(file: File): JsonNode? {
        val (tree, trailingDocument) = try {
            yamlFactory.createParser(file).use { parser ->
                // readTree rather than readValue: a file holding only blank lines and comments yields a
                // missing node, where readValue throws "No content to map due to end-of-input".
                val first: JsonNode? = mapper.readTree(parser)
                first to (parser.nextToken() != null)
            }
        } catch (e: Exception) {
            throw malformed(file, "could not be parsed: ${e.message}", e)
        }
        if (trailingDocument) {
            throw malformed(
                file,
                "holds more than one YAML document. Everything after the first '---' is ignored, so " +
                    "declare every scope in a single document.",
            )
        }
        return tree
    }

    private fun malformed(
        file: File,
        detail: String,
        cause: Exception? = null,
    ): GradleException =
        GradleException(
            "[${ScopingErrorCodes.SCOPES_FILE_MALFORMED}] ${file.invariantSeparatorsPath} $detail",
            cause,
        )
}
