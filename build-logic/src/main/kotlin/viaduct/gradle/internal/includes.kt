package viaduct.gradle.internal

import java.io.File
import org.gradle.api.initialization.Settings

/**
 * Include a project with a given name that is different from the path.
 *
 * @param path The path to the project.
 * @param projectName The name to assign to the project. If null, the path will be used as the name replacing ":" with "-".
 */
fun Settings.includeNamed(
    path: String,
    rootPath: String = ".",
    projectName: String? = null
) {
    include(path)
    project(path).projectDir = File("$rootPath${path.replace(":", "/")}")
    val name = projectName ?: path.trimStart(':').replace(":", "-")
    project(path).name = name
}
