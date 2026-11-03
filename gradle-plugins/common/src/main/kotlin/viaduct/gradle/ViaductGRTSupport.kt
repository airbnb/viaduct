package viaduct.gradle

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.bundling.Jar
import viaduct.apiannotations.InternalApi

@InternalApi
enum class ViaductGRTLanguage(
    val configurationPrefix: String,
    val capability: String,
    val jarTaskName: String,
) {
    KOTLIN("Kotlin", "com.airbnb.viaduct:kotlin-grt", "generateViaductGRTs"),
    JAVA("Java", "com.airbnb.viaduct:java-grt", "generateViaductJavaGRTs"),
}

@InternalApi
object ViaductGRTSupport {
    private const val REQUIREMENTS = "viaductModuleGRTRequirements"
    private val LANGUAGES = Attribute.of("viaduct.grt.languages", String::class.java)

    fun publishModuleRequirements(project: Project) {
        val languages = localRequirements(project)
        project.configurations.consumable(REQUIREMENTS) { variant ->
            variant.attributes { attributes ->
                requirementAttributes(project, attributes)
                attributes.attributeProvider(LANGUAGES, languages.map { it.sorted().joinToString(",") })
            }
        }
    }

    fun setupApplicationDependencies(
        project: Project,
        modules: NamedDomainObjectProvider<out Configuration>,
        language: ViaductGRTLanguage,
        artifact: Provider<RegularFile>,
    ) {
        val incoming = project.configurations.resolvable("viaduct${language.configurationPrefix}GRTRequirementsIn") { configuration ->
            configuration.extendsFrom(modules.get())
            configuration.isTransitive = false
            configuration.attributes { requirementAttributes(project, it) }
        }.get()
        val required = incoming.incoming.resolutionResult.rootComponent.map { root ->
            val languages = root.dependencies.flatMap { dependency ->
                if (dependency is UnresolvedDependencyResult) throw dependency.failure
                (dependency as ResolvedDependencyResult).resolvedVariant.attributes.getAttribute(LANGUAGES).orEmpty().split(",")
            }
            language.name in languages
        }.zip(localRequirements(project)) { fromModules, local -> fromModules || language.name in local }
        val present = artifact.map { listOf(it) }
        val absent = project.providers.provider { emptyList<RegularFile>() }
        val selected = required.flatMap { if (it) present else absent }
        project.configurations.matching { it.name == "api" }.configureEach { configuration ->
            project.dependencies.add(configuration.name, project.files(selected))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun localRequirements(project: Project): SetProperty<String> {
        val name = "viaductLocalGRTRequirements"
        val existing = project.extensions.findByName(name) as SetProperty<String>?
        return existing ?: project.objects.setProperty(String::class.java).convention(emptySet()).also {
            project.extensions.add(name, it)
        }
    }

    private fun requirementAttributes(
        project: Project,
        attributes: AttributeContainer
    ) {
        attributes.attribute(ViaductArtifacts.KIND, "module-grt-requirements")
        attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, ViaductArtifacts.USAGE))
    }

    fun publishVariants(
        project: Project,
        language: ViaductGRTLanguage,
        artifact: Provider<RegularFile>,
        version: String,
    ) {
        mapOf("Api" to Usage.JAVA_API, "Runtime" to Usage.JAVA_RUNTIME).forEach { (suffix, usage) ->
            project.configurations.consumable("viaduct${language.configurationPrefix}Grt${suffix}Elements") { variant ->
                variant.attributes { jvmJarAttributes(project, it, usage) }
                variant.outgoing.capability("${language.capability}:$version")
                variant.outgoing.artifact(artifact)
            }
        }
    }

    fun setupModuleDependencies(
        project: Project,
        application: NamedDomainObjectProvider<out Configuration>,
        language: ViaductGRTLanguage,
        applicationPlugin: Class<out Plugin<Project>>,
    ) {
        localRequirements(project).add(language.name)
        val dependencies = project.configurations.dependencyScope("viaduct${language.configurationPrefix}GrtDependencies").get()
        project.configurations.resolvable("viaduct${language.configurationPrefix}GRTClassesIn") { incoming ->
            incoming.extendsFrom(dependencies)
            incoming.attributes { jvmJarAttributes(project, it, Usage.JAVA_API) }
        }
        if (application.get().dependencies.withType(ProjectDependency::class.java).isEmpty()) {
            project.plugins.withType(applicationPlugin).configureEach {
                project.dependencies.add(
                    dependencies.name,
                    project.files(project.tasks.named(language.jarTaskName, Jar::class.java).flatMap { it.archiveFile }),
                )
            }
        } else {
            dependencies.dependencies.addAllLater(
                application.map { configuration ->
                    configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                        dependency.copy().apply {
                            capabilities { it.requireCapability(language.capability) }
                        }
                    }
                },
            )
        }
        project.pluginManager.withPlugin("java") {
            listOf("implementation", "testImplementation").forEach { name ->
                project.configurations.named(name).configure { it.extendsFrom(dependencies) }
            }
        }
        project.pluginManager.withPlugin("java-test-fixtures") {
            project.configurations.named("testFixturesImplementation").configure { it.extendsFrom(dependencies) }
        }
    }

    private fun jvmJarAttributes(
        project: Project,
        attributes: AttributeContainer,
        usage: String
    ) {
        attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, usage))
        attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }
}
