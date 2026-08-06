package viaduct.java.registry.apt

import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror
import viaduct.tenant.codegen.ksp.ResolverParams
import viaduct.tenant.codegen.ksp.SelectionsBlock
import viaduct.tenant.codegen.ksp.VariableProviderDescriptor

internal const val RESOLVER_ANNOTATION_FQN = "viaduct.java.api.annotations.Resolver"
internal const val RESOLVER_FOR_FQN = "viaduct.java.api.annotations.ResolverFor"
internal const val NODE_RESOLVER_FOR_FQN = "viaduct.java.api.annotations.NodeResolverFor"
internal const val VARIABLE_FQN = "viaduct.java.api.annotations.Variable"
internal const val VARIABLES_FQN = "viaduct.java.api.annotations.Variables"

internal const val FIELD_RESOLVER_BASE_FQN = "viaduct.java.api.resolvers.FieldResolverBase"

/**
 * Connection field resolvers implement `ConnectionResolverBase<T, O, Q, A, R>`, which extends
 * `FieldResolverBase<T, O, Q, A, R>` with the same type-argument order. The extractor treats it as
 * an alias so a connection resolver's return/query/arguments types are read the same way.
 */
internal const val CONNECTION_RESOLVER_BASE_FQN = "viaduct.java.api.resolvers.ConnectionResolverBase"
internal const val NODE_RESOLVER_BASE_FQN = "viaduct.java.api.resolvers.NodeResolverBase"
internal const val ARGUMENTS_NO_ARGUMENTS_FQN = "viaduct.java.api.types.Arguments.NoArguments"

internal const val DESCRIPTOR_ROOT = "viaduct-registry"

/** A descriptor plus the GRT package prefix derived from the same type-argument inspection. */
internal data class ExtractedResolver(
    val params: ResolverParams,
    val grtPackagePrefix: String?,
)

/**
 * Converts a `@Resolver`-annotated Java [TypeElement] into a [ResolverParams] descriptor, mirroring
 * the Kotlin KSP `toResolverParams` logic in `RegistryExtractorExtensions.kt`.
 *
 * Java differs from Kotlin only in where the type information lives: the generated base implements
 * `FieldResolverBase<T, O, Q, A, S>` (or `NodeResolverBase<R>`) with concrete GRT type arguments,
 * and `@ResolverFor` / `@NodeResolverFor` carry the scalar metadata. We read both directly.
 */
internal class JavaResolverParamsExtractor(
    private val processingEnv: ProcessingEnvironment,
    private val onError: (String, Element) -> Unit,
) {
    private val elements = processingEnv.elementUtils
    private val types = processingEnv.typeUtils

    fun extract(impl: TypeElement): ExtractedResolver? {
        val implFqn = binaryName(impl) ?: return null

        val base = directResolverBase(impl) ?: return null
        val resolverBaseClass = binaryName(base) ?: return null

        nodeResolverForAnnotation(base)?.let { nodeAnn ->
            return extractNode(impl, base, implFqn, resolverBaseClass, nodeAnn)
        }
        resolverForAnnotation(base)?.let { fieldAnn ->
            return extractField(impl, base, implFqn, resolverBaseClass, fieldAnn)
        }
        return null
    }

    // ── Node resolvers ────────────────────────────────────────────────────────

    private fun extractNode(
        impl: TypeElement,
        base: TypeElement,
        implFqn: String,
        resolverBaseClass: String,
        nodeAnn: AnnotationMirror,
    ): ExtractedResolver? {
        val resolverAnn = resolverAnnotation(impl)
        if (resolverAnn != null) {
            val objectFragment = resolverAnn.stringValue("objectValueFragment")?.takeIf { it.isNotBlank() }
            val queryFragment = resolverAnn.stringValue("queryValueFragment")?.takeIf { it.isNotBlank() }
            val variables = resolverAnn.arrayValue("variables")
            if (objectFragment != null || queryFragment != null || variables.isNotEmpty()) {
                onError(
                    "@Resolver on node resolver $implFqn must not specify objectValueFragment, " +
                        "queryValueFragment, or variables. Node resolvers do not support required selection sets.",
                    impl,
                )
                return null
            }
        }

        val typeName = nodeAnn.stringValue("typeName") ?: return null
        val isBatching = nodeAnn.booleanValue("isBatching") ?: false
        val isSelective = nodeAnn.booleanValue("isSelective") ?: false

        val params = ResolverParams.Node(
            implFqn = implFqn,
            typeName = typeName,
            resolverBaseClass = resolverBaseClass,
            isBatching = isBatching,
            isSelective = isSelective,
        )
        return ExtractedResolver(params, nodeResolverGrtPackagePrefix(base))
    }

    // ── Field resolvers ─────────────────────────────────────────────────────────

    private fun extractField(
        impl: TypeElement,
        base: TypeElement,
        implFqn: String,
        resolverBaseClass: String,
        fieldAnn: AnnotationMirror,
    ): ExtractedResolver? {
        val typeName = fieldAnn.stringValue("typeName") ?: return null
        val fieldName = fieldAnn.stringValue("fieldName") ?: return null
        val isBatching = fieldAnn.booleanValue("isBatching") ?: false
        val isSelective = fieldAnn.booleanValue("isSelective") ?: false

        val resolverAnn = resolverAnnotation(impl)
        val variablesTypeMap = variablesTypeMap(impl)
        val variableProviders = resolverAnn?.let { variableProviders(it, variablesTypeMap) } ?: emptyList()

        val objectFragment = resolverAnn?.stringValue("objectValueFragment")?.takeIf { it.isNotBlank() }
        val queryFragment = resolverAnn?.stringValue("queryValueFragment")?.takeIf { it.isNotBlank() }

        // Variable providers go on objectSelections when it exists; otherwise fall back to
        // querySelections. Mirrors the Kotlin extractor.
        val objectSelections = objectFragment?.let {
            SelectionsBlock(selections = it, variablesProviders = variableProviders)
        }
        val querySelections = queryFragment?.let {
            SelectionsBlock(
                selections = it,
                variablesProviders = if (objectFragment == null) variableProviders else emptyList(),
            )
        }

        val baseTypeArgs = fieldResolverBaseTypeArgs(base)
        // FieldResolverBase<T, O, Q, A, S>: T = return, O = object, Q = query, A = arguments.
        val returnType = baseTypeArgs.getOrNull(0)
        val queryType = baseTypeArgs.getOrNull(2)
        val argumentsType = baseTypeArgs.getOrNull(3)

        val returnTypeName = returnType?.let { simpleNameUnwrappingList(it) }
        val queryTypeName = queryType?.let { simpleName(it) } ?: "Query"
        val hasArguments = argumentsType != null && !isArgumentsNone(argumentsType)

        val params = ResolverParams.Field(
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

        val grtPackagePrefix = baseTypeArgs.getOrNull(1)?.let { packageOf(it) }
            ?: returnType?.let { packageOf(it) }
        return ExtractedResolver(params, grtPackagePrefix)
    }

    // ── @Variable / @Variables ───────────────────────────────────────────────────

    private fun variableProviders(
        resolverAnn: AnnotationMirror,
        variablesTypeMap: Map<String, String>,
    ): List<VariableProviderDescriptor> =
        resolverAnn.arrayValue("variables").mapNotNull { varValue ->
            val varAnn = varValue.value as? AnnotationMirror ?: return@mapNotNull null
            val name = varAnn.stringValue("name") ?: return@mapNotNull null
            val fromObjectField = varAnn.stringValue("fromObjectField")?.takeIf { it.isNotEmpty() }
            val fromQueryField = varAnn.stringValue("fromQueryField")?.takeIf { it.isNotEmpty() }
            val fromArgument = varAnn.stringValue("fromArgument")?.takeIf { it.isNotEmpty() }

            val (kind, path) = when {
                fromObjectField != null -> "fromObjectField" to fromObjectField
                fromQueryField != null -> "fromQueryField" to fromQueryField
                fromArgument != null -> "fromArgument" to fromArgument
                else -> return@mapNotNull null
            }

            VariableProviderDescriptor(
                kind = kind,
                name = name,
                path = path,
                providedVariables = mapOf(name to (variablesTypeMap[name] ?: "")),
            )
        }

    /** Parses `@Variables(types = {"foo: Int!"})` on a nested class into name → type. */
    private fun variablesTypeMap(impl: TypeElement): Map<String, String> {
        val entries = impl.enclosedElements
            .filterIsInstance<TypeElement>()
            .firstNotNullOfOrNull { nested ->
                findAnnotation(nested, VARIABLES_FQN)?.arrayValue("types")
            }
            ?: return emptyMap()

        return entries.mapNotNull { entry ->
            val raw = entry.value as? String ?: return@mapNotNull null
            val parts = raw.trim().split(":")
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val type = parts[1].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to type
        }.toMap()
    }

    // ── Supertype / type-argument inspection ──────────────────────────────────────

    /** The direct supertype (superclass or interface) of [impl] carrying a resolver-base annotation. */
    private fun directResolverBase(impl: TypeElement): TypeElement? {
        val supers = buildList {
            (impl.superclass as? DeclaredType)?.let { add(it) }
            impl.interfaces.filterIsInstance<DeclaredType>().forEach { add(it) }
        }
        return supers.mapNotNull { it.asElement() as? TypeElement }
            .firstOrNull { base ->
                resolverForAnnotation(base) != null || nodeResolverForAnnotation(base) != null
            }
    }

    /** Type arguments of the base's `FieldResolverBase<...>` interface, in declaration order. */
    private fun fieldResolverBaseTypeArgs(base: TypeElement): List<TypeMirror> =
        base.interfaces
            .filterIsInstance<DeclaredType>()
            .firstOrNull {
                val fqn = (it.asElement() as? TypeElement)?.qualifiedName?.toString()
                fqn == FIELD_RESOLVER_BASE_FQN || fqn == CONNECTION_RESOLVER_BASE_FQN
            }
            ?.typeArguments
            ?: emptyList()

    /** GRT package prefix for a node resolver: package of `NodeResolverBase<R>`'s R argument. */
    private fun nodeResolverGrtPackagePrefix(base: TypeElement): String? =
        base.interfaces
            .filterIsInstance<DeclaredType>()
            .firstOrNull { (it.asElement() as? TypeElement)?.qualifiedName?.toString() == NODE_RESOLVER_BASE_FQN }
            ?.typeArguments?.firstOrNull()
            ?.let { packageOf(it) }

    private fun isArgumentsNone(type: TypeMirror): Boolean {
        val element = (type as? DeclaredType)?.asElement() as? TypeElement ?: return false
        return element.qualifiedName.toString() == ARGUMENTS_NO_ARGUMENTS_FQN
    }

    private fun simpleName(type: TypeMirror): String? {
        val element = (type as? DeclaredType)?.asElement() as? TypeElement ?: return null
        return element.simpleName.toString()
    }

    /** Simple name of [type], unwrapping `List<X>` to X (mirrors the Kotlin extractor). */
    private fun simpleNameUnwrappingList(type: TypeMirror): String? {
        val declared = type as? DeclaredType ?: return null
        val element = declared.asElement() as? TypeElement ?: return null
        if (element.simpleName.toString() == "List") {
            val inner = declared.typeArguments.firstOrNull() as? DeclaredType ?: return null
            return (inner.asElement() as? TypeElement)?.simpleName?.toString()
        }
        return element.simpleName.toString()
    }

    private fun packageOf(type: TypeMirror): String? {
        val element = (type as? DeclaredType)?.asElement() as? TypeElement ?: return null
        return elements.getPackageOf(element).qualifiedName.toString().takeIf { it.isNotEmpty() }
    }

    private fun binaryName(element: TypeElement): String? = elements.getBinaryName(element).toString().takeIf { it.isNotEmpty() }

    // ── Annotation helpers ────────────────────────────────────────────────────────

    private fun resolverAnnotation(element: TypeElement): AnnotationMirror? = findAnnotation(element, RESOLVER_ANNOTATION_FQN)

    private fun resolverForAnnotation(element: TypeElement): AnnotationMirror? = findAnnotation(element, RESOLVER_FOR_FQN)

    private fun nodeResolverForAnnotation(element: TypeElement): AnnotationMirror? = findAnnotation(element, NODE_RESOLVER_FOR_FQN)

    private fun findAnnotation(
        element: Element,
        fqn: String,
    ): AnnotationMirror? =
        element.annotationMirrors.firstOrNull { mirror ->
            (mirror.annotationType.asElement() as? TypeElement)?.qualifiedName?.toString() == fqn
        }

    private fun AnnotationMirror.value(name: String): AnnotationValue? {
        val withExplicit = elementValues.entries.firstOrNull { it.key.simpleName.toString() == name }?.value
        if (withExplicit != null) return withExplicit
        // Fall back to annotation defaults (e.g. isBatching default false, fragments default "").
        return elements.getElementValuesWithDefaults(this).entries
            .firstOrNull { it.key.simpleName.toString() == name }?.value
    }

    private fun AnnotationMirror.stringValue(name: String): String? = value(name)?.value as? String

    private fun AnnotationMirror.booleanValue(name: String): Boolean? = value(name)?.value as? Boolean

    @Suppress("UNCHECKED_CAST")
    private fun AnnotationMirror.arrayValue(name: String): List<AnnotationValue> = (value(name)?.value as? List<AnnotationValue>) ?: emptyList()
}
