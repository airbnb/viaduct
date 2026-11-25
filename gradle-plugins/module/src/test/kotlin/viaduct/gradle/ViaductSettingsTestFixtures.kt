package viaduct.gradle

import java.io.File

internal fun File.writeViaductSettings(
    applicationProjectPath: String = ":",
    modulePackagePrefix: String = "com.example.test",
    modules: Map<String, String>,
    plainIncludes: List<String> = emptyList(),
    kotlinApi: Boolean = true,
) {
    val plainIncludeBlock = plainIncludes.joinToString("\n") { includePath ->
        """
        include("${includePath.removePrefix(":")}")
        """.trimIndent()
    }
    val moduleIncludeBlock = modules.entries.joinToString("\n\n") { (projectPath, suffix) ->
        """
        includeModule {
            project("$projectPath")
            modulePackageSuffix("$suffix")
        }
        """.trimIndent().prependIndent("    ")
    }
    val capability = if (kotlinApi) "usePlugin(\"com.airbnb.viaduct.kotlinapi\")" else ""
    val optionalPlainIncludes = plainIncludeBlock.takeIf { it.isNotBlank() }?.let { "\n$it\n" } ?: ""
    val optionalModuleIncludes = moduleIncludeBlock.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""

    writeText(
        """
        plugins {
            id("com.airbnb.viaduct.settings-gradle-plugin")
        }

        rootProject.name = "test"
        $optionalPlainIncludes
        includeViaductApplication {
            $capability
            project("$applicationProjectPath")
            modulePackagePrefix("$modulePackagePrefix")$optionalModuleIncludes
        }
        """.trimIndent()
    )
}
