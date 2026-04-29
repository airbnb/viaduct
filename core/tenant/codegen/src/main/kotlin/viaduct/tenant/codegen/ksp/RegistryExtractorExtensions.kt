package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ResolverFor

private val nodeResolverForAnnotationName = requireNotNull(NodeResolverFor::class.simpleName)
private val resolverForAnnotationName = requireNotNull(ResolverFor::class.simpleName)
private val resolverAnnotationName = requireNotNull(Resolver::class.simpleName)
private val variableUnsetValue = Variable.UNSET_STRING_VALUE

/**
 * Converts a resolver implementation into the intermediate descriptor model consumed by the
 * registry extractor. Emits both node resolvers (@NodeResolverFor) and field resolvers
 * (@ResolverFor).
 */
internal fun KSClassDeclaration.toResolverParams(logger: KSPLogger): ResolverParams? {
    val implFqn = qualifiedResolverName(logger) ?: return null

    val directBaseDeclaration = directResolverBaseDeclaration(
        implFqn = implFqn,
        logger = logger,
    ) ?: return null

    val resolverBaseClass = directBaseDeclaration.qualifiedName?.asString() ?: run {
        logger.warnRegistryExtractor(
            "Skipping {} because base class has no qualified name",
            implFqn,
        )
        return null
    }

    val nodeResolverAnnotation = directBaseDeclaration.firstAnnotationNamed(nodeResolverForAnnotationName)
    if (nodeResolverAnnotation != null) {
        val resolverAnnotation = firstAnnotationNamed(resolverAnnotationName)
        if (resolverAnnotation == null) {
            logger.infoRegistryExtractor(
                "Skipping {} because it extends a @{} base but is not annotated with @{}",
                implFqn,
                nodeResolverForAnnotationName,
                resolverAnnotationName,
            )
            return null
        }

        val objectFragment = resolverAnnotation.stringArg("objectValueFragment")?.takeIf { it.isNotBlank() }
        val queryFragment = resolverAnnotation.stringArg("queryValueFragment")?.takeIf { it.isNotBlank() }
        val variables = resolverAnnotation.arguments
            .firstOrNull { it.name?.asString() == "variables" }
            ?.value as? List<*>
        if (objectFragment != null || queryFragment != null || !variables.isNullOrEmpty()) {
            logger.errorRegistryExtractor(
                "@Resolver on node resolver {} must not specify objectValueFragment, " +
                    "queryValueFragment, or variables. Node resolvers do not support required selection sets.",
                implFqn,
            )
            return null
        }

        return toNodeResolverParams(
            implFqn = implFqn,
            nodeResolverAnnotation = nodeResolverAnnotation,
            resolverBaseClass = resolverBaseClass,
            logger = logger,
        )
    }

    val resolverForAnnotation = directBaseDeclaration.firstAnnotationNamed(resolverForAnnotationName)
    if (resolverForAnnotation != null) {
        return toFieldResolverParams(
            implFqn = implFqn,
            resolverForAnnotation = resolverForAnnotation,
            resolverBaseClass = resolverBaseClass,
            logger = logger,
        )
    }

    return null
}

private fun KSClassDeclaration.directResolverBaseDeclaration(
    implFqn: String,
    logger: KSPLogger,
): KSClassDeclaration? {
    val annotatedBase = superTypes.toList()
        .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
        .firstOrNull { base ->
            base.firstAnnotationNamed(nodeResolverForAnnotationName) != null ||
                base.firstAnnotationNamed(resolverForAnnotationName) != null
        }

    if (annotatedBase == null) {
        logger.infoRegistryExtractor(
            "Skipping {} because no direct supertype is annotated with @{} or @{}",
            implFqn,
            nodeResolverForAnnotationName,
            resolverForAnnotationName,
        )
    }

    return annotatedBase
}

private fun KSClassDeclaration.toNodeResolverParams(
    implFqn: String,
    nodeResolverAnnotation: KSAnnotation,
    resolverBaseClass: String,
    logger: KSPLogger,
): ResolverParams.Node? {
    val typeName = nodeResolverAnnotation.stringArg("typeName") ?: run {
        logger.warnRegistryExtractor(
            "Skipping {} because @{} is missing typeName",
            implFqn,
            nodeResolverForAnnotationName,
        )
        return null
    }

    val isBatching = nodeResolverAnnotation.boolArg("isBatching") ?: run {
        logger.warnRegistryExtractor(
            "Skipping {} because @{} is missing isBatching",
            implFqn,
            nodeResolverForAnnotationName,
        )
        return null
    }

    val isSelective = nodeResolverAnnotation.boolArg("isSelective") ?: run {
        logger.warnRegistryExtractor(
            "Skipping {} because @{} is missing isSelective",
            implFqn,
            nodeResolverForAnnotationName,
        )
        return null
    }

    return ResolverParams.Node(
        implFqn = implFqn,
        typeName = typeName,
        resolverBaseClass = resolverBaseClass,
        isBatching = isBatching,
        isSelective = isSelective,
    )
}

internal fun KSClassDeclaration.firstAnnotationNamed(simpleName: String): KSAnnotation? {
    return annotations.firstOrNull { annotation ->
        annotation.shortName.asString() == simpleName
    }
}

internal fun KSAnnotation.stringArg(name: String): String? {
    return arguments.firstOrNull { argument ->
        argument.name?.asString() == name
    }?.value as? String
}

internal fun KSAnnotation.boolArg(name: String): Boolean? {
    return arguments.firstOrNull { argument ->
        argument.name?.asString() == name
    }?.value as? Boolean
}

private fun KSClassDeclaration.toFieldResolverParams(
    implFqn: String,
    resolverForAnnotation: KSAnnotation,
    resolverBaseClass: String,
    logger: KSPLogger,
): ResolverParams.Field? {
    val typeName = resolverForAnnotation.stringArg("typeName") ?: run {
        logger.warnRegistryExtractor(
            "Skipping {} because @{} is missing typeName",
            implFqn,
            resolverForAnnotationName,
        )
        return null
    }

    val fieldName = resolverForAnnotation.stringArg("fieldName") ?: run {
        logger.warnRegistryExtractor(
            "Skipping {} because @{} is missing fieldName",
            implFqn,
            resolverForAnnotationName,
        )
        return null
    }

    val isBatching = resolverForAnnotation.boolArg("isBatching") ?: false
    val isSelective = resolverForAnnotation.boolArg("isSelective") ?: false

    val resolverAnnotation = firstAnnotationNamed(resolverAnnotationName)
    val variablesTypeMap = variablesTypeMap()
    val variableProviders = resolverAnnotation?.variableProviders(variablesTypeMap) ?: emptyList()

    val objectFragment = resolverAnnotation?.stringArg("objectValueFragment")?.takeIf { it.isNotBlank() }
    val queryFragment = resolverAnnotation?.stringArg("queryValueFragment")?.takeIf { it.isNotBlank() }

    // Variable providers are attached to objectSelections by convention (they reference object fields).
    // querySelections has no variable providers in the current model.
    val objectSelections = objectFragment?.let {
        SelectionsBlock(selections = it, variablesProviders = variableProviders)
    }
    val querySelections = queryFragment?.let { SelectionsBlock(selections = it) }

    return ResolverParams.Field(
        implFqn = implFqn,
        typeName = typeName,
        fieldName = fieldName,
        resolverBaseClass = resolverBaseClass,
        isBatching = isBatching,
        isSelective = isSelective,
        objectSelections = objectSelections,
        querySelections = querySelections,
    )
}

private fun KSAnnotation.variableProviders(variablesTypeMap: Map<String, String>): List<VariableProviderDescriptor> {
    val varAnnotations = arguments
        .firstOrNull { it.name?.asString() == "variables" }
        ?.value as? List<*>
        ?: return emptyList()

    return varAnnotations.filterIsInstance<KSAnnotation>().mapNotNull { varAnn ->
        val name = varAnn.stringArg("name") ?: return@mapNotNull null
        // Kotlin annotation parameters can't default to null, so @Variable uses a sentinel string
        // to mean "this source was not set by the developer". Filter it out before checking which
        // source was actually provided.
        val fromObjectField = varAnn.stringArg("fromObjectField")?.takeIf { it != variableUnsetValue }
        val fromQueryField = varAnn.stringArg("fromQueryField")?.takeIf { it != variableUnsetValue }
        val fromArgument = varAnn.stringArg("fromArgument")?.takeIf { it != variableUnsetValue }

        val (kind, path) = when {
            fromObjectField != null -> "fromObjectField" to fromObjectField
            fromQueryField != null -> "fromQueryField" to fromQueryField
            fromArgument != null -> "fromArgument" to fromArgument
            else -> return@mapNotNull null
        }

        // Use the type from @Variables if present; fall back to empty string so the bootstrapper
        // can still iterate .keys to produce SelectionSetVariable instances at runtime.
        val providedVariables = mapOf(name to (variablesTypeMap[name] ?: ""))

        VariableProviderDescriptor(kind = kind, name = name, path = path, providedVariables = providedVariables)
    }
}

/**
 * Parses `@Variables(types = "foo: Int!, bar: Boolean")` from any nested class of this resolver.
 * Returns a map of variable name → type expression (e.g. `{"foo" -> "Int!", "bar" -> "Boolean"}`).
 * At most one nested `@Variables` annotation is expected (validated separately by the KSP validator).
 */
private fun KSClassDeclaration.variablesTypeMap(): Map<String, String> {
    val typesStr = declarations
        .filterIsInstance<KSClassDeclaration>()
        .flatMap { it.annotations }
        .firstOrNull { it.shortName.asString() == "Variables" }
        ?.stringArg("types")
        ?: return emptyMap()

    return typesStr.trim()
        .split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { entry ->
            val parts = entry.trim().split(":")
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val type = parts[1].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to type
        }
        .toMap()
}

internal fun KSClassDeclaration.qualifiedResolverName(logger: KSPLogger): String? {
    if (isLocal()) {
        logger.errorRegistryExtractor(
            "@Resolver is not supported on local classes: {}",
            simpleName.asString(),
        )
        return null
    }

    return qualifiedName?.asString() ?: run {
        logger.errorRegistryExtractor(
            "@Resolver must have a qualified name: {}",
            simpleName.asString(),
        )
        null
    }
}
