package conventions

import buildroot.orchestrationRegistryService
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection

/**
 * Attribute distinguishing the three [org.gradle.testing.jacoco.tasks.JacocoReport] outputs a
 * consumer needs to aggregate coverage across projects: execution data, class directories, and
 * source directories. Producers (conventions.jacoco) publish one consumable configuration per
 * kind; consumers (core/build.gradle.kts) resolve one resolvable configuration per kind, so
 * coverage data flows through the dependency graph instead of one project reading another
 * project's `jacocoTestReport` task directly.
 */
val jacocoCoverageDataKind: Attribute<String> = Attribute.of("viaduct.jacocoCoverageData.kind", String::class.java)

object JacocoCoverageDataKind {
    const val EXECUTION_DATA = "execution-data"
    const val CLASS_DIRECTORIES = "class-directories"
    const val SOURCE_DIRECTORIES = "source-directories"
}

/**
 * Declares a dependencyScope + resolvable configuration pair for one [kind] of jacoco coverage
 * data (configuration names derived from [configurationBaseName], e.g. "ExecutionData"), and
 * lazily adds a project() dependency (targeting [elementsConfigurationName]) on every project
 * self-registered under the "jacocoCoverageData" key (see conventions.jacoco). Resolves each
 * contributing project's published artifacts through the dependency graph, instead of this
 * project reading each subproject's jacocoTestReport task directly.
 */
fun Project.jacocoCoverageDataIncoming(
    configurationBaseName: String,
    kind: String,
    elementsConfigurationName: String
): FileCollection {
    val orchestrationRegistry = orchestrationRegistryService()
    val scope = configurations.dependencyScope("jacoco${configurationBaseName}DependencyScope").get()
    val incoming = configurations.resolvable("jacoco${configurationBaseName}Incoming") {
        extendsFrom(scope)
        attributes { attribute(jacocoCoverageDataKind, kind) }
    }.get()

    scope.dependencies.addAllLater(
        provider {
            orchestrationRegistry.get().tasksFor("jacocoCoverageData").map { projectPath ->
                dependencies.project(
                    mapOf("path" to projectPath, "configuration" to elementsConfigurationName)
                )
            }
        }
    )

    return incoming.incoming.files
}
