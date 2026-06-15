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
import viaduct.tenant.codegen.ksp.ResolverDescriptorFile
import viaduct.tenant.codegen.ksp.ResolverParams
import viaduct.tenant.codegen.ksp.ResolverParamsJsonCodec

/**
 * javac annotation processor that emits one registry descriptor JSON per source file containing
 * at least one `@Resolver`-annotated Java class.
 *
 * This is the Java twin of the Kotlin KSP `RegistryExtractorProcessor`. It produces the exact same
 * [ResolverDescriptorFile] JSON shape (reusing the shared model + codec from `:tenant:codegen`),
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
@SupportedAnnotationTypes(RESOLVER_ANNOTATION_FQN)
class JavaRegistryExtractorProcessor : AbstractProcessor() {
    private val codec = ResolverParamsJsonCodec()

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun process(
        annotations: Set<TypeElement>,
        roundEnv: RoundEnvironment,
    ): Boolean {
        val resolverAnnotation = processingEnv.elementUtils.getTypeElement(RESOLVER_ANNOTATION_FQN)
            ?: return false

        val annotatedTypes = roundEnv.getElementsAnnotatedWith(resolverAnnotation)
            .filterIsInstance<TypeElement>()
        if (annotatedTypes.isEmpty()) return false

        val extractor = JavaResolverParamsExtractor(processingEnv) { msg, element ->
            processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg, element)
        }

        // Group resolver descriptors by their containing top-level source file. The descriptor
        // file name and originating element both derive from that top-level type, mirroring KSP's
        // per-source-file isolation.
        val byTopLevel = mutableMapOf<TypeElement, MutableList<ExtractedResolver>>()
        for (type in annotatedTypes) {
            val extracted = extractor.extract(type) ?: continue
            val topLevel = topLevelType(type)
            byTopLevel.getOrPut(topLevel) { mutableListOf() }.add(extracted)
        }

        for ((topLevel, extractedList) in byTopLevel) {
            writeDescriptor(topLevel, extractedList)
        }

        return false
    }

    private fun writeDescriptor(
        topLevel: TypeElement,
        extractedList: List<ExtractedResolver>,
    ) {
        val nodes = extractedList.map { it.params }.filterIsInstance<ResolverParams.Node>()
            .sortedWith(compareBy({ it.typeName }, { it.implFqn }))
        val fields = extractedList.map { it.params }.filterIsInstance<ResolverParams.Field>()
            .sortedWith(compareBy({ it.typeName }, { it.fieldName }, { it.implFqn }))

        val grtPackagePrefix = extractedList.firstNotNullOfOrNull { it.grtPackagePrefix }

        val descriptor = ResolverDescriptorFile(
            nodes = nodes,
            fields = fields,
            grtPackagePrefix = grtPackagePrefix,
            bootstrapClass = null,
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
}
