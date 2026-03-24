package viaduct.graphql.schema.binary

import java.io.InputStream
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/**
 * Reads a binary-encoded schema from the input stream.
 *
 * @param input The input stream containing the binary schema data
 * @return A ViaductSchema representation of the binary schema
 */
internal fun readBSchema(input: InputStream): SchemaWithData {
    return BInputStream(input, MAX_STRING_LEN).use { data ->
        val schema = SchemaWithData()

        // Decode header first to get counts for pre-sizing
        val header = HeaderSection.decode(data)

        // Decode sections
        val identifiers = IdentifiersDecoder.fromFile(data, header, schema)
        val sourceLocations = SourceLocationsDecoder(data, header)
        val constants = ConstantsDecoder.fromFile(data, header, identifiers)
        val types = TypeExpressionsDecoder(data, header, identifiers)

        // Decode definitions
        val definitions = DefinitionsDecoder(data, identifiers, types, sourceLocations, constants)

        schema.populate(
            identifiers.directives,
            identifiers.types,
            definitions.queryTypeDef,
            definitions.mutationTypeDef,
            definitions.subscriptionTypeDef
        )
        schema
    }
}

//
// Section decoders

/**
 * Decodes the identifiers section and provides lookup access to identifiers and definitions.
 *
 * @param identifiers All identifiers in lexicographic order
 * @param indexedDefs Indexed array of all definitions (directives and type definitions) in file order
 * @param directives Map of directive name to directive definition
 * @param types Map of type name to type definition
 */
internal class IdentifiersDecoder(
    private val identifiers: IdentifierTable,
    val indexedDefs: Array<SchemaWithData.TopLevelDef>,
    val directives: Map<String, SchemaWithData.Directive>,
    val types: Map<String, SchemaWithData.TypeDef>
) {
    companion object {
        /**
         * Reads the identifiers section and definition stubs from the binary stream.
         *
         * @param data The input stream positioned at the start of the identifiers section
         * @param header The decoded header containing counts
         * @param schema The schema stub to pass to created definitions
         * @return A new IdentifiersDecoder instance
         */
        fun fromFile(
            data: BInputStream,
            header: HeaderSection,
            schema: SchemaWithData
        ): IdentifiersDecoder {
            // Read Identifiers section
            data.validateMagicNumber(MAGIC_IDENTIFIERS, "identifiers")
            val identifierTable = SortedArrayIdentifierTable.read(data, header.identifierCount)
            data.skipPadding()

            // Read Definition Stubs section
            data.validateMagicNumber(MAGIC_DEFINITION_STUBS, "definition stubs")

            val mDirectives = LinkedHashMap<String, SchemaWithData.Directive>(((header.directiveCount / 0.75f) + 1).toInt(), 0.75f)
            val mTypes = LinkedHashMap<String, SchemaWithData.TypeDef>(((header.typeDefCount / 0.75f) + 1).toInt(), 0.75f)

            val indexedDefs = Array(header.definitionStubCount) { _ ->
                val stubWord = StubRefPlus(data.readInt())
                val name = identifierTable.keyAt(stubWord.getIdentifierIndex())
                when (val kindCode = stubWord.getKindCode()) {
                    K_DIRECTIVE -> SchemaWithData.Directive(schema, name).also { mDirectives[name] = it }
                    K_ENUM -> SchemaWithData.Enum(schema, name).also { mTypes[name] = it }
                    K_INPUT -> SchemaWithData.Input(schema, name).also { mTypes[name] = it }
                    K_INTERFACE -> SchemaWithData.Interface(schema, name).also { mTypes[name] = it }
                    K_OBJECT -> SchemaWithData.Object(schema, name).also { mTypes[name] = it }
                    K_SCALAR -> SchemaWithData.Scalar(schema, name).also { mTypes[name] = it }
                    K_UNION -> SchemaWithData.Union(schema, name).also { mTypes[name] = it }
                    else -> throw InvalidFileFormatException("Invalid kind code in definition stub ($kindCode)")
                }
            }

            return IdentifiersDecoder(identifierTable, indexedDefs, mDirectives, mTypes)
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
) {
    /** Get type expression by index (will apply IDX_MASK) */
    fun get(index: Int): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> = typeExprs[index and IDX_MASK]

    val typeExprs: Array<ViaductSchema.TypeExpr<SchemaWithData.TypeDef>>

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
            val typeDef: SchemaWithData.TypeDef = identifiers.types[typeDefName]
                ?: throw NoSuchElementException("Type def not found ($typeDefName).")
            ViaductSchema.TypeExpr(
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
