package viaduct.ksp.validation

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.devtools.ksp.symbol.KSClassDeclaration
import viaduct.engine.api.parse.DocumentParser
import viaduct.ksp.convertToFullFragment
import viaduct.ksp.extractTypeNameFromBaseClass

/**
 * Represents a resolver's fragment definition extracted from a single @Resolver annotation argument.
 *
 * This class serves as a bridge between compile-time processing and run-time use:
 * 1. At compile-time: [ResolverSelectionSetProcessor] extracts fragments from @Resolver annotations
 *    and serializes them to `resolver-extracted-fragments.graphql` using the `toString` method here.
 * 2. At run-time: Consumers can use this class reads to read the serialized file via the `fromString` method.
 *
 * Each spec corresponds to one resolver annotation argument (objectValueFragment or queryValueFragment).
 * A resolver class with both objectValueFragment and queryValueFragment will produce two separate specs.
 *
 * @property fragment The complete fragment definition(s) from the annotation argument.
 *   Contains one top-level fragment with zero or more helper fragments that the
 *   top-level fragment may reference via spreads (e.g., ...UserProfilePhoto)
 * @property metadata The [Metadata] for this resolver fragment
 */
data class ResolverFragmentSpec(
    val fragment: String,
    val metadata: Metadata,
) {
    /**
     * Serializes this spec in the format:
     * ```
     * # <METADATA>{"packageName":"my.package","className":"UserNameResolver"...}</METADATA>
     * fragment Main on User { ... }
     * ```
     */
    override fun toString(): String {
        val json = objectMapper.writeValueAsString(metadata)
        return buildString {
            append("# $METADATA_START$json$METADATA_END\n")
            append("$fragment\n")
        }
    }

    fun fragmentDocument() = DocumentParser.parse(fragment)

    companion object {
        private const val METADATA_START = "<METADATA>"
        private const val METADATA_END = "</METADATA>"
        private val METADATA_PATTERN = Regex("""$METADATA_START(.+)$METADATA_END""")

        // A regex used to split a file with multiple specs
        val BLOCK_SPLIT_PATTERN = Regex("(?=# ${Regex.escape(METADATA_START)})")

        private val objectMapper = jacksonObjectMapper()

        /**
         * Parses a resolver block from the serialized format.
         *
         * @param fragmentBlock The complete fragment definition from a @Resolver annotation argument
         *   (queryValueFragment or objectValueFragment). Contains one top-level fragment with
         *   @generateMock directive, plus zero or more helper fragments without @generateMock that
         *   the top-level fragment may reference via spreads (e.g., ...UserProfilePhoto).
         * @return ResolverFragmentSpec
         * @throws IllegalArgumentException if the block cannot be parsed
         */
        fun fromString(fragmentBlock: String): ResolverFragmentSpec {
            val match = requireNotNull(METADATA_PATTERN.find(fragmentBlock)) {
                "No metadata block found in: ${fragmentBlock.take(100)}..."
            }
            val json = match.groupValues[1]
            val metadata = objectMapper.readValue<Metadata>(json)
            val fragment = fragmentBlock
                .substringAfter(METADATA_END)
                .trim()
            require(fragment.isNotBlank()) { "Fragment content cannot be blank" }
            return ResolverFragmentSpec(fragment, metadata)
        }

        internal fun fromResolverAnnotationArgument(
            fragmentType: ResolverFragmentType,
            argumentValue: Any?,
            ksclass: KSClassDeclaration,
            sourceFileName: String,
        ): ResolverFragmentSpec? {
            // Early return if no fragment value provided
            val fragmentString = argumentValue?.toString()?.takeIf { it.isNotBlank() }
                ?: return null

            val typeName = ksclass.extractTypeNameFromBaseClass()
            val fragmentTypeName = when (fragmentType) {
                ResolverFragmentType.OBJECT -> typeName
                ResolverFragmentType.QUERY -> "Query"
            }
            val fullFragment = convertToFullFragment(fragmentString, fragmentTypeName)

            return ResolverFragmentSpec(
                fragment = fullFragment,
                metadata = Metadata(
                    packageName = ksclass.packageName.asString(),
                    className = ksclass.simpleName.asString(),
                    sourceFileName = sourceFileName,
                    typeName = typeName,
                    fragmentType = fragmentType,
                ),
            )
        }
    }
}

/**
 * @property packageName The package containing the resolver class
 * @property className The simple name of the resolver class
 * @property sourceFileName The source file name where the resolver is defined
 * @property typeName The parent type of the GraphQL schema field this resolver resolves
 */
data class Metadata(
    val packageName: String,
    val className: String,
    val sourceFileName: String,
    val typeName: String,
    val fragmentType: ResolverFragmentType,
) {
    init {
        require(className.isNotBlank()) { "Class name cannot be blank" }
        require(sourceFileName.isNotBlank()) { "Source file name cannot be blank" }
        require(typeName.isNotBlank()) { "Type name cannot be blank" }
    }

    @get:JsonIgnore
    val fullClassName: String get() = if (packageName.isEmpty()) className else "$packageName.$className"
}

enum class ResolverFragmentType {
    OBJECT,
    QUERY
}
