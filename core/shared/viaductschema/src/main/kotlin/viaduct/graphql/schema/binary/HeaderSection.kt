package viaduct.graphql.schema.binary

/** Reads and validates the binary schema file header. */
internal class HeaderSection(
    val maxStringLen: Int,
    val identifierCount: Int,
    val identifierBytes: Int,
    val definitionStubCount: Int,
    val sourceLocationCount: Int,
    val sourceLocationBytes: Int,
    val typeExprSectionBytes: Int,
    val typeExprCount: Int,
    val directiveCount: Int,
    val typeDefCount: Int,
    val simpleConstantCount: Int,
    val simpleConstantBytes: Int,
    val compoundConstantCount: Int,
    val compoundConstantBytes: Int,
    val magicNumber: Int = MAGIC_NUMBER,
    val version: Int = FILE_VERSION,
) {
    fun encode(out: BOutputStream) {
        out.writeInt(magicNumber)
        out.writeInt(version)
        out.writeInt(maxStringLen)
        out.writeInt(identifierCount)
        out.writeInt(identifierBytes)
        out.writeInt(definitionStubCount)
        out.writeInt(sourceLocationCount)
        out.writeInt(sourceLocationBytes)
        out.writeInt(typeExprSectionBytes)
        out.writeInt(typeExprCount)
        out.writeInt(directiveCount)
        out.writeInt(typeDefCount)
        out.writeInt(simpleConstantCount)
        out.writeInt(simpleConstantBytes)
        out.writeInt(compoundConstantCount)
        out.writeInt(compoundConstantBytes)
    }

    companion object {
        fun from(
            schemaInfo: SchemaInfo,
            constantsEncoder: ConstantsEncoder
        ): HeaderSection {
            val directiveCount = schemaInfo.inputSchema.directives.values.size
            val typeDefCount = schemaInfo.inputSchema.types.values.size
            return HeaderSection(
                maxStringLen = schemaInfo.maxIdentifierOrSourceNameLen,
                identifierCount = schemaInfo.identifiers.size,
                identifierBytes = schemaInfo.identifierBytes,
                definitionStubCount = directiveCount + typeDefCount,
                sourceLocationCount = schemaInfo.sourceNameCount,
                sourceLocationBytes = schemaInfo.sourceNameBytes,
                typeExprSectionBytes = WORD_SIZE + schemaInfo.typeExprs.keys.sumOf { TexprWordOne.typeExprByteSize(it) },
                typeExprCount = schemaInfo.typeExprs.size,
                directiveCount = directiveCount,
                typeDefCount = typeDefCount,
                simpleConstantCount = constantsEncoder.simpleConstantsCount,
                simpleConstantBytes = constantsEncoder.simpleConstantsBytes,
                compoundConstantCount = constantsEncoder.compoundConstantsCount,
                compoundConstantBytes = constantsEncoder.compoundConstantsBytes,
            )
        }

        fun decode(data: BInputStream) =
            HeaderSection(
                magicNumber = data.readInt(),
                version = data.readInt(),
                maxStringLen = data.readInt(),
                identifierCount = data.readInt(),
                identifierBytes = data.readInt(),
                definitionStubCount = data.readInt(),
                sourceLocationCount = data.readInt(),
                sourceLocationBytes = data.readInt(),
                typeExprSectionBytes = data.readInt(),
                typeExprCount = data.readInt(),
                directiveCount = data.readInt(),
                typeDefCount = data.readInt(),
                simpleConstantCount = data.readInt(),
                simpleConstantBytes = data.readInt(),
                compoundConstantCount = data.readInt(),
                compoundConstantBytes = data.readInt(),
            ).apply {
                if (magicNumber != MAGIC_NUMBER) {
                    throw InvalidFileFormatException(
                        "Invalid magic number: expected 0x${MAGIC_NUMBER.toString(16)}, got 0x${magicNumber.toString(16)}"
                    )
                }
                if (version != FILE_VERSION) {
                    throw InvalidFileFormatException(
                        "Unsupported version number: expected 0x${FILE_VERSION.toString(16)}, got 0x${version.toString(16)}"
                    )
                }
                if (maxStringLen > MAX_STRING_LEN) {
                    throw InvalidFileFormatException(
                        "Max identifier length ($maxStringLen) exceeds limit ($MAX_STRING_LEN)."
                    )
                }
                if (definitionStubCount != directiveCount + typeDefCount) {
                    throw InvalidFileFormatException(
                        "Definition stub count mismatch: definitionStubCount=$definitionStubCount but directiveCount=$directiveCount + typeDefCount=$typeDefCount"
                    )
                }
            }
    }
}
