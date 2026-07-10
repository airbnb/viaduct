package viaduct.gradle

internal object ViaductTopologyValidator {
    private const val APPLICATION_PROJECT_MISSING = "VIADUCT_APPLICATION_PROJECT_MISSING"
    private const val APPLICATION_PROJECT_PATH_INVALID = "VIADUCT_APPLICATION_PROJECT_PATH_INVALID"
    private const val APPLICATION_ROOT_DUPLICATE = "VIADUCT_APPLICATION_ROOT_DUPLICATE"
    private const val APPLICATION_ROOT_OVERLAP = "VIADUCT_APPLICATION_ROOT_OVERLAP"
    private const val MODULE_PACKAGE_PREFIX_MISSING = "MODULE_PACKAGE_PREFIX_MISSING"
    private const val MODULE_PACKAGE_PREFIX_INVALID = "MODULE_PACKAGE_PREFIX_INVALID"
    private const val MODULE_PROJECT_MISSING = "VIADUCT_MODULE_PROJECT_MISSING"
    private const val MODULE_PROJECT_PATH_INVALID = "VIADUCT_MODULE_PROJECT_PATH_INVALID"
    private const val MODULE_OUTSIDE_APPLICATION = "VIADUCT_MODULE_OUTSIDE_APPLICATION"
    private const val MODULE_PROJECT_DUPLICATE = "VIADUCT_MODULE_PROJECT_DUPLICATE"
    private const val MODULE_PACKAGE_SUFFIX_MISSING = "MODULE_PACKAGE_SUFFIX_MISSING"
    private const val MODULE_PACKAGE_SUFFIX_INVALID = "MODULE_PACKAGE_SUFFIX_INVALID"

    fun validate(declarations: List<ViaductApplicationDeclaration>): List<ViaductPluginValidationError> {
        val errors = mutableListOf<ViaductPluginValidationError>()

        declarations.forEach { application ->
            validateApplication(application, errors)
            application.modules.forEach { module ->
                validateModule(application, module, errors)
            }
            errors += ModuleSuffixValidator.validate(
                application.modules
                    .filter { it.projectPath != null && it.modulePackageSuffix != null && isPackageSuffix(it.modulePackageSuffix!!) }
                    .map { ModuleSuffixEntry(it.projectPath!!, it.modulePackageSuffix!!) },
            )
        }

        errors += validateApplicationRoots(declarations)
        errors += validateDuplicateModules(declarations)

        return errors
    }

    private fun validateApplication(
        application: ViaductApplicationDeclaration,
        errors: MutableList<ViaductPluginValidationError>,
    ) {
        val path = application.projectPath
        when {
            path == null ->
                errors += ViaductPluginValidationError(
                    APPLICATION_PROJECT_MISSING,
                    "includeViaductApplication must declare project(\":path\").",
                )
            !ViaductProjectPaths.isValid(path) ->
                errors += ViaductPluginValidationError(
                    APPLICATION_PROJECT_PATH_INVALID,
                    "Viaduct application project path '$path' is not a valid Gradle project path.",
                )
        }

        val prefix = application.modulePackagePrefix
        when {
            prefix == null ->
                errors += ViaductPluginValidationError(
                    MODULE_PACKAGE_PREFIX_MISSING,
                    "Viaduct application ${path ?: "<unknown>"} must declare modulePackagePrefix(\"com.example\").",
                )
            !isPackagePrefix(prefix) ->
                errors += ViaductPluginValidationError(
                    MODULE_PACKAGE_PREFIX_INVALID,
                    "Viaduct application ${path ?: "<unknown>"} declares invalid modulePackagePrefix '$prefix'.",
                )
        }
    }

    private fun validateModule(
        application: ViaductApplicationDeclaration,
        module: ViaductModuleDeclaration,
        errors: MutableList<ViaductPluginValidationError>,
    ) {
        val appPath = application.projectPath
        val modulePath = module.projectPath
        when {
            modulePath == null ->
                errors += ViaductPluginValidationError(
                    MODULE_PROJECT_MISSING,
                    "includeModule in application ${appPath ?: "<unknown>"} must declare project(\":path\").",
                )
            !ViaductProjectPaths.isValid(modulePath) ->
                errors += ViaductPluginValidationError(
                    MODULE_PROJECT_PATH_INVALID,
                    "Viaduct module project path '$modulePath' is not a valid Gradle project path.",
                )
            appPath != null && ViaductProjectPaths.isValid(appPath) && !ViaductProjectPaths.isSameOrDescendant(modulePath, appPath) ->
                errors += ViaductPluginValidationError(
                    MODULE_OUTSIDE_APPLICATION,
                    "Viaduct module '$modulePath' must be the application project or a descendant of application '$appPath'.",
                )
        }

        val suffix = module.modulePackageSuffix
        when {
            suffix == null ->
                errors += ViaductPluginValidationError(
                    MODULE_PACKAGE_SUFFIX_MISSING,
                    "Viaduct module ${modulePath ?: "<unknown>"} must declare modulePackageSuffix(\"name\").",
                )
            !isPackageSuffix(suffix) ->
                errors += ViaductPluginValidationError(
                    MODULE_PACKAGE_SUFFIX_INVALID,
                    "Viaduct module ${modulePath ?: "<unknown>"} declares invalid modulePackageSuffix '$suffix'.",
                )
        }
    }

    private fun validateApplicationRoots(declarations: List<ViaductApplicationDeclaration>): List<ViaductPluginValidationError> {
        val validPaths = declarations.mapNotNull { it.projectPath }.filter { ViaductProjectPaths.isValid(it) }
        val errors = mutableListOf<ViaductPluginValidationError>()

        validPaths.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { duplicate ->
            errors += ViaductPluginValidationError(
                APPLICATION_ROOT_DUPLICATE,
                "Viaduct application root '$duplicate' is declared more than once.",
            )
        }

        for (i in validPaths.indices) {
            for (j in i + 1 until validPaths.size) {
                val a = validPaths[i]
                val b = validPaths[j]
                if (a != b && (ViaductProjectPaths.isSameOrDescendant(a, b) || ViaductProjectPaths.isSameOrDescendant(b, a))) {
                    errors += ViaductPluginValidationError(
                        APPLICATION_ROOT_OVERLAP,
                        "Viaduct application roots '$a' and '$b' overlap. Application roots must be disjoint.",
                    )
                }
            }
        }

        return errors
    }

    private fun validateDuplicateModules(declarations: List<ViaductApplicationDeclaration>): List<ViaductPluginValidationError> =
        declarations
            .flatMap { it.modules }
            .mapNotNull { it.projectPath }
            .filter { ViaductProjectPaths.isValid(it) }
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .map { duplicate ->
                ViaductPluginValidationError(
                    MODULE_PROJECT_DUPLICATE,
                    "Viaduct module '$duplicate' is declared more than once.",
                )
            }

    private fun isPackagePrefix(value: String): Boolean = value.isNotBlank() && value.split(".").all(::isJavaIdentifier)

    private fun isPackageSuffix(value: String): Boolean = value.isBlank() || value.split(".").all(::isJavaIdentifier)

    private fun isJavaIdentifier(value: String): Boolean =
        value.isNotEmpty() &&
            Character.isJavaIdentifierStart(value[0]) &&
            value.drop(1).all(Character::isJavaIdentifierPart)
}

internal object ViaductProjectPaths {
    fun normalize(path: String): String = if (path.startsWith(":")) path else ":$path"

    fun isValid(path: String): Boolean {
        return path == ":" ||
            (path.startsWith(":") && !path.endsWith(":") && path.split(":").drop(1).all { it.isNotBlank() })
    }

    fun isSameOrDescendant(
        path: String,
        candidateAncestor: String,
    ): Boolean = path == candidateAncestor || candidateAncestor == ":" || path.startsWith("$candidateAncestor:")
}
