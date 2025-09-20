package viaduct.gradle.internal

import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.named
import org.gradle.testing.jacoco.tasks.JacocoReport

open class IntegrationCoverageExt(
    private val project: Project,
    private val taskName: String,
    objects: ObjectFactory,
) {
    internal val baseProjectPath = objects.property(String::class.java)

    /**
     * Project path of base project for these integration tests (e.g., ":tenant:tenant-runtime").
     */
    fun baseProject(path: String) {
        baseProjectPath.set(path)

        // Because they come from an included build, the project dependencies here need
        // to be expressed using coordinates, not project-paths -- dependency-substitution
        // is used to translate these _back_ into (included) projects
        val artifactId = path.split(':').last { it.isNotEmpty() } // ":tenant:tenant-runtime" -> "tenant-runtime"
        val coord = "com.airbnb.viaduct:$artifactId"
        project.dependencies.apply {
            add("incomingUnitExec", coord)
            add("baseRuntimeClasses", coord)
            add("baseRuntimeSources", coord)
        }

        project.tasks.named<JacocoReport>(taskName).configure {
            description = "Unit + integration test coverage for $path"
        }
    }
}
