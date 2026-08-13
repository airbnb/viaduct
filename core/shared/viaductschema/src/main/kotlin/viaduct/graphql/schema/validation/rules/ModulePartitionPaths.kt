package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema

/**
 * The '/'-separated form of this location's source name, for comparing against module partition
 * path prefixes.
 *
 * Source names reach these rules from `readTypesFromFiles`, which rewrites the platform separator
 * itself, and from producers that do not: binary-encoded schemas, SDL parsed from a string, and
 * direct `MultiSourceReader` callers. Normalizing here covers the latter, and lets the partition
 * path prefixes stay written as plain '/'-separated literals on every platform.
 */
internal fun ViaductSchema.SourceLocation.partitionMatchPath(): String = sourceName.replace('\\', '/')

/** True when [location] sits under a module partition directory. */
internal fun isUnderModulePartition(
    location: ViaductSchema.SourceLocation?,
    modulePathPrefix: String
): Boolean = location?.partitionMatchPath()?.contains(modulePathPrefix) == true
