package viaduct.graphql.schema.cli

import viaduct.graphql.schema.ViaductSchema

/**
 * Finds the longest common directory prefix among a list of paths.
 * The result always ends with "/" if non-empty, representing a directory.
 *
 * For example:
 * - ["/home/user/repo/modules/foo/", "/home/user/repo/modules/bar/"]
 *   returns "/home/user/repo/modules/"
 */
fun findCommonPrefix(paths: List<String>): String {
    if (paths.isEmpty()) return ""
    if (paths.size == 1) {
        // For a single path, return everything up to and including the last "/"
        val lastSlash = paths[0].lastIndexOf('/')
        return if (lastSlash >= 0) paths[0].substring(0, lastSlash + 1) else ""
    }

    val first = paths[0]
    var lastSlash = -1

    outer@ for (i in first.indices) {
        val c = first[i]
        for (path in paths) {
            if (i >= path.length || path[i] != c) {
                break@outer
            }
        }
        if (c == '/') {
            lastSlash = i
        }
    }

    // Return up to and including the last common "/"
    return if (lastSlash >= 0) first.substring(0, lastSlash + 1) else ""
}

/**
 * Extracts a module path from a source-file pathname, stripping a common prefix.
 *
 * GraphQL source files are expected to be in a subdirectory called "src".
 * This function extracts everything before "/src/" as the module path,
 * then strips the provided common prefix.
 */
fun extractTenant(
    sourceName: String?,
    commonPrefix: String = ""
): String {
    if (sourceName != null) {
        val m = MODULE_PATH_FINDER.find(sourceName)
        if (m != null) {
            val fullPath = m.groupValues[1]
            return if (commonPrefix.isNotEmpty() && fullPath.startsWith(commonPrefix)) {
                fullPath.substring(commonPrefix.length)
            } else {
                fullPath
            }
        }
    }
    return NO_TENANT
}

const val NO_TENANT = "NO_TENANT"

private val MODULE_PATH_FINDER by lazy {
    // Captures everything before the first "/src/" in the path (non-greedy)
    Regex("(.+?)/src/.*")
}

/**
 * Computes module paths for types and fields in a schema, with the
 * common prefix automatically stripped from all paths.
 */
class ModulePathComputer(schema: ViaductSchema) {
    private val commonPrefix: String

    init {
        // Collect all source paths from the schema
        val sourcePaths = mutableListOf<String>()
        for (def in schema.types.values) {
            def.sourceLocation?.sourceName?.let { sourcePaths.add(it) }
            if (def is ViaductSchema.Record) {
                for (field in def.fields) {
                    field.containingExtension.sourceLocation?.sourceName?.let { sourcePaths.add(it) }
                }
            }
        }

        // Extract module paths (everything before /src/) and find common prefix
        val modulePaths = sourcePaths.mapNotNull { path ->
            MODULE_PATH_FINDER.find(path)?.groupValues?.get(1)?.let { "$it/" }
        }
        commonPrefix = findCommonPrefix(modulePaths)
    }

    /** Returns the module path for a type. */
    fun tenantOf(def: ViaductSchema.TypeDef): String = extractTenant(def.sourceLocation?.sourceName, commonPrefix)

    /** Returns the module path for a field. */
    fun fieldTenantOf(field: ViaductSchema.Field): String = extractTenant(field.containingExtension.sourceLocation?.sourceName, commonPrefix)

    /** Returns the module path for a field's base type. */
    fun fieldBaseTenantOf(field: ViaductSchema.Field): String = extractTenant(field.type.baseTypeDef.sourceLocation?.sourceName, commonPrefix)

    /** Returns true iff field is in an inter-module extension. */
    fun isInExtension(field: ViaductSchema.Field): Boolean = fieldTenantOf(field) != tenantOf(field.containingDef)

    /** Returns true iff field's return type is from a different module. */
    fun hasExternalType(field: ViaductSchema.Field): Boolean = fieldTenantOf(field) != fieldBaseTenantOf(field)
}

/**
 * Returns the module path for a type, extracted from its source location.
 * Note: This does not strip common prefixes. Use [ModulePathComputer] for that.
 */
val ViaductSchema.TypeDef.tenant: String
    get() = extractTenant(this.sourceLocation?.sourceName)

/**
 * Returns the module path for a field, extracted from the field's
 * containing extension's source location.
 * Note: This does not strip common prefixes. Use [ModulePathComputer] for that.
 */
val ViaductSchema.Field.fieldTenant: String
    get() = extractTenant(this.containingExtension.sourceLocation?.sourceName)

/**
 * Returns true iff field is in an inter-module extension of the type,
 * i.e., if the module path of the field definition differs from the
 * module path of the containing type's base definition.
 */
val ViaductSchema.Field.inExtension: Boolean
    get() = this.fieldTenant != this.containingDef.tenant

/**
 * Returns true iff field is defined in one module but has a return type
 * defined in another module.
 */
val ViaductSchema.Field.hasExternalType: Boolean
    get() = this.fieldTenant != this.type.baseTypeDef.tenant
