package viaduct.graphql.schema

import viaduct.graphql.utils.DefaultSchemaFactory

data class SchemaProvenance(
    val scope: String,
    val owner: String?,
    val generator: String?,
    val relativePath: String,
) {
    val supplyingPlugin: String? get() = generator ?: owner.takeIf { scope == "plugin" }
    val tenantModule: String? get() = owner?.let(::moduleNameFromId)?.takeIf { scope == "module" }

    companion object {
        const val PLUGIN_DIRECTORY = "schema/plugin"
        const val BUILTIN_SCHEMA_FILE = "$PLUGIN_DIRECTORY/com.airbnb.viaduct/source/BUILTIN_SCHEMA.graphqls"

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
            val prefix = if (scope == "plugin") PLUGIN_DIRECTORY else "schema/$scope"
            return prefix + (owner?.let { "/$it" } ?: "") + (generator?.let { "/generated/$it" } ?: "/source")
        }

        private fun requirePathSegment(value: String) {
            require(value.isNotBlank() && '/' !in value && value != "." && value != "..") { "Invalid schema provenance segment: $value" }
        }

        @JvmStatic
        fun parse(sourceName: String?): SchemaProvenance? {
            val parts = sourceName?.split('/') ?: return null
            if (parts.firstOrNull() != "schema") return null
            val scope = parts.getOrNull(1) ?: return null
            val originIndex = when (scope) {
                "application" -> 2
                "module", "plugin" -> 3
                else -> return null
            }
            val owner = if (originIndex == 3) parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null else null
            val origin = parts.getOrNull(originIndex) ?: return null
            val generator = when (origin) {
                "source" -> null
                "generated" -> parts.getOrNull(originIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
                else -> return null
            }
            val path = parts.drop(originIndex + if (generator == null) 1 else 2)
            if (path.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return SchemaProvenance(scope, owner, generator, path.joinToString("/"))
        }

        @JvmStatic
        fun isPluginScalar(scalar: ViaductSchema.Scalar): Boolean = isPluginScalar(scalar.name, scalar.sourceLocation?.sourceName)

        fun isPluginScalar(
            name: String,
            sourceName: String?
        ): Boolean = !DefaultSchemaFactory.isDefaultScalar(name) && parse(sourceName)?.supplyingPlugin != null
    }
}
