package viaduct.graphql.schema.binary

import java.io.InputStream
import viaduct.graphql.schema.ViaductSchema

/**
 * Reads a binary-encoded schema from the input stream.
 *
 * @param input The input stream containing the binary schema data
 * @return A ViaductSchema representation of the binary schema
 */
fun readBSchema(input: InputStream): ViaductSchema {
    return BInputStream(input, MAX_STRING_LEN).use { data ->
        // Decode header first to get counts for pre-sizing
        val header = HeaderSection.decode(data)
        val result = BSchema(header.directiveCount, header.typeDefCount)

        // Decode sections
        val identifiers = IdentifiersDecoder.fromFile(data, header, result)
        val sourceLocations = SourceLocationsDecoder(data, header)
        val constants = ConstantsDecoder.fromFile(data, header, identifiers)
        val types = TypeExpressionsDecoder(data, header, identifiers, result)

        // Decode definitions
        DefinitionsDecoder(data, identifiers, types, sourceLocations, constants, result)

        result
    }
}

//
// Section decoders

/**
 * Decodes the identifiers section and provides lookup access to identifiers and definitions.
 *
 * @param identifiers All identifiers in lexicographic order
 * @param indexedDefs Indexed array of all definitions (directives and type definitions) in file order
 */
internal class IdentifiersDecoder(
    private val identifiers: IdentifierTable,
    val indexedDefs: Array<BSchema.TopLevelDef>
) {
    companion object {
        /**
         * Reads the identifiers section and definition stubs from the binary stream.
         *
         * @param data The input stream positioned at the start of the identifiers section
         * @param header The decoded header containing counts
         * @param result The BSchema to populate with stub definitions
         * @return A new IdentifiersDecoder instance
         */
        fun fromFile(
            data: BInputStream,
            header: HeaderSection,
            result: BSchema
        ): IdentifiersDecoder {
            // Read Identifiers section
            data.validateMagicNumber(MAGIC_IDENTIFIERS, "identifiers")
            val identifierTable = SortedArrayIdentifierTable.read(data, header.identifierCount)
            data.skipPadding()

            // Read Definition Stubs section
            data.validateMagicNumber(MAGIC_DEFINITION_STUBS, "definition stubs")
            val indexedDefs = Array(header.definitionStubCount) { defIdx ->
                val stubWord = StubRefPlus(data.readInt())
                val name = identifierTable.keyAt(stubWord.getIdentifierIndex())
                when (val kindCode = stubWord.getKindCode()) {
                    K_DIRECTIVE -> result.makeDirective(name)
                    K_ENUM -> result.addTypeDef<BSchema.Enum>(name)
                    K_INPUT -> result.addTypeDef<BSchema.Input>(name)
                    K_INTERFACE -> result.addTypeDef<BSchema.Interface>(name)
                    K_OBJECT -> result.addTypeDef<BSchema.Object>(name)
                    K_SCALAR -> result.addTypeDef<BSchema.Scalar>(name)
                    K_UNION -> result.addTypeDef<BSchema.Union>(name)
                    else -> throw InvalidFileFormatException("Invalid kind code in definition stub ($kindCode)")
                }
            }

            return IdentifiersDecoder(identifierTable, indexedDefs)
        }
    }

    /** Get identifier by index (with IDX_MASK applied) */
    fun get(index: Int): String = identifiers.keyAt(index and IDX_MASK)

    /**
     * Get identifier by index unless identifier is [UNDEFINED_ROOT_MAKER],
     * in which case return `null`.
     */
    fun getRootName(idx: Int): String? = if (idx == UNDEFINED_ROOT_MARKER) null else get(idx)
}

/**
 * Reads the source locations section.
 *
 * This class reads all source location strings and provides indexed access.
 * Index 0 always returns null (representing no source location).
 */
internal class SourceLocationsDecoder(data: BInputStream, header: HeaderSection) {
    val sourceLocations: Array<ViaductSchema.SourceLocation?>

    init {
        // Read Source Locations section
        val sourceLocationsMagic = data.readInt()
        if (sourceLocationsMagic != MAGIC_SOURCE_LOCATIONS) {
            throw InvalidFileFormatException(
                "Invalid source locations section magic: expected 0x${MAGIC_SOURCE_LOCATIONS.toString(
                    16
                )}, got 0x${sourceLocationsMagic.toString(16)}"
            )
        }
        data.read() // Skip null placeholder (single 0 byte)
        sourceLocations = Array<ViaductSchema.SourceLocation?>(header.sourceLocationCount) { idx ->
            if (idx == 0) null else ViaductSchema.SourceLocation(data.readUTF8String())
        }
        data.skipPadding()
    }

    /** Get source location by index. Index 0 always returns null. */
    fun get(index: Int): ViaductSchema.SourceLocation? = sourceLocations[index]
}

/**
 * Reads the type expressions section.
 *
 * This class reads all type expressions and provides indexed access to them.
 */
internal class TypeExpressionsDecoder(
    data: BInputStream,
    header: HeaderSection,
    identifiers: IdentifiersDecoder,
    result: BSchema,
) {
    /** Get type expression by index (will apply IDX_MASK) */
    fun get(index: Int): BSchema.TypeExpr = typeExprs[index and IDX_MASK]

    val typeExprs: Array<BSchema.TypeExpr>

    init {
        // Read Type Expressions section
        val typeExprsMagic = data.readInt()
        if (typeExprsMagic != MAGIC_TYPE_EXPRS) {
            throw InvalidFileFormatException(
                "Invalid type expressions section magic: expected 0x${MAGIC_TYPE_EXPRS.toString(16)}, got 0x${typeExprsMagic.toString(16)}"
            )
        }
        typeExprs = Array(header.typeExprCount) {
            val firstWord = TexprWordOne(data.readInt())
            val typeDefName = identifiers.get(firstWord.typeIndex())
            val typeDef: BSchema.TypeDef = result.findType(typeDefName)
            BSchema.TypeExpr(
                typeDef,
                firstWord.baseTypeNullable(),
                when (firstWord.needsWordTwo()) {
                    true -> TexprWordTwo(data.readInt()).listNullableVec()
                    else -> firstWord.listNullableVec()
                }
            )
        }
    }
}
