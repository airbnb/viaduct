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
 * into [ResolverParams], and then groups node resolvers by their containing source file.
 * This pass is intentionally scoped to node resolvers; field descriptors remain part
 * of the model contract but are not emitted yet.
 */
internal class ResolverParamsExtractor(
    private val resolver: Resolver,
    private val logger: KSPLogger,
) {
    fun extractByFile(): Map<KSFile, ResolverDescriptorFile> {
        val groupedNodesByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Node>>()

        resolver
            .getAllFiles()
            .flatMap { file -> file.declarations.asSequence() }
            .flatMap { declaration -> declaration.selfAndNestedDeclarations() }
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                collectResolverParams(
                    declaration = declaration,
                    groupedNodesByFile = groupedNodesByFile,
                )
            }

        val descriptorsByFile = groupedNodesByFile
            .mapValues { (_, nodes) ->
                ResolverDescriptorFile(
                    nodes = nodes.sortedWith(compareBy({ it.typeName }, { it.implFqn })),
                    fields = emptyList(),
                )
            }
            .toSortedMap(compareBy { file -> file.filePath })

        logger.infoRegistryExtractor(
            "Descriptor files extracted: {}",
            descriptorsByFile.size,
        )

        return descriptorsByFile
    }

    private fun collectResolverParams(
        declaration: KSClassDeclaration,
        groupedNodesByFile: MutableMap<KSFile, MutableList<ResolverParams.Node>>,
    ) {
        val containingFile = declaration.containingFile ?: run {
            logger.warnRegistryExtractor(
                "Skipping resolver without containing file: {}",
                declaration.simpleName.asString(),
            )
            return
        }

        val params = declaration.toResolverParams(logger)
        if (params is ResolverParams.Node) {
            groupedNodesByFile.getOrPut(containingFile) { mutableListOf() }.add(params)
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
