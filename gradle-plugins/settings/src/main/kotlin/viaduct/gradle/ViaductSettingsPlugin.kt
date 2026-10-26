package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.IsolatedAction
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings

abstract class ViaductSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val includeViaductApplication = IncludeViaductApplicationHandler(settings)
        settings.extensions.add("viaductTopologyDeclarations", includeViaductApplication)

        settings.gradle.settingsEvaluated {
            val topology = includeViaductApplication.buildTopology()
            settings.gradle.sharedServices.registerIfAbsent(
                ViaductTopologyService.NAME,
                ViaductTopologyService::class.java,
            ) {
                parameters.topologyJson.set(ViaductTopologyJson.encode(topology))
            }
            settings.gradle.lifecycle.beforeProject(
                InstallViaductProjectPlugins(includeViaductApplication.projectInstallations()),
            )
        }
    }
}

open class IncludeViaductApplicationHandler(private val settings: Settings) {
    private val declarations = mutableListOf<ViaductApplicationDeclaration>()

    fun include(configure: ViaductApplicationSpec.() -> Unit) {
        val declaration = ViaductApplicationDeclaration()
        val spec = ViaductApplicationSpec(settings, declaration)
        spec.configure()
        declarations += declaration
    }

    internal fun projectInstallations(): Map<String, ViaductProjectPluginInstallation> = buildMap {
        val registry = settings.viaductPluginRegistry().registrations
        declarations.forEach { application ->
            val registrations = application.enabledPlugins.map { registry.getValue(it) }
            put(
                application.projectPath!!,
                ViaductProjectPluginInstallation(
                    application = true,
                    module = application.modules.any { it.projectPath == application.projectPath },
                    applicationPlugins = registrations.map { it.applicationPlugin },
                    modulePlugins = registrations.mapNotNull { it.moduleStubPlugin },
                ),
            )
            application.modules.filter { it.projectPath != application.projectPath }.forEach { module ->
                put(
                    module.projectPath!!,
                    ViaductProjectPluginInstallation(
                        application = false,
                        module = true,
                        applicationPlugins = emptyList(),
                        modulePlugins = registrations.mapNotNull { it.moduleStubPlugin },
                    ),
                )
            }
        }
    }

    internal fun buildTopology(): ViaductApplicationMap {
        val errors = ViaductTopologyValidator.validate(declarations)
        if (errors.isNotEmpty()) {
            throw GradleException(
                "Viaduct settings topology configuration is invalid:\n" +
                    errors.joinToString("\n") { "  - [${it.code}] ${it.message}" },
            )
        }

        return ViaductApplicationMap(
            applicationTopologies = buildMap {
                declarations.forEach { application ->
                    val topology = ViaductApplicationTopology(
                        applicationProjectPath = application.projectPath!!,
                        modulePackagePrefix = application.modulePackagePrefix!!,
                        modulePackageSuffixes = application.modules.associate {
                            it.projectPath!! to it.modulePackageSuffix!!
                        },
                    )
                    put(topology.applicationProjectPath, topology)
                    topology.modulePackageSuffixes.keys.forEach { modulePath ->
                        put(modulePath, topology)
                    }
                }
            },
        )
    }
}

open class ViaductApplicationSpec internal constructor(
    private val settings: Settings,
    private val declaration: ViaductApplicationDeclaration,
) {
    fun usePlugin(id: String) {
        settings.pluginManager.apply(id)
        if (id !in settings.viaductPluginRegistry().registrations) {
            throw GradleException("Viaduct settings plugin '$id' did not call registerViaductPlugin before apply returned")
        }
        declaration.enabledPlugins += id
    }

    fun project(path: String): ProjectDescriptor {
        val normalized = ViaductProjectPaths.normalize(path)
        if (declaration.projectPath != null) {
            throw GradleException("includeViaductApplication.project(...) may only be called once.")
        }
        declaration.projectPath = normalized
        includeProject(settings, normalized)
        return settings.project(normalized)
    }

    fun modulePackagePrefix(modulePackagePrefix: String) {
        if (declaration.modulePackagePrefix != null) {
            throw GradleException("includeViaductApplication.modulePackagePrefix(...) may only be called once.")
        }
        declaration.modulePackagePrefix = modulePackagePrefix
    }

    fun includeModule(configure: ViaductModuleSpec.() -> Unit) {
        val moduleDeclaration = ViaductModuleDeclaration()
        val spec = ViaductModuleSpec(settings, moduleDeclaration)
        spec.configure()
        declaration.modules += moduleDeclaration
    }
}

open class ViaductModuleSpec internal constructor(
    private val settings: Settings,
    private val declaration: ViaductModuleDeclaration,
) {
    fun project(path: String): ProjectDescriptor {
        val normalized = ViaductProjectPaths.normalize(path)
        if (declaration.projectPath != null) {
            throw GradleException("includeModule.project(...) may only be called once.")
        }
        declaration.projectPath = normalized
        includeProject(settings, normalized)
        return settings.project(normalized)
    }

    fun modulePackageSuffix(modulePackageSuffix: String) {
        if (declaration.modulePackageSuffix != null) {
            throw GradleException("includeModule.modulePackageSuffix(...) may only be called once.")
        }
        declaration.modulePackageSuffix = modulePackageSuffix
    }
}

internal data class ViaductApplicationDeclaration(
    var projectPath: String? = null,
    var modulePackagePrefix: String? = null,
    val modules: MutableList<ViaductModuleDeclaration> = mutableListOf(),
    val enabledPlugins: MutableSet<String> = linkedSetOf(),
)

internal data class ViaductProjectPluginInstallation(
    val application: Boolean,
    val module: Boolean,
    val applicationPlugins: List<Class<out Plugin<Project>>>,
    val modulePlugins: List<Class<out Plugin<Project>>>,
)

private class InstallViaductProjectPlugins(
    private val installations: Map<String, ViaductProjectPluginInstallation>,
) : IsolatedAction<Project> {
    override fun execute(project: Project) {
        val installation = installations[project.path] ?: return
        if (installation.application) {
            project.pluginManager.apply(ViaductApplicationConventionsPlugin::class.java)
        }
        if (installation.module) {
            project.pluginManager.apply(ViaductModuleConventionsPlugin::class.java)
        }
        if (installation.application) {
            installation.applicationPlugins.forEach { project.pluginManager.apply(it) }
        }
        if (installation.module) {
            installation.modulePlugins.forEach { project.pluginManager.apply(it) }
        }
    }
}

internal data class ViaductModuleDeclaration(
    var projectPath: String? = null,
    var modulePackageSuffix: String? = null,
)

private fun includeProject(
    settings: Settings,
    path: String,
) {
    if (path != ":") {
        settings.include(path)
    }
}
