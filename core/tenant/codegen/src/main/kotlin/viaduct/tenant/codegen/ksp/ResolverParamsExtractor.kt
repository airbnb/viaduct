package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver as ResolverAnnotation
import viaduct.api.types.CompositeOutput
import viaduct.service.api.spi.TenantBootstrapper

private val RESOLVER_ANNOTATION = requireNotNull(ResolverAnnotation::class.qualifiedName)
private val FRAGMENT_FROM_ANNOTATION_FQN = requireNotNull(FragmentFromAnnotation::class.qualifiedName)

// FragmentFromAnnotation<CompositeOutput.NotComposite> is the "unset" type argument used by
// fragments that don't bind a concrete GRT; a NotComposite arg carries no type to check against.
private val NOT_COMPOSITE_FQN = requireNotNull(CompositeOutput.NotComposite::class.qualifiedName)

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
    fun extractByFile(): Map<KSFile, PerSourceDescriptorFile> {
        val groupedNodesByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Node>>()
        val groupedFieldsByFile = mutableMapOf<KSFile, MutableList<ResolverParams.Field>>()
        val groupedFragmentsByFile = mutableMapOf<KSFile, MutableList<NamedFragmentDescriptor>>()
        val groupedOperationsByFile = mutableMapOf<KSFile, MutableList<OperationDescriptor>>()

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

        val graphqlFragmentAnnotation = requireNotNull(GraphQLFragment::class.qualifiedName)
        resolver
            .getSymbolsWithAnnotation(graphqlFragmentAnnotation)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                collectNamedFragment(declaration, groupedFragmentsByFile)
            }

        val graphqlOperationAnnotation = requireNotNull(GraphQLOperation::class.qualifiedName)
        resolver
            .getSymbolsWithAnnotation(graphqlOperationAnnotation)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                collectNamedOperation(declaration, groupedOperationsByFile)
            }

        val bootstrapClassByFile = extractBootstrapClassByFile()

        val allFiles = (
            groupedNodesByFile.keys + groupedFieldsByFile.keys + bootstrapClassByFile.keys +
                groupedFragmentsByFile.keys + groupedOperationsByFile.keys
        )
            .toSortedSet(compareBy { it.filePath })

        val descriptorsByFile = allFiles.associateWith { file ->
            val classesInFile = file.declarations.filterIsInstance<KSClassDeclaration>().toList()
            PerSourceDescriptorFile(
                nodes = groupedNodesByFile[file]
                    .orEmpty()
                    .sortedWith(compareBy({ it.typeName }, { it.implFqn })),
                fields = groupedFieldsByFile[file]
                    .orEmpty()
                    .sortedWith(compareBy({ it.typeName }, { it.fieldName }, { it.implFqn })),
                grtPackagePrefix = extractGrtPackagePrefix(classesInFile),
                bootstrapClass = bootstrapClassByFile[file],
                namedFragments = groupedFragmentsByFile[file].orEmpty().sortedBy { it.text },
                namedOperations = groupedOperationsByFile[file].orEmpty().sortedBy { it.implFqn },
            )
        }.toSortedMap(compareBy { file -> file.filePath })

        logger.loggingRegistryExtractor(
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
                    logger.errorRegistryExtractor("@TenantBootstrapper class has no containing file", declaration)
                    return@mapNotNull null
                }
                val fqn = declaration.qualifiedName?.asString() ?: run {
                    logger.errorRegistryExtractor("@TenantBootstrapper class has no qualified name", declaration)
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

    private fun collectNamedFragment(
        declaration: KSClassDeclaration,
        groupedFragmentsByFile: MutableMap<KSFile, MutableList<NamedFragmentDescriptor>>,
    ) {
        if (declaration.classKind != ClassKind.OBJECT) {
            logger.errorRegistryExtractor(
                "@GraphQLFragment must be applied to a Kotlin object declaration, but {} is not an object.",
                declaration.simpleName.asString(),
            )
            return
        }

        val containingFile = declaration.containingFile ?: run {
            logger.warnRegistryExtractor(
                "Skipping @GraphQLFragment without containing file: {}",
                declaration.simpleName.asString(),
            )
            return
        }

        val fragmentText = declaration.annotations
            .first { it.shortName.asString() == "GraphQLFragment" }
            .arguments
            // KSP may expose the argument as positional (name == null) when written as
            // @GraphQLFragment("fragment ..."), or as named when written as @GraphQLFragment(value = "fragment ...").
            .firstOrNull { it.name?.asString() == "value" || it.name == null }
            ?.value as? String

        if (fragmentText.isNullOrBlank()) {
            logger.errorRegistryExtractor(
                "@GraphQLFragment value must not be blank on {}",
                declaration.simpleName.asString(),
            )
            return
        }

        groupedFragmentsByFile.getOrPut(containingFile) { mutableListOf() }.add(
            NamedFragmentDescriptor(text = fragmentText.trim(), grtTypeName = declaration.fragmentGrtTypeName()),
        )
    }

    /**
     * Resolves the simple name of the `FragmentFromAnnotation<T>` type argument (the GRT the fragment
     * object is declared on, e.g. `User`). Returns null when there is no such supertype or the type
     * argument can't be resolved, in which case the assembly-time GRT-vs-type-condition check is skipped.
     */
    private fun KSClassDeclaration.fragmentGrtTypeName(): String? {
        val fragmentFromAnnotation = superTypes
            .map { it.resolve() }
            .firstOrNull { it.declaration.qualifiedName?.asString() == FRAGMENT_FROM_ANNOTATION_FQN }
            ?: return null

        val grtDeclaration = fragmentFromAnnotation.arguments.firstOrNull()?.type?.resolve()?.declaration ?: return null
        if (grtDeclaration.qualifiedName?.asString() == NOT_COMPOSITE_FQN) return null
        return grtDeclaration.simpleName.asString()
    }

    private fun collectNamedOperation(
        declaration: KSClassDeclaration,
        groupedOperationsByFile: MutableMap<KSFile, MutableList<OperationDescriptor>>,
    ) {
        if (declaration.classKind != ClassKind.OBJECT) {
            logger.errorRegistryExtractor(
                "@GraphQLOperation must be applied to a Kotlin object declaration, but {} is not an object.",
                declaration.simpleName.asString(),
            )
            return
        }

        val containingFile = declaration.containingFile ?: run {
            logger.warnRegistryExtractor(
                "Skipping @GraphQLOperation without containing file: {}",
                declaration.simpleName.asString(),
            )
            return
        }

        val kind = declaration.operationKind() ?: run {
            logger.errorRegistryExtractor(
                "@GraphQLOperation object {} must extend QueryFromAnnotation or MutationFromAnnotation.",
                declaration.simpleName.asString(),
            )
            return
        }

        val operationText = declaration.annotations
            .first { it.shortName.asString() == "GraphQLOperation" }
            .arguments
            // KSP may expose the argument as positional (name == null) when written as
            // @GraphQLOperation("..."), or as named when written as @GraphQLOperation(value = "...").
            .firstOrNull { it.name?.asString() == "value" || it.name == null }
            ?.value as? String

        if (operationText.isNullOrBlank()) {
            logger.errorRegistryExtractor(
                "@GraphQLOperation value must not be blank on {}",
                declaration.simpleName.asString(),
            )
            return
        }

        groupedOperationsByFile.getOrPut(containingFile) { mutableListOf() }.add(
            OperationDescriptor(
                text = operationText.trim(),
                kind = kind,
                implFqn = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString(),
            ),
        )
    }

    /** Determines the operation kind from the @GraphQLOperation object's base class, or null if neither. */
    private fun KSClassDeclaration.operationKind(): OperationKind? {
        val baseNames = superTypes
            .mapNotNull { it.resolve().declaration.qualifiedName?.asString() }
            .toSet()
        return when {
            QueryFromAnnotation::class.qualifiedName in baseNames -> OperationKind.QUERY
            MutationFromAnnotation::class.qualifiedName in baseNames -> OperationKind.MUTATION
            else -> null
        }
    }
}
