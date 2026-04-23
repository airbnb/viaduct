package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ResolverFor

private val nodeResolverForAnnotationName = requireNotNull(NodeResolverFor::class.simpleName)
private val resolverForAnnotationName = requireNotNull(ResolverFor::class.simpleName)

/**
 * Converts a resolver implementation into the intermediate descriptor model consumed by the
 * registry extractor.
 *
 * For now, this pass only emits node resolvers. Field resolvers are intentionally skipped and
 * will be handled in a later pass when we expand descriptor coverage.
 */
internal fun KSClassDeclaration.toResolverParams(logger: KSPLogger): ResolverParams? {
    val implFqn = qualifiedResolverName(logger) ?: return null

    val directBaseDeclaration = directResolverBaseDeclaration(
        implFqn = implFqn,
        logger = logger,
    ) ?: return null

    val nodeResolverAnnotation = directBaseDeclaration.firstAnnotationNamed(
        nodeResolverForAnnotationName,
    )
    if (nodeResolverAnnotation != null) {
        val resolverBaseClass = directBaseDeclaration.qualifiedName?.asString() ?: run {
            logger.warnRegistryExtractor(
                "Skipping {} because base class has no qualified name",
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

    // directBaseDeclaration is guaranteed to have @NodeResolverFor or @ResolverFor,
    // so reaching here means it is a field resolver — intentionally skipped in this pass.
    logger.infoRegistryExtractor(
        "Skipping field resolver {} in node-only pass",
        implFqn,
    )
    return null
}

private fun KSClassDeclaration.directResolverBaseDeclaration(
    implFqn: String,
    logger: KSPLogger,
): KSClassDeclaration? {
    val directSupertypes = superTypes.toList()
    if (directSupertypes.isEmpty()) {
        logger.infoRegistryExtractor(
            "Skipping {} because it has no direct supertypes",
            implFqn,
        )
        return null
    }

    val annotatedBase = directSupertypes
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
