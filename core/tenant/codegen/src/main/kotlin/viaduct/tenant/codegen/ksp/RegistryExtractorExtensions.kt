package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ResolverFor
import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variable

private val nodeResolverForAnnotationName = requireNotNull(NodeResolverFor::class.simpleName)
private val resolverForAnnotationName = requireNotNull(ResolverFor::class.simpleName)
private val resolverAnnotationName = requireNotNull(Resolver::class.simpleName)
private val variableUnsetValue = Variable.UNSET_STRING_VALUE

// Resolver base classes whose final type argument is the resolver's return type.
// ResolverBase<R> — R is the only type arg.
// FieldResolverBase<O, Q, A, R> / ConnectionResolverBase<O, Q, A, R> — R is the last type arg.
// MutationResolverBase<Q, M, A, R> — R is the last type arg.
private val RESOLVER_BASE_SIMPLE_NAMES = setOf(
    "ResolverBase",
    "FieldResolverBase",
    "ConnectionResolverBase",
    "MutationResolverBase",
)

/**
 * Converts a resolver implementation into the intermediate descriptor model consumed by the
 * registry extractor. Emits both node resolvers (@NodeResolverFor) and field resolvers
 * (@ResolverFor).
 */
internal fun KSClassDeclaration.toResolverParams(logger: KSPLogger): ResolverParams? {
    val implFqn = qualifiedResolverName(logger) ?: return null

    val baseDeclaration = resolverBaseDeclaration(
        implFqn = implFqn,
        logger = logger,
    ) ?: return null

    if (baseDeclaration.qualifiedName == null) {
        logger.warnRegistryExtractor(
            "Skipping {} because base class has no qualified name",
            implFqn,
        )
        return null
    }
    val resolverBaseClass = baseDeclaration.jvmBinaryName()

    val nodeResolverAnnotation = baseDeclaration.firstAnnotationNamed(nodeResolverForAnnotationName)
    if (nodeResolverAnnotation != null) {
        val resolverAnnotation = firstAnnotationNamed(resolverAnnotationName)
        if (resolverAnnotation == null) {
            logger.loggingRegistryExtractor(
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

    val resolverForAnnotation = baseDeclaration.firstAnnotationNamed(resolverForAnnotationName)
    if (resolverForAnnotation != null) {
        return toFieldResolverParams(
            implFqn = implFqn,
            resolverForAnnotation = resolverForAnnotation,
            resolverBaseClass = resolverBaseClass,
            baseDeclaration = baseDeclaration,
            logger = logger,
        )
    }

    return null
}

private fun KSClassDeclaration.resolverBaseDeclaration(
    implFqn: String,
    logger: KSPLogger,
): KSClassDeclaration? {
    val annotatedBases = annotatedResolverBaseDeclarations()
    if (annotatedBases.size == 1) {
        return annotatedBases.single()
    }

    val annotationNames = "@$nodeResolverForAnnotationName or @$resolverForAnnotationName"
    if (annotatedBases.isEmpty()) {
        logger.errorRegistryExtractor(
            "@$resolverAnnotationName class {} must inherit from exactly one resolver base annotated " +
                "with {}, but none were found.",
            implFqn,
            annotationNames,
        )
    } else {
        val baseNames = annotatedBases
            .map { it.qualifiedName?.asString() ?: it.simpleName.asString() }
            .sorted()
        logger.errorRegistryExtractor(
            "@$resolverAnnotationName class {} must inherit from exactly one resolver base annotated " +
                "with {}, but found {}: {}",
            implFqn,
            annotationNames,
            annotatedBases.size,
            baseNames,
        )
    }
    return null
}

private fun KSClassDeclaration.annotatedResolverBaseDeclarations(): List<KSClassDeclaration> {
    val pending = ArrayDeque<KSClassDeclaration>()
    superTypes
        .mapNotNullTo(pending) { it.resolve().declaration as? KSClassDeclaration }
    val visited = mutableSetOf<String>()
    val annotatedBases = linkedMapOf<String, KSClassDeclaration>()

    while (pending.isNotEmpty()) {
        val declaration = pending.removeFirst()
        val declarationName = declaration.qualifiedName?.asString() ?: declaration.toString()
        if (!visited.add(declarationName)) continue

        if (
            declaration.firstAnnotationNamed(nodeResolverForAnnotationName) != null ||
            declaration.firstAnnotationNamed(resolverForAnnotationName) != null
        ) {
            annotatedBases[declarationName] = declaration
        }

        declaration.superTypes
            .mapNotNullTo(pending) { it.resolve().declaration as? KSClassDeclaration }
    }

    return annotatedBases.values.toList()
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
    baseDeclaration: KSClassDeclaration,
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

    // Variable providers go on objectSelections when it exists; otherwise fall back to querySelections.
    // This ensures @Variable bindings are reachable regardless of which fragment type the resolver uses.
    val objectSelections = objectFragment?.let {
        SelectionsBlock(selections = it, variablesProviders = variableProviders)
    }
    val querySelections = queryFragment?.let {
        SelectionsBlock(selections = it, variablesProviders = if (objectFragment == null) variableProviders else emptyList())
    }

    val contextTypeArgs = baseDeclaration.declarations
        .filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.simpleName.asString() == "Context" }
        ?.superTypes?.firstOrNull()?.resolve()?.arguments
        ?.mapNotNull { it.type?.resolve() }
        .orEmpty()

    // NoArguments is a unique sentinel — if absent among type args, the field has a generated Arguments class.
    var hasArguments = true
    var queryTypeName = "Query"
    for (typeArg in contextTypeArgs) {
        if (typeArg.declaration.qualifiedName?.asString() == "viaduct.api.types.Arguments.NoArguments") {
            hasArguments = false
        }
        if ((typeArg.declaration as? KSClassDeclaration)?.superTypes?.any {
                it.resolve().declaration.qualifiedName?.asString() == "viaduct.api.types.Query"
            } == true
        ) {
            queryTypeName = typeArg.declaration.simpleName.asString()
        }
    }

    val returnTypeName = baseDeclaration.superTypes
        .firstOrNull {
            it.resolve().declaration.simpleName.asString() in RESOLVER_BASE_SIMPLE_NAMES
        }
        ?.resolve()?.arguments?.lastOrNull()?.type?.resolve()
        ?.let { resolvedType ->
            if (resolvedType.declaration.simpleName.asString() == "List") {
                resolvedType.arguments.firstOrNull()?.type?.resolve()?.declaration?.simpleName?.asString()
            } else {
                resolvedType.declaration.simpleName.asString()
            }
        }

    return ResolverParams.Field(
        implFqn = implFqn,
        typeName = typeName,
        fieldName = fieldName,
        resolverBaseClass = resolverBaseClass,
        isBatching = isBatching,
        isSelective = isSelective,
        objectSelections = objectSelections,
        querySelections = querySelections,
        hasArguments = hasArguments,
        queryTypeName = queryTypeName,
        returnTypeName = returnTypeName,
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
 * Parses `@Variables(types = ["foo: Int!", "bar: Boolean"])` from any nested class of this resolver.
 * Returns a map of variable name → type expression (e.g. `{"foo" -> "Int!", "bar" -> "Boolean"}`).
 * At most one nested `@Variables` annotation is expected (validated separately by the KSP validator).
 */
private fun KSClassDeclaration.variablesTypeMap(): Map<String, String> {
    val typesList = declarations
        .filterIsInstance<KSClassDeclaration>()
        .flatMap { it.annotations }
        .firstOrNull { it.shortName.asString() == "Variables" }
        ?.arguments
        ?.firstOrNull { it.name?.asString() == "types" }
        ?.value as? List<*>
        ?: return emptyMap()

    return typesList.filterIsInstance<String>()
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

    if (qualifiedName == null) {
        logger.errorRegistryExtractor(
            "@Resolver must have a qualified name: {}",
            simpleName.asString(),
        )
        return null
    }

    return jvmBinaryName()
}

/**
 * Returns the JVM binary name for this class (e.g. `com.example.Outer$Inner`) by replacing
 * the `.` separators in the class-path segment with `$`. KSP's [qualifiedName] uses `.` for
 * all separators; `Class.forName` requires `$` for nested classes.
 */
private fun KSClassDeclaration.jvmBinaryName(): String {
    val pkg = packageName.asString()
    val qualName = requireNotNull(qualifiedName).asString()
    if (pkg.isEmpty()) return qualName.replace('.', '$')
    val classPath = qualName.removePrefix("$pkg.").replace('.', '$')
    return "$pkg.$classPath"
}

/**
 * Extracts the GRT package prefix from the given class declarations by inspecting resolver
 * base supertypes. Scoped to a specific source file's declarations so each descriptor gets
 * the prefix of its own resolvers rather than an arbitrary one from the whole compilation unit.
 */
internal fun extractGrtPackagePrefix(classDeclarations: List<KSClassDeclaration>): String? {
    return classDeclarations
        .asSequence()
        .flatMap { it.selfAndNestedClasses() }
        .mapNotNull { it.resolverBasePkg() }
        .firstOrNull()
}

private fun KSClassDeclaration.selfAndNestedClasses(): Sequence<KSClassDeclaration> =
    sequence {
        yield(this@selfAndNestedClasses)
        for (nested in declarations.filterIsInstance<KSClassDeclaration>()) {
            yieldAll(nested.selfAndNestedClasses())
        }
    }

private fun KSClassDeclaration.resolverBasePkg(): String? {
    val base = annotatedResolverBaseDeclarations().singleOrNull() ?: return null

    return pkgFromContextClass(base) ?: pkgFromNodeResolverBase(base)
}

private fun pkgFromContextClass(base: KSClassDeclaration): String? {
    val contextClass = base.declarations
        .filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.simpleName.asString() == "Context" } ?: return null

    return contextClass.superTypes
        .firstOrNull()
        ?.resolve()
        ?.arguments
        ?.firstOrNull()
        ?.type
        ?.resolve()
        ?.declaration
        ?.qualifiedName
        ?.asString()
        ?.substringBeforeLast('.')
        ?.takeIf { it.isNotEmpty() }
}

private fun pkgFromNodeResolverBase(base: KSClassDeclaration): String? {
    return base.superTypes
        .map { it.resolve() }
        .firstOrNull { it.declaration.simpleName.asString() == "NodeResolverBase" }
        ?.arguments
        ?.firstOrNull()
        ?.type
        ?.resolve()
        ?.declaration
        ?.qualifiedName
        ?.asString()
        ?.substringBeforeLast('.')
        ?.takeIf { it.isNotEmpty() }
}
