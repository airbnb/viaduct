package viaduct.graphql.schema

import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.utils.resources.ViaductResourcePaths

data class SchemaProvenance(
    val scope: String,
    val owner: String?,
    val generator: String?,
    val relativePath: String,
) {
    val supplyingPlugin: String? get() = generator ?: owner.takeIf { scope == "plugin" }
    val tenantModule: String? get() = owner?.let(::moduleNameFromId)?.takeIf { scope == "module" }

    companion object {
        const val PLUGIN_DIRECTORY = "viaduct/schema/plugin"
        const val BUILTIN_SCHEMA_FILE = "viaduct/central-schema/plugin/viaduct.core/generated/viaduct.core/BUILTIN_SCHEMA.graphqls"

        fun moduleIdFromPackage(
            tenantPackage: String,
            tenantPackagePrefix: String?,
        ): String {
            val packageName = tenantPackage.trim('.')
            val prefix = tenantPackagePrefix?.trim('.')?.takeIf(String::isNotBlank)
            val moduleId = if (prefix != null && packageName.startsWith("$prefix.")) packageName.removePrefix("$prefix.") else packageName
            requirePathSegment(moduleId)
            return moduleId
        }

        fun moduleNameFromId(moduleId: String): String = moduleId.replace('.', '/')

        fun pluginError(
            plugins: Collection<String>,
            message: String,
        ): String =
            "Internal framework/plugin error" +
                (if (plugins.isEmpty()) "" else " from '${plugins.distinct().joinToString("', '")}'") + ": $message"

        fun moduleSourceDirectory(moduleId: String): String = directory("module", moduleId)

        fun moduleGeneratedDirectory(
            moduleId: String,
            pluginId: String
        ): String = directory("module", moduleId, pluginId)

        fun pluginSourceDirectory(pluginId: String): String = directory("plugin", pluginId)

        fun pluginGeneratedDirectory(
            pluginId: String,
            generatorId: String
        ): String = directory("plugin", pluginId, generatorId)

        fun applicationSourceDirectory(partition: String): String {
            requirePathSegment(partition)
            return "${directory("application")}/$partition"
        }

        private fun directory(
            scope: String,
            owner: String? = null,
            generator: String? = null
        ): String {
            owner?.let(::requirePathSegment)
            generator?.let(::requirePathSegment)
            val prefix = if (scope == "plugin") PLUGIN_DIRECTORY else "viaduct/schema/$scope"
            return prefix + (owner?.let { "/$it" } ?: "") + (generator?.let { "/generated/$it" } ?: "/source")
        }

        private fun requirePathSegment(value: String) = ViaductResourcePaths.requireId(value)

        @JvmStatic
        fun parse(sourceName: String?): SchemaProvenance? {
            if (sourceName == null || runCatching { ViaductResourcePaths.validate(sourceName) }.isFailure) return null
            val parts = sourceName.split('/')
            val scope = parts[2]
            val originIndex = if (scope == "application") 3 else 4
            val generator = if (parts[originIndex] == "generated") parts[originIndex + 1] else null
            return SchemaProvenance(
                scope,
                if (scope == "application") null else parts[3],
                generator,
                parts.drop(originIndex + if (generator == null) 1 else 2).joinToString("/"),
            )
        }

        @JvmStatic
        fun isPluginScalar(scalar: ViaductSchema.Scalar): Boolean = isPluginScalar(scalar.name, scalar.sourceLocation?.sourceName)

        fun isPluginScalar(
            name: String,
            sourceName: String?
        ): Boolean = !DefaultSchemaFactory.isDefaultScalar(name) && parse(sourceName)?.supplyingPlugin != null
    }
}
