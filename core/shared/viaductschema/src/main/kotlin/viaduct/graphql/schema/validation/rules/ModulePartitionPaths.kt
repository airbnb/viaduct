package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema

/**
 * The '/'-separated form of this location's source name, for comparing against module partition
 * path prefixes.
 *
 * Source names come from two producers: URLs, whose paths always use '/', and [java.io.File] paths,
 * which use the platform separator. Normalizing here lets the partition path prefixes stay written
 * as plain '/'-separated literals and behave identically on every platform.
 */
internal fun ViaductSchema.SourceLocation.partitionMatchPath(): String = sourceName.replace('\\', '/')

/** True when [location] sits under a module partition directory. */
internal fun isUnderModulePartition(
    location: ViaductSchema.SourceLocation?,
    modulePathPrefix: String
): Boolean = location?.partitionMatchPath()?.contains(modulePathPrefix) == true
