package viaduct.gradle

import java.io.File
import java.net.URLClassLoader
import org.gradle.api.attributes.Attribute

object ViaductPluginCommon {
    val VIADUCT_KIND: Attribute<String> =
        Attribute.of("viaduct.kind", String::class.java)

    object Kind {
        const val SCHEMA_PARTITION = "schema-partition"
        const val CENTRAL_SCHEMA  = "central-schema"
        const val GRT_CLASSES = "grt-classes"
    }

    object Configs {
        /** Root/app: resolvable configuration that modules add their schema partitions to. */
        const val ALL_SCHEMA_PARTITIONS_INCOMING = "viaductAllSchemaPartitionsIn"

        /** Root/app: consumable configuration for the central schema file. */
        const val CENTRAL_SCHEMA_OUTGOING = "viaductCentralSchema"

        /** Root/app: consumable configuration for the generated GRT files. */
        const val GRT_CLASSES_OUTGOING = "viaductGRTClasses"


        /** Module: consumable configuration for a modules schema partition. */
        const val SCHEMA_PARTITION_OUTGOING = "viaductSchemaPartition"

        /** Module: resolvable configuration for the central schema file. */
        const val CENTRAL_SCHEMA_INCOMING = "viaductCentralSchemaIn"

        /** Module: resolvable configuration for the GRT class files. */
        const val GRT_CLASSES_INCOMING = "viaductGRTClassesIn"
    }

    // TODO: Must be a better way to do this.  Right now we are limited because we
    // can't include the class loader as a dependency into this plugins project
    // -- see note in settings.gradle.kts.
    fun getClassPathElements(anchor: Class<*>): List<File> =
        (anchor.classLoader as? URLClassLoader)
            ?.urLs
            ?.mapNotNull { url -> runCatching { File(url.toURI()) }.getOrNull() }
            .orEmpty()
}
