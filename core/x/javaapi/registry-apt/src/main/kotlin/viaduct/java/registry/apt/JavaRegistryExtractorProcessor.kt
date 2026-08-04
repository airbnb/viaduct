package viaduct.java.registry.apt

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic
import javax.tools.StandardLocation
import viaduct.tenant.codegen.ksp.OperationDescriptor
import viaduct.tenant.codegen.ksp.PerSourceDescriptorFile
import viaduct.tenant.codegen.ksp.ResolverParams
import viaduct.tenant.codegen.ksp.ResolverParamsJsonCodec

/**
 * javac annotation processor that emits one registry descriptor JSON per source file containing
 * at least one `@Resolver`-annotated Java class.
 *
 * This is the Java twin of the Kotlin KSP `RegistryExtractorProcessor`. It produces the exact same
 * [PerSourceDescriptorFile] JSON shape (reusing the shared model + codec from `:tenant:codegen`),
 * written to `viaduct-registry/<package-path>/<TopLevelClass>.json` in the annotation processor's
 * resource output. The aggregation CLI (`AssembleTenantModuleConfigFile`) then consolidates these
 * into `META-INF/viaduct/modules/<pkg>.json`, identical to the Kotlin pipeline — only the
 * `--executor-factory` differs (`ViaductJavaExecutorFactory`).
 *
 * ## Incrementality
 *
 * Each output file is created with its originating top-level type passed to the
 * [javax.annotation.processing.Filer]. This mirrors KSP isolation mode: a change to one source
 * file regenerates only its descriptor, and deletion removes it, because incremental javac tracks
 * the originating-element association.
 *
 * ## Scope
 *
 * - Field resolvers (`@ResolverFor` bases) and node resolvers (`@NodeResolverFor` bases) are both
 *   supported.
 * - There is no Java `@TenantBootstrapper` annotation today, so `bootstrapClass` is always null.
 */
@SupportedAnnotationTypes(
    RESOLVER_ANNOTATION_FQN,
    GRAPHQL_FRAGMENT_ANNOTATION_FQN,
    GRAPHQL_OPERATION_ANNOTATION_FQN,
)
class JavaRegistryExtractorProcessor : AbstractProcessor() {
    private val codec = ResolverParamsJsonCodec()

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun process(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment,
    ): Boolean {
        val reportError = { msg: String, element: Element ->
            processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element)
        }
        val resolverExtractor = JavaResolverParamsExtractor(processingEnv, reportError)
        val documentExtractor = JavaDocumentParamsExtractor(processingEnv, reportError)

        // Group all descriptors by their containing top-level source file. Document-only source
        // files must emit descriptors too, so named fragments and operations reach assembly even
        // when no resolver happens to share their file.
        val byTopLevel = mutableMapOf<TypeElement, SourceDescriptors>()

        for (type in annotatedTypes(roundEnv, RESOLVER_ANNOTATION_FQN)) {
            val extracted = resolverExtractor.extract(type) ?: continue
            val topLevel = topLevelType(type)
            byTopLevel.getOrPut(topLevel, ::SourceDescriptors).resolvers.add(extracted)
        }
        for (type in annotatedTypes(roundEnv, GRAPHQL_FRAGMENT_ANNOTATION_FQN)) {
            val extracted = documentExtractor.extractFragment(type) ?: continue
            val topLevel = topLevelType(type)
            byTopLevel.getOrPut(topLevel, ::SourceDescriptors).fragments.add(extracted)
        }
        for (type in annotatedTypes(roundEnv, GRAPHQL_OPERATION_ANNOTATION_FQN)) {
            val extracted = documentExtractor.extractOperation(type) ?: continue
            val topLevel = topLevelType(type)
            byTopLevel.getOrPut(topLevel, ::SourceDescriptors).operations.add(extracted)
        }

        for ((topLevel, descriptors) in byTopLevel) {
            writeDescriptor(topLevel, descriptors)
        }

        return false
    }

    private fun writeDescriptor(
        topLevel: TypeElement,
        descriptors: SourceDescriptors,
    ) {
        val nodes = descriptors.resolvers.map { it.params }.filterIsInstance<ResolverParams.Node>()
            .sortedWith(compareBy({ it.typeName }, { it.implFqn }))
        val fields = descriptors.resolvers.map { it.params }.filterIsInstance<ResolverParams.Field>()
            .sortedWith(compareBy({ it.typeName }, { it.fieldName }, { it.implFqn }))

        val grtPackagePrefix = descriptors.resolvers.firstNotNullOfOrNull { it.grtPackagePrefix }
            ?: descriptors.fragments.firstNotNullOfOrNull { it.grtPackagePrefix }

        val descriptor = PerSourceDescriptorFile(
            nodes = nodes,
            fields = fields,
            grtPackagePrefix = grtPackagePrefix,
            bootstrapClass = null,
            namedFragments = descriptors.fragments.map { it.descriptor }.sortedBy { it.text },
            namedOperations = descriptors.operations.sortedBy { it.implFqn },
        )
        if (descriptor.isEmpty()) return

        val json = codec.encode(descriptor)

        val packageName = processingEnv.elementUtils.getPackageOf(topLevel).qualifiedName.toString()
        val packagePath = packageName.replace('.', '/')
        val baseName = topLevel.simpleName.toString()
        val relativeName = if (packagePath.isEmpty()) {
            "$DESCRIPTOR_ROOT/$baseName.json"
        } else {
            "$DESCRIPTOR_ROOT/$packagePath/$baseName.json"
        }

        val resource = processingEnv.filer.createResource(
            StandardLocation.CLASS_OUTPUT,
            "",
            relativeName,
            topLevel,
        )
        OutputStreamWriter(resource.openOutputStream(), StandardCharsets.UTF_8).use { it.write(json) }
    }

    private fun topLevelType(element: TypeElement): TypeElement {
        var current: Element = element
        while (current.enclosingElement?.kind?.isTypeKind == true) {
            current = current.enclosingElement
        }
        return current as TypeElement
    }

    private val ElementKind.isTypeKind: Boolean
        get() = this == ElementKind.CLASS || this == ElementKind.INTERFACE || this == ElementKind.ENUM

    private fun annotatedTypes(
        roundEnv: RoundEnvironment,
        annotationFqn: String,
    ): List<TypeElement> {
        val annotation = processingEnv.elementUtils.getTypeElement(annotationFqn) ?: return emptyList()
        return roundEnv.getElementsAnnotatedWith(annotation).filterIsInstance<TypeElement>()
    }

    private data class SourceDescriptors(
        val resolvers: MutableList<ExtractedResolver> = mutableListOf(),
        val fragments: MutableList<ExtractedNamedFragment> = mutableListOf(),
        val operations: MutableList<OperationDescriptor> = mutableListOf(),
    )
}
