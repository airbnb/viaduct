package viaduct.gradle.scaffold

/**
 * Data model used for template substitution when scaffolding a new Viaduct project.
 *
 * @property packagePrefix The root package for the generated project (e.g., "com.example.myapp")
 * @property projectName The name of the project, derived from the last segment of packagePrefix
 * @property gradleVersion The Gradle version for the wrapper
 * @property ktorVersion The Ktor version for dependencies
 * @property viaductVersion The Viaduct version for dependencies (automatically set from plugin version at runtime)
 */
data class ProjectModel(
    val packagePrefix: String,
    val projectName: String = packagePrefix.substringAfterLast('.'),
    val gradleVersion: String = "8.12",
    val ktorVersion: String = "3.0.3",
    val viaductVersion: String,
) {
    /** Package path for directory creation (dots replaced with slashes) */
    val packagePath: String = packagePrefix.replace('.', '/')
}
