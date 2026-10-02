package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Decodes the schema scope definitions a build tool has already parsed out of `scopes.yaml`, and owns
 * the validation contract for them.
 *
 * Takes a decoded map rather than a file so YAML stays off the runtime classpath.
 *
 * A repeated mapping key is overwritten before a map exists, so rejecting it belongs to the caller's
 * parser — see `ViaductScopesYaml`.
 */
@InternalApi
@OptIn(ExperimentalApi::class)
object SchemaScopeDefinitions {
    /** Conventional file name, shared by every build tool that reads these definitions. */
    const val SOURCE_FILE_NAME: String = "scopes.yaml"

    /** The only `version` this decoder understands. */
    const val SUPPORTED_VERSION: String = SchemaScoping.CURRENT_VERSION

    private const val VERSION_KEY = "version"
    private const val SCHEMA_SCOPES_KEY = "schemaScopes"
    private const val SCOPED_SCHEMAS_KEY = "scopedSchemas"
    private const val ENTRY_ID_KEY = "id"
    private const val ENTRY_SCOPES_KEY = "scopes"

    private val topLevelKeys = setOf(VERSION_KEY, SCHEMA_SCOPES_KEY, SCOPED_SCHEMAS_KEY)
    private val entryKeys = setOf(ENTRY_ID_KEY, ENTRY_SCOPES_KEY)

    sealed interface Result {
        data class Success(val scoping: SchemaScoping) : Result

        data class Failure(val errors: List<SchemaScopingValidationError>) : Result
    }

    /**
     * Decodes [decoded] into a validated [SchemaScoping].
     *
     * A `null` or empty map means the file was absent or held nothing, which is the "no scopes
     * declared" state rather than an error.
     */
    fun fromDecoded(decoded: Map<String, Any?>?): Result {
        if (decoded.isNullOrEmpty()) return Result.Success(SchemaScoping.EMPTY)

        val shapeErrors = mutableListOf<SchemaScopingValidationError>()
        shapeErrors += unknownKeyErrors(decoded.keys, topLevelKeys, "at the top level of $SOURCE_FILE_NAME")
        shapeErrors += versionErrors(decoded)

        val universe = decodeUniverse(decoded, shapeErrors)
        val entries = decodeEntries(decoded, shapeErrors)
        if (shapeErrors.isNotEmpty()) return Result.Failure(shapeErrors)

        val contentErrors = mutableListOf<SchemaScopingValidationError>()
        contentErrors += universeContentErrors(universe)
        contentErrors += entryContentErrors(entries)
        if (contentErrors.isNotEmpty()) return Result.Failure(contentErrors)

        val scoping = SchemaScoping(
            scopeUniverse = universe.orEmpty().toSet(),
            scopedSchemas = entries.orEmpty().associate { it.id to it.scopes.toSet() },
        )
        val crossPropertyErrors = SchemaScopingValidator.validate(scoping)
        return if (crossPropertyErrors.isEmpty()) {
            Result.Success(scoping)
        } else {
            Result.Failure(crossPropertyErrors)
        }
    }

    private data class DecodedEntry(val id: String, val scopes: List<String>)

    private fun decodeUniverse(
        decoded: Map<String, Any?>,
        errors: MutableList<SchemaScopingValidationError>,
    ): List<String>? {
        if (!decoded.containsKey(SCHEMA_SCOPES_KEY)) return null
        val raw = decoded[SCHEMA_SCOPES_KEY] ?: return emptyList()
        if (raw !is List<*>) {
            errors += wrongType(SCHEMA_SCOPES_KEY, "a list of scope IDs", raw)
            return null
        }
        return raw.mapIndexedNotNull { index, element ->
            scopeIdElement(element, "$SCHEMA_SCOPES_KEY[$index]", errors)
        }
    }

    private fun decodeEntries(
        decoded: Map<String, Any?>,
        errors: MutableList<SchemaScopingValidationError>,
    ): List<DecodedEntry>? {
        if (!decoded.containsKey(SCOPED_SCHEMAS_KEY)) return null
        val raw = decoded[SCOPED_SCHEMAS_KEY] ?: return emptyList()
        if (raw !is List<*>) {
            errors += wrongType(SCOPED_SCHEMAS_KEY, "a list of { id, scopes } entries", raw)
            return null
        }
        return raw.mapIndexedNotNull { index, element ->
            decodeEntry(element, "$SCOPED_SCHEMAS_KEY[$index]", errors)
        }
    }

    private fun decodeEntry(
        element: Any?,
        path: String,
        errors: MutableList<SchemaScopingValidationError>,
    ): DecodedEntry? {
        if (element !is Map<*, *>) {
            errors += wrongType(path, "an object with an '$ENTRY_ID_KEY' and a '$ENTRY_SCOPES_KEY' list", element)
            return null
        }
        val before = errors.size
        val keys = element.keys.map { it.toString() }
        errors += unknownKeyErrors(keys, entryKeys, "in $path")

        val id = element[ENTRY_ID_KEY]
        if (id !is String) {
            errors += wrongType("$path.$ENTRY_ID_KEY", "a scoped-schema ID", id)
        }

        val scopes = when (val rawScopes = element[ENTRY_SCOPES_KEY]) {
            null -> emptyList()
            is List<*> -> rawScopes.mapIndexedNotNull { index, scope ->
                scopeIdElement(scope, "$path.$ENTRY_SCOPES_KEY[$index]", errors)
            }
            else -> {
                errors += wrongType("$path.$ENTRY_SCOPES_KEY", "a list of scope IDs", rawScopes)
                null
            }
        }
        if (errors.size != before || id !is String || scopes == null) return null
        return DecodedEntry(id, scopes)
    }

    private fun scopeIdElement(
        element: Any?,
        path: String,
        errors: MutableList<SchemaScopingValidationError>,
    ): String? =
        when (element) {
            is String -> element
            is Map<*, *> -> {
                val pair = element.entries.firstOrNull()
                when {
                    pair == null -> errors += wrongType(path, "a scope ID", element)
                    pair.value == null -> errors += SchemaScopingValidationError(
                        code = ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
                        message = "$path decoded as an object rather than a scope ID. A trailing colon starts a " +
                            "YAML mapping: write '- ${pair.key}' without it.",
                    )
                    else -> errors += SchemaScopingValidationError(
                        code = ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
                        message = "$path decoded as an object rather than a scope ID. A colon followed by a space " +
                            "starts a YAML mapping: write '- ${pair.key}:${pair.value}' with no space after the colon.",
                    )
                }
                null
            }
            else -> {
                errors += wrongType(path, "a scope ID", element)
                null
            }
        }

    private fun universeContentErrors(universe: List<String>?): List<SchemaScopingValidationError> {
        if (universe == null) return emptyList()
        val errors = mutableListOf<SchemaScopingValidationError>()
        if (universe.isEmpty()) {
            errors += SchemaScopingValidationError(
                code = ScopingErrorCodes.SCHEMA_SCOPES_EMPTY,
                message = "'$SCHEMA_SCOPES_KEY' is declared but lists no scope IDs. Omit the key entirely if this " +
                    "application defines no scopes; an empty list is not a way to say \"unscoped\".",
            )
            return errors
        }
        errors += duplicateErrors(
            universe,
            ScopingErrorCodes.SCHEMA_SCOPE_DUPLICATE_ID,
        ) { duplicates ->
            "'$SCHEMA_SCOPES_KEY' lists duplicate scope ID(s): $duplicates. Each scope ID may only appear once."
        }
        universe.forEach { id -> SchemaScopingValidator.validateScopeId(id)?.let { errors += it } }
        return errors
    }

    private fun entryContentErrors(entries: List<DecodedEntry>?): List<SchemaScopingValidationError> {
        if (entries == null) return emptyList()
        val errors = mutableListOf<SchemaScopingValidationError>()
        errors += duplicateErrors(
            entries.map { it.id },
            ScopingErrorCodes.SCOPED_SCHEMA_DUPLICATE_ID,
        ) { duplicates ->
            "'$SCOPED_SCHEMAS_KEY' declares duplicate '$ENTRY_ID_KEY' value(s): $duplicates. " +
                "Each scoped-schema ID may only appear once."
        }
        entries.forEach { entry ->
            SchemaScopingValidator.validateSchemaId(entry.id)?.let { errors += it }
            errors += duplicateErrors(
                entry.scopes,
                ScopingErrorCodes.SCHEMA_SCOPE_DUPLICATE_ID,
            ) { duplicates ->
                "Scoped schema '${entry.id}' lists duplicate scope ID(s): $duplicates."
            }
            entry.scopes.forEach { id -> SchemaScopingValidator.validateScopeId(id)?.let { errors += it } }
            if (entry.scopes.isEmpty()) {
                errors += SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPED_SCHEMA_EMPTY_SCOPES,
                    message = "Scoped schema '${entry.id}' selects no scopes. Every entry needs a non-empty " +
                        "'$ENTRY_SCOPES_KEY' list; ask for the unscoped schema by its reserved id " +
                        "${SchemaScopingValidator.BASE_SCHEMA_ID} at runtime instead.",
                )
            }
        }
        return errors
    }

    private fun versionErrors(decoded: Map<String, Any?>): List<SchemaScopingValidationError> {
        val declared = decoded[VERSION_KEY]?.toString()
        if (declared == null) {
            return listOf(
                SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPES_FILE_VERSION_MISSING,
                    message = "$SOURCE_FILE_NAME is not empty but declares no '$VERSION_KEY' value. Add " +
                        "'$VERSION_KEY: $SUPPORTED_VERSION' so a later release cannot read this file as a " +
                        "format it was not written for. An empty file needs no '$VERSION_KEY'.",
                ),
            )
        }
        return if (declared == SUPPORTED_VERSION) {
            emptyList()
        } else {
            listOf(
                SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPES_FILE_VERSION_UNSUPPORTED,
                    message = "$SOURCE_FILE_NAME declares '$VERSION_KEY: $declared', which this version of Viaduct " +
                        "does not support. Supported: $SUPPORTED_VERSION.",
                ),
            )
        }
    }

    private fun unknownKeyErrors(
        present: Collection<String>,
        known: Set<String>,
        location: String,
    ): List<SchemaScopingValidationError> {
        val unknown = (present - known).sorted()
        return if (unknown.isEmpty()) {
            emptyList()
        } else {
            listOf(
                SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPES_FILE_UNKNOWN_KEY,
                    message = "Unknown key(s) $unknown $location. Known keys: ${known.sorted()}.",
                ),
            )
        }
    }

    private fun duplicateErrors(
        values: List<String>,
        code: String,
        message: (List<String>) -> String,
    ): List<SchemaScopingValidationError> {
        val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        return if (duplicates.isEmpty()) {
            emptyList()
        } else {
            listOf(SchemaScopingValidationError(code = code, message = message(duplicates)))
        }
    }

    private fun wrongType(
        path: String,
        expected: String,
        actual: Any?,
    ): SchemaScopingValidationError =
        SchemaScopingValidationError(
            code = ScopingErrorCodes.SCOPES_FILE_WRONG_TYPE,
            message = "$path must be $expected, but was ${describe(actual)}.",
        )

    private fun describe(value: Any?): String =
        when (value) {
            null -> "empty"
            is Map<*, *> -> "an object"
            is List<*> -> "a list"
            else -> "${value::class.simpleName?.lowercase()} '$value'"
        }
}
