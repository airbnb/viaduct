package viaduct.gradle

import java.io.File

internal fun File.writeViaductSettings(
    applicationProjectPath: String = ":",
    modulePackagePrefix: String = "com.example.test",
    modules: Map<String, String>,
    plainIncludes: List<String> = emptyList(),
    includedBuilds: List<File> = emptyList(),
) {
    val plainIncludeBlock = plainIncludes.joinToString("\n") { includePath ->
        """
        include("${includePath.removePrefix(":")}")
        """.trimIndent()
    }
    val includedBuildBlock = includedBuilds.joinToString("\n") { includedBuild ->
        """
        includeBuild("${includedBuild.invariantSeparatorsPath}")
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
    val optionalPlainIncludes = plainIncludeBlock.takeIf { it.isNotBlank() }?.let { "\n$it\n" } ?: ""
    val optionalIncludedBuilds = includedBuildBlock.takeIf { it.isNotBlank() }?.let { "\n$it\n" } ?: ""
    val optionalModuleIncludes = moduleIncludeBlock.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""

    writeText(
        """
        plugins {
            id("com.airbnb.viaduct.settings-gradle-plugin")
        }

        rootProject.name = "test"
        $optionalPlainIncludes$optionalIncludedBuilds
        includeViaductApplication {
            project("$applicationProjectPath")
            modulePackagePrefix("$modulePackagePrefix")$optionalModuleIncludes
        }
        """.trimIndent()
    )
}
