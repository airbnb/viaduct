package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile

/**
 * Extracts file-scoped resolver descriptors from the current KSP compilation unit.
 *
 * The extractor walks all class declarations, including nested classes, reduces them
 * into [ResolverParams], and groups both node and field resolvers by their containing
 * source file.
 */
internal class ResolverParamsExtractor(
    private val resolver: Resolver,
    private val logger: KSPLogger,
) {
    fun extractByFile(): Map<KSFile, ResolverDescriptorFile> {
        val groupedNodesByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Node>>()
        val groupedFieldsByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Field>>()

        resolver
            .getAllFiles()
            .flatMap { file -> file.declarations.asSequence() }
            .flatMap { declaration -> declaration.selfAndNestedDeclarations() }
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                collectResolverParams(
                    declaration = declaration,
                    groupedNodesByFile = groupedNodesByFile,
                    groupedFieldsByFile = groupedFieldsByFile,
                )
            }

        val allFiles = (groupedNodesByFile.keys + groupedFieldsByFile.keys).toSortedSet(compareBy { it.filePath })

        val descriptorsByFile = allFiles.associateWith { file ->
            ResolverDescriptorFile(
                nodes = groupedNodesByFile[file]
                    .orEmpty()
                    .sortedWith(compareBy({ it.typeName }, { it.implFqn })),
                fields = groupedFieldsByFile[file]
                    .orEmpty()
                    .sortedWith(compareBy({ it.typeName }, { it.fieldName }, { it.implFqn })),
            )
        }.toSortedMap(compareBy { file -> file.filePath })

        logger.infoRegistryExtractor(
            "Descriptor files extracted: {}",
            descriptorsByFile.size,
        )

        return descriptorsByFile
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

private fun KSDeclaration.selfAndNestedDeclarations(): Sequence<KSDeclaration> {
    return sequence {
        yield(this@selfAndNestedDeclarations)

        if (this@selfAndNestedDeclarations is KSClassDeclaration) {
            for (nested in declarations) {
                yieldAll(nested.selfAndNestedDeclarations())
            }
        }
    }
}
