package viaduct.gradle.internal

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.named
import org.gradle.testing.jacoco.tasks.JacocoReport

open class IntegrationCoverageExt(
    private val project: Project,
    private val taskName: String,
    objects: ObjectFactory,
) {
    /**
     * Path (in included build!) to project containing subject code and unit tests,
     * e.g., ":tenant:tenant-runtime".
     */
    internal val baseProjectPath = objects.property(String::class.java)

    /** Name of build containing base project (e.g., "core"). */
    internal val includedBuildName = objects.property(String::class.java)

    /**
     * @param path Path (in root build) to project containing subject code and unit tests
     *
     * Assumed to be in form ":<includedBuildName>:**:<artifactId>"
     * e.g., ":core:tenant:tenant-runtime"
     * where
     *   - includedBuildName is the name of the build containing the project (eg, "core", "codegen")
     *   - artifactId is for the maven coord ("com.airbnb.viaduct:$artifactId")
     */
    fun baseProject(path: String) {
        if (!path.startsWith(":")) throw GradleException("Invalid project path: $path")
        val segments = path.split(':')

        val incBuild = segments[1] // ":core:tenant:tenant-runtime" -> "core"
        val basePath = ":" + segments.drop(2).joinToString(":") // ":core:tenant:tenant-runtime" -> ":tenant:tenant-runtime"
        val artifactId = segments.last() // ":core:tenant:tenant-runtime" -> "tenant-runtime"

        project.gradle.includedBuild(incBuild) // Validation: will throw a GradleException if no such build
        includedBuildName.set(incBuild)
        baseProjectPath.set(basePath)

        // Because they come from an included build, the project dependencies here need
        // to be expressed using coordinates, not project-paths -- dependency-substitution
        // is used to translate these _back_ into (included) projects
        val coord = "com.airbnb.viaduct:$artifactId"
        project.dependencies.apply {
            add("incomingUnitExec", coord)
            add("baseRuntimeClasses", coord)
            add("baseRuntimeSources", coord)
        }

        project.tasks.named<JacocoReport>(taskName).configure {
            if (taskName.contains("Full")) {
                description = "Unit + integration test coverage for $path"
            } else {
                description = "Integration test coverage for $path"
            }
        }
    }
}
