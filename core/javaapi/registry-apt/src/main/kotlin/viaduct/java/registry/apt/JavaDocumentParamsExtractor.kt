package viaduct.java.registry.apt

import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror
import viaduct.tenant.codegen.ksp.NamedFragmentDescriptor
import viaduct.tenant.codegen.ksp.OperationDescriptor
import viaduct.tenant.codegen.ksp.OperationKind

internal const val GRAPHQL_FRAGMENT_ANNOTATION_FQN = "viaduct.java.api.annotations.GraphQLFragment"
internal const val GRAPHQL_OPERATION_ANNOTATION_FQN = "viaduct.java.api.annotations.GraphQLOperation"
internal const val FRAGMENT_FROM_ANNOTATION_FQN = "viaduct.java.api.documents.FragmentFromAnnotation"
internal const val QUERY_FROM_ANNOTATION_FQN = "viaduct.java.api.documents.QueryFromAnnotation"
internal const val MUTATION_FROM_ANNOTATION_FQN = "viaduct.java.api.documents.MutationFromAnnotation"
internal const val COMPOSITE_OUTPUT_NONE_FQN = "viaduct.java.api.types.CompositeOutput.None"

internal data class ExtractedNamedFragment(
    val descriptor: NamedFragmentDescriptor,
    val grtPackagePrefix: String?,
)

/** Extracts Java named-fragment and operation declarations into the shared descriptor model. */
internal class JavaDocumentParamsExtractor(
    processingEnv: ProcessingEnvironment,
    private val onError: (String, Element) -> Unit,
) {
    private val elements = processingEnv.elementUtils
    private val types = processingEnv.typeUtils

    fun extractFragment(type: TypeElement): ExtractedNamedFragment? {
        val fragmentBase = findSupertype(type.asType(), FRAGMENT_FROM_ANNOTATION_FQN)
        if (fragmentBase == null) {
            onError(
                "@GraphQLFragment class ${type.qualifiedName} must extend FragmentFromAnnotation<T>.",
                type,
            )
            return null
        }

        val text = findAnnotation(type, GRAPHQL_FRAGMENT_ANNOTATION_FQN)
            ?.stringValue("value")
            ?.trim()
        if (text.isNullOrEmpty()) {
            onError("@GraphQLFragment value must not be blank on ${type.qualifiedName}.", type)
            return null
        }

        val grtType = fragmentBase.typeArguments.firstOrNull()
        val grtElement = (grtType as? DeclaredType)?.asElement() as? TypeElement
        val grtTypeName = grtElement
            ?.takeUnless { it.qualifiedName.toString() == COMPOSITE_OUTPUT_NONE_FQN }
            ?.simpleName
            ?.toString()
        val grtPackagePrefix = grtElement
            ?.let(elements::getPackageOf)
            ?.qualifiedName
            ?.toString()
            ?.takeIf { it.isNotEmpty() }

        return ExtractedNamedFragment(
            descriptor = NamedFragmentDescriptor(text = text, grtTypeName = grtTypeName),
            grtPackagePrefix = grtPackagePrefix,
        )
    }

    fun extractOperation(type: TypeElement): OperationDescriptor? {
        val kind = when {
            findSupertype(type.asType(), QUERY_FROM_ANNOTATION_FQN) != null -> OperationKind.QUERY
            findSupertype(type.asType(), MUTATION_FROM_ANNOTATION_FQN) != null -> OperationKind.MUTATION
            else -> {
                onError(
                    "@GraphQLOperation class ${type.qualifiedName} must extend " +
                        "QueryFromAnnotation or MutationFromAnnotation.",
                    type,
                )
                return null
            }
        }

        val text = findAnnotation(type, GRAPHQL_OPERATION_ANNOTATION_FQN)
            ?.stringValue("value")
            ?.trim()
        if (text.isNullOrEmpty()) {
            onError("@GraphQLOperation value must not be blank on ${type.qualifiedName}.", type)
            return null
        }

        return OperationDescriptor(
            text = text,
            kind = kind,
            implFqn = elements.getBinaryName(type).toString(),
        )
    }

    private fun findSupertype(
        type: TypeMirror,
        targetFqn: String,
    ): DeclaredType? {
        val declared = type as? DeclaredType ?: return null
        val element = declared.asElement() as? TypeElement ?: return null
        if (element.qualifiedName.toString() == targetFqn) return declared
        return types.directSupertypes(type).firstNotNullOfOrNull { findSupertype(it, targetFqn) }
    }

    private fun findAnnotation(
        element: Element,
        fqn: String,
    ): AnnotationMirror? =
        element.annotationMirrors.firstOrNull { mirror ->
            (mirror.annotationType.asElement() as? TypeElement)?.qualifiedName?.toString() == fqn
        }

    private fun AnnotationMirror.stringValue(name: String): String? =
        elements.getElementValuesWithDefaults(this).entries
            .firstOrNull { it.key.simpleName.toString() == name }
            ?.value
            ?.value as? String
}
