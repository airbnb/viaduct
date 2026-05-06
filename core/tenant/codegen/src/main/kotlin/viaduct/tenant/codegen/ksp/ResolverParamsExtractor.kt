package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import viaduct.api.Resolver as ResolverAnnotation
import viaduct.service.api.spi.TenantBootstrapper

private val RESOLVER_ANNOTATION = requireNotNull(ResolverAnnotation::class.qualifiedName)

/**
 * Extracts file-scoped resolver descriptors from the current KSP compilation unit.
 *
 * The extractor finds all classes annotated with [ResolverAnnotation], reduces them
 * into [ResolverParams], and groups both node and field resolvers by their containing
 * source file. It also detects classes annotated with [TenantBootstrapper] and embeds
 * their FQN in the per-file descriptor. At most one [TenantBootstrapper] class may
 * appear per source file; a KSP error is emitted if more than one is found.
 */
internal class ResolverParamsExtractor(
    private val resolver: Resolver,
    private val logger: KSPLogger,
) {
    fun extractByFile(): Map<KSFile, ResolverDescriptorFile> {
        val groupedNodesByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Node>>()
        val groupedFieldsByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Field>>()

        resolver
            .getSymbolsWithAnnotation(RESOLVER_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                collectResolverParams(
                    declaration = declaration,
                    groupedNodesByFile = groupedNodesByFile,
                    groupedFieldsByFile = groupedFieldsByFile,
                )
            }

        val bootstrapClassByFile = extractBootstrapClassByFile()

        val allFiles = (groupedNodesByFile.keys + groupedFieldsByFile.keys + bootstrapClassByFile.keys)
            .toSortedSet(compareBy { it.filePath })

        val descriptorsByFile = allFiles.associateWith { file ->
            val classesInFile = file.declarations.filterIsInstance<KSClassDeclaration>().toList()
            ResolverDescriptorFile(
                nodes = groupedNodesByFile[file]
                    .orEmpty()
                    .sortedWith(compareBy({ it.typeName }, { it.implFqn })),
                fields = groupedFieldsByFile[file]
                    .orEmpty()
                    .sortedWith(compareBy({ it.typeName }, { it.fieldName }, { it.implFqn })),
                grtPackagePrefix = extractGrtPackagePrefix(classesInFile),
                bootstrapClass = bootstrapClassByFile[file],
            )
        }.toSortedMap(compareBy { file -> file.filePath })

        logger.infoRegistryExtractor(
            "Descriptor files extracted: {}",
            descriptorsByFile.size,
        )

        return descriptorsByFile
    }

    private fun extractBootstrapClassByFile(): Map<KSFile, String> {
        val annotationName = requireNotNull(TenantBootstrapper::class.qualifiedName)
        return resolver
            .getSymbolsWithAnnotation(annotationName)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { declaration ->
                val file = declaration.containingFile ?: run {
                    logger.error("@TenantBootstrapper class has no containing file", declaration)
                    return@mapNotNull null
                }
                val fqn = declaration.qualifiedName?.asString() ?: run {
                    logger.error("@TenantBootstrapper class has no qualified name", declaration)
                    return@mapNotNull null
                }
                file to fqn
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .entries
            .mapNotNull { (file, fqns) ->
                if (fqns.size > 1) {
                    logger.errorRegistryExtractor(
                        "Each source file may contain at most one @TenantBootstrapper class, but {} contains {}: {}",
                        file.filePath,
                        fqns.size,
                        fqns,
                    )
                    null
                } else {
                    file to fqns.single()
                }
            }
            .toMap()
    }

    private fun collectResolverParams(
        declaration: KSClassDeclaration,
        groupedNodesByFile: MutableMap<KSFile, MutableList<ResolverParams.Node>>,
        groupedFieldsByFile: MutableMap<KSFile, MutableList<ResolverParams.Field>>,
    ) {
        val containingFile = declaration.containingFile ?: run {
            logger.warnRegistryExtractor(
                "Skipping resolver without containing file: {}",
                declaration.simpleName.asString(),
            )
            return
        }

        when (val params = declaration.toResolverParams(logger)) {
            is ResolverParams.Node -> groupedNodesByFile.getOrPut(containingFile) { mutableListOf() }.add(params)
            is ResolverParams.Field -> groupedFieldsByFile.getOrPut(containingFile) { mutableListOf() }.add(params)
            null -> Unit
        }
    }
}
