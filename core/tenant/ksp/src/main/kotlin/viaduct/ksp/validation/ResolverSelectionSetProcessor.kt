package viaduct.ksp.validation

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.Validator
import java.io.File
import java.lang.IllegalArgumentException

/**
 * Processes @Resolver annotations at compile-time.
 *
 * Responsibilities:
 * 1. Extract fragments (objectValueFragment, queryValueFragment) and @Variable declarations
 * 2. Handle conversion from shorthand to longhand form if necessary
 * 3. Validate fragments against the GraphQL compilation schema
 * 4. Validate variable declarations (source constraints, path resolution, unused/unbound variables)
 * 5. Generate resolver-extracted-fragments.graphql containing all extracted [ResolverFragmentSpec]
 */
class ResolverSelectionSetProcessor(
    environment: SymbolProcessorEnvironment,
    private val fragmentValidator: Validator,
) : SymbolProcessor {
    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger
    private val fragmentsOutputFile: String? = environment.options[FRAGMENTS_OUTPUT_OPTION]

    private fun getCompilationSchemaSDL(resolver: Resolver): String {
        val compilationSchemaFile = resolver.findCompilationSchemaFile()
            ?: throw RuntimeException("Unable to read compilation schema SDL to validate resolver required selection set.")

        return compilationSchemaFile.unwrapSchemaFromFile()
    }

    private fun Resolver.findCompilationSchemaFile(): File? {
        return this.getAllFiles().firstOrNull {
            it.fileName == CompilationSchemaWrapperKtUtils.COMPILATION_SCHEMA_WRAPPER_KT_FILE
        }?.let { File(it.filePath) }
    }

    private fun File.unwrapSchemaFromFile(): String {
        return CompilationSchemaWrapperKtUtils.unwrapCompilationSchemaSDLFromWrapperKt(this)
    }

    // Track if we've already generated the file in this compilation session
    private var hasGeneratedFragments = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                getCompilationSchemaSDL(resolver)
            )
        )

        // Extract @Resolver annotations from all declarations
        val annotationSpecs = mutableListOf<ResolverAnnotationSpec>()
        val resolverFiles = mutableSetOf<KSFile>()

        resolver.getAllFiles().forEach { file ->
            resolverFiles.add(file)
            file.declarations.forEach { declaration ->
                if (declaration is KSClassDeclaration) {
                    declaration.getResolverAnnotationSpec(file.fileName)?.let { spec ->
                        annotationSpecs.add(spec)
                    }
                }
            }
        }

        // Perform validation
        validateResolvers(annotationSpecs, schema)

        // Generate GraphQL fragments file only if output option is provided
        val fragmentSpecs = annotationSpecs.flatMap { it.fragments }
        if (fragmentsOutputFile != null && fragmentSpecs.isNotEmpty() && !hasGeneratedFragments) {
            generateResolverFragmentsGraphQL(resolverFiles, fragmentSpecs, fragmentsOutputFile)
            hasGeneratedFragments = true
        }

        return emptyList()
    }

    internal fun generateResolverFragmentsGraphQL(
        resolverFiles: MutableSet<KSFile>,
        resolverSpecs: List<ResolverFragmentSpec>,
        outputFileName: String,
    ) {
        try {
            val dependencies = Dependencies(
                aggregating = false,
                sources = resolverFiles.toTypedArray()
            )

            val file = codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = "",
                fileName = outputFileName,
                extensionName = "graphql"
            )

            file.writer().use { writer ->
                resolverSpecs.forEach { writer.write(it.toString()) }
            }

            logger.info("Generated resolver fragments GraphQL schema with ${resolverSpecs.size} resolvers")
        } catch (e: Exception) {
            logger.error("Failed to generate resolver fragments GraphQL: ${e.message}")
        }
    }

    internal fun KSClassDeclaration.getResolverAnnotationSpec(sourceFileName: String): ResolverAnnotationSpec? {
        val resolverAnnotations = this.annotations.filter { it.shortName.asString() == "Resolver" }

        if (resolverAnnotations.toList().size > 1) {
            throw IllegalArgumentException("Only one @Resolver annotation is allowed. ${this.packageName}.${this.simpleName} has multiple.")
        }

        val resolverAnnotation = resolverAnnotations.firstOrNull() ?: return null

        val fragments = mutableListOf<ResolverFragmentSpec>()
        var variables = emptyList<ResolverVariableSpec>()

        resolverAnnotation.arguments.forEach { argument ->
            val argName = argument.name?.asString() ?: return@forEach
            when (argName) {
                "objectValueFragment" -> {
                    ResolverFragmentSpec.fromResolverAnnotationArgument(
                        fragmentType = ResolverFragmentType.OBJECT,
                        argumentValue = argument.value,
                        ksclass = this,
                        sourceFileName = sourceFileName
                    )?.let { fragments.add(it) }
                }
                "queryValueFragment" -> {
                    ResolverFragmentSpec.fromResolverAnnotationArgument(
                        fragmentType = ResolverFragmentType.QUERY,
                        argumentValue = argument.value,
                        ksclass = this,
                        sourceFileName = sourceFileName
                    )?.let { fragments.add(it) }
                }
                "variables" -> {
                    val varAnnotations = argument.value as? List<*> ?: emptyList<Any>()
                    variables = varAnnotations.filterIsInstance<KSAnnotation>().map { varAnn ->
                        ResolverVariableSpec(
                            name = varAnn.getArgString("name")!!,
                            fromObjectField = varAnn.getArgStringOrNull("fromObjectField"),
                            fromQueryField = varAnn.getArgStringOrNull("fromQueryField"),
                            fromArgument = varAnn.getArgStringOrNull("fromArgument"),
                        )
                    }
                }
            }
        }

        // Extract variable names from nested @Variables class
        val variablesAnnotations = this.declarations
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { it.annotations.filter { a -> a.shortName.asString() == "Variables" } }
            .toList()

        if (variablesAnnotations.size > 1) {
            throw IllegalArgumentException(
                "Resolver class ${this.qualifiedName?.asString()} must have at most one @Variables annotation across its nested classes"
            )
        }

        val variablesProviderVarNames = variablesAnnotations.firstOrNull()?.let { annotation ->
            @Suppress("UNCHECKED_CAST")
            val typesList = annotation.arguments.firstOrNull { it.name?.asString() == "types" }?.value as? List<String> ?: emptyList()
            if (typesList.isNotEmpty()) parseVariableNamesFromTypes(typesList) else emptySet()
        } ?: emptySet()

        val typeName = this.simpleName.asString()
        val metadata = Metadata(
            packageName = this.packageName.asString(),
            className = typeName,
            sourceFileName = sourceFileName,
            typeName = fragments.firstOrNull()?.metadata?.typeName ?: typeName,
            fragmentType = fragments.firstOrNull()?.metadata?.fragmentType ?: ResolverFragmentType.OBJECT,
        )

        return ResolverAnnotationSpec(
            fragments = fragments,
            variables = variables,
            metadata = metadata,
            variablesProviderVarNames = variablesProviderVarNames,
        )
    }

    /**
     * Validates resolver required selection sets and variables.
     * Uses separate validator classes implementing IValidator interface.
     * Collects errors from all validators and reports them together.
     */
    private fun validateResolvers(
        annotationSpecs: List<ResolverAnnotationSpec>,
        schema: GraphQLSchema,
    ) {
        val errors = mutableListOf<String>()

        val fragmentSpecs = annotationSpecs.flatMap { it.fragments }
        ValidateResolverFragments(fragmentSpecs, schema, fragmentValidator)
            .validateAndReportErrors()?.let { errors.add(it) }

        ValidateResolverVariables(annotationSpecs)
            .validateAndReportErrors()?.let { errors.add(it) }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(errors.joinToString("\n"))
        }
    }
}

/** KSP processor option key. When present, its value is used as the output file name for the extracted resolver fragments. */
const val FRAGMENTS_OUTPUT_OPTION = "fragmentsOutputFile"

/** Duplicated from viaduct.api.Variable.UNSET_STRING_VALUE — cannot import due to no dep on tenant-api */
internal const val VARIABLE_UNSET_STRING_VALUE = "XY!#* N0T S3T!"

private fun KSAnnotation.getArgString(argName: String): String? = arguments.find { it.name?.asString() == argName }?.value as? String

private fun KSAnnotation.getArgStringOrNull(argName: String): String? = getArgString(argName)?.takeIf { it != VARIABLE_UNSET_STRING_VALUE }

internal fun parseVariableNamesFromTypes(types: List<String>): Set<String> =
    types
        .filter { it.isNotBlank() }
        .map { entry ->
            val parts = entry.trim().split(":")
            require(parts.size == 2) {
                "Invalid @Variables entry '${entry.trim()}' — expected format 'name: Type'"
            }
            val name = parts[0].trim()
            require(name.isNotEmpty()) {
                "Invalid @Variables entry '${entry.trim()}' — variable name is empty"
            }
            name
        }
        .toSet()
