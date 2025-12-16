/**
 * Add this to integration-test projects (e.g., tenant/runtime-integration-tests)
 *
 * It will generate an exec file for the integration tests and then combine that
 * with the unit tests from the base project to generate a full coverage report
 */

import org.gradle.api.attributes.*
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import viaduct.gradle.internal.IntegrationCoverageExt

private val fullReportTaskName = "jacocoFullCoverageReport"

val ext = extensions.create(
    "viaductIntegrationCoverage",
    IntegrationCoverageExt::class,
    project,
    fullReportTaskName,
    objects,
)

// Configurations we will resolve from the base project's unit-test exec data
val incomingUnitExec by configurations.creating {
    description = "JaCoCo exec data from the base module's unit tests (included build)"
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.VERIFICATION))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("jacoco-exec"))
    }
}

// Configuration we will resolve from the base project's compiled class files
// This is a bit fragile: it's critical that _only_ the classes compiled from sources
// are included -- and that they come in as class files, not a JAR file.  Setting
// isTransitive to false is important, as is the LibraryElements.CLASSES
val baseRuntimeClasses by configurations.creating {
    description = "Runtime class directories of the base module (for coverage attribution)"
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
    }
}

// Configuration we will resolve from the base project's source files (optional - for better reports)
val baseRuntimeSources by configurations.creating {
    description = "Optional sources jars of the base module (for HTML report browsing)"
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
    }
}

val baseSourcesDir = layout.buildDirectory.dir("jacoco/base-sources")

// Unpack the (optional) sourcesElements from the base module
val unpackBaseSources by tasks.registering(Sync::class) {
    description = "Unpack base module sources for JaCoCo"

    into(baseSourcesDir.map { it.asFile })

    from(project.provider<Iterable<FileTree>> {
        configurations.named(baseRuntimeSources.name).map {
            cfg -> cfg.incoming.artifactView { isLenient = true }.artifacts.artifactFiles
        }.get().files.map { zipTree(it) }
    })

    // The following is not just a cross-project task-dependency, it's a cross-build one.
    // However, configurations alone were not enough to force execution of the :sourcesJar task.
    // TODO - fix this (maybe a proper task subclass would fix this).
    dependsOn(providers.provider {
        gradle.includedBuild(ext.includedBuildName.get()).task("${ext.baseProjectPath.get()}:sourcesJar")
    })
}

// helper to register either integration-test reports
fun registerCoverageTask(name: String) =
    tasks.register<JacocoReport>(name) {
        group = "verification"

        // Clear anything earlier plugins may have added
        // More fagility: we need to be careful because we're bringing in "exec" data
        // from an included build - don't want any surprises
        classDirectories.setFrom(emptyList<Any>())
        additionalClassDirs.setFrom(files())
        additionalSourceDirs.setFrom(files())
        sourceDirectories.setFrom(emptyList<Any>())

        // Classes whose coverage is being analyzed
        classDirectories.setFrom(
            configurations.named("baseRuntimeClasses").map { cfg ->
                // This is a FileCollection with proper builtBy; contains only directories when using classesElements
                cfg.incoming.artifactView { }.files
            }
        )

        // Source for classes whose coverage is being analyzed
        sourceDirectories.setFrom(baseSourcesDir.map { it.asFile })

        // Execution data from integration tests
        val itExecsProvider = providers.provider {
            tasks.withType<Test>().mapNotNull { t ->
                t.extensions.findByType(JacocoTaskExtension::class.java)?.destinationFile
            }
        }
        executionData.from(itExecsProvider)

        // For full report, add execution data from unit tests
        if (name == fullReportTaskName) {
            executionData.from(
                configurations.named("incomingUnitExec").map { it.incoming.artifactView { }.files }
            )
        }

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }

        // task dependencies
        dependsOn(tasks.withType<Test>()) // local integration tests
        dependsOn(unpackBaseSources)
    }

registerCoverageTask(fullReportTaskName)
registerCoverageTask("jacocoIntegrationOnlyReport")
