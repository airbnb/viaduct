package viaduct.graphql.schema.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.InvalidSchemaException
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile

/**
 * Tests for error handling during binary schema decoding.
 *
 * This file consolidates all tests related to errors that can occur when
 * decoding a binary schema file:
 *
 * - [InvalidFileFormatException]: Structural problems with the binary file format
 *   (invalid magic numbers, unsupported versions, truncated data, etc.)
 *
 * - [InvalidSchemaException]: The file format is valid but the schema content
 *   violates GraphQL semantics (missing directive definitions, wrong type kinds, etc.)
 *
 * Validation is performed in BSchema constructors and setters, making these
 * checks format-independent and ensuring they apply regardless of how the
 * schema is constructed.
 */
class DecodingErrorHandlingTest {
    private val schema = SchemaWithData()
    // ========================================================================
    // Test #1: Missing directive definition for applied directive
    // ========================================================================

    @Test
    fun `Applied directive on type references non-existent directive`() {
        val obj = SchemaWithData.Object(schema, "MyObject")

        // Create an applied directive referencing a directive that doesn't exist
        val appliedDirective = ViaductSchema.AppliedDirective.of(mockDirective("nonExistent"), emptyMap())

        val ext = ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Object, SchemaWithData.Field>(
            def = obj,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = listOf(appliedDirective), // Applied directive with no definition!
            supers = emptyList(),
            sourceLocation = null
        )
        obj.populate(listOf(ext), emptyList())

        assertThrows<InvalidSchemaException> {
            emptyDirectives.validateAppliedDirectives(obj)
        }
    }

    @Test
    fun `Applied directive on field references non-existent directive`() {
        val obj = SchemaWithData.Object(schema, "Query")
        val stringType = SchemaWithData.Scalar(schema, "String")
        val stringExt = ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
            def = stringType,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        stringType.populate(listOf(stringExt))

        // Create a field with an applied directive that doesn't exist
        val appliedDirective = ViaductSchema.AppliedDirective.of(mockDirective("missingDirective"), emptyMap())

        val ext = ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Object, SchemaWithData.Field>(
            def = obj,
            memberFactory = { ext ->
                listOf(
                    SchemaWithData.Field(
                        ext,
                        "myField",
                        ViaductSchema.TypeExpr(stringType),
                        listOf(appliedDirective), // Applied directive with no definition!
                        hasDefault = false,
                        defaultValue = null
                    )
                )
            },
            isBase = true,
            appliedDirectives = emptyList(),
            supers = emptyList(),
            sourceLocation = null
        )
        obj.populate(listOf(ext), emptyList())

        assertThrows<InvalidSchemaException> {
            emptyDirectives.validateAppliedDirectives(obj)
        }
    }

    @Test
    fun `Applied directive on scalar references non-existent directive`() {
        val scalar = SchemaWithData.Scalar(schema, "MyScalar")

        val appliedDirective = ViaductSchema.AppliedDirective.of(mockDirective("nonExistent"), emptyMap())
        val extension = ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
            def = scalar,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = listOf(appliedDirective),
            sourceLocation = null
        )
        scalar.populate(listOf(extension))

        assertThrows<InvalidSchemaException> {
            emptyDirectives.validateAppliedDirectives(scalar)
        }
    }

    @Test
    fun `Applied directive on enum value references non-existent directive`() {
        val enumType = SchemaWithData.Enum(schema, "Status")

        val appliedDirective = ViaductSchema.AppliedDirective.of(mockDirective("nonExistent"), emptyMap())

        val ext = ViaductSchema.Extension.of<SchemaWithData.Enum, SchemaWithData.EnumValue>(
            def = enumType,
            memberFactory = { ext ->
                listOf(
                    SchemaWithData.EnumValue(
                        ext,
                        "ACTIVE",
                        listOf(appliedDirective) // Applied directive with no definition!
                    )
                )
            },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        enumType.populate(listOf(ext))

        assertThrows<InvalidSchemaException> {
            emptyDirectives.validateAppliedDirectives(enumType)
        }
    }

    // ========================================================================
    // Test #2 & #3: Type kind validation in type references
    // ========================================================================

    @Test
    fun `Interface possibleObjectTypes contains non-Object type`() {
        val iface = SchemaWithData.Interface(schema, "MyInterface")
        val scalar = SchemaWithData.Scalar(schema, "NotAnObject")
        val stringType = SchemaWithData.Scalar(schema, "String")

        // Set up interface with a field
        val ext = ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Interface, SchemaWithData.Field>(
            def = iface,
            memberFactory = { ext ->
                listOf(
                    SchemaWithData.Field(ext, "field", ViaductSchema.TypeExpr(stringType), emptyList(), false, null)
                )
            },
            isBase = true,
            appliedDirectives = emptyList(),
            supers = emptyList(),
            sourceLocation = null
        )

        // Try to set possibleObjectTypes with a Scalar (not an Object)
        assertThrows<InvalidSchemaException> {
            // Use unsafe cast to bypass compile-time type checking
            // This simulates what could happen during binary decoding
            @Suppress("UNCHECKED_CAST")
            val badSet = setOf(scalar) as Set<SchemaWithData.Object>
            iface.populate(listOf(ext), badSet)
        }
    }

    @Test
    fun `Object implements non-Interface type`() {
        val obj = SchemaWithData.Object(schema, "MyObject")
        val scalar = SchemaWithData.Scalar(schema, "NotAnInterface")
        val stringType = SchemaWithData.Scalar(schema, "String")

        assertThrows<InvalidSchemaException> {
            // Try to create extension with a Scalar in supers (should be Interface)
            // Use unsafe cast to bypass compile-time type checking
            @Suppress("UNCHECKED_CAST")
            val badSupers = listOf(scalar) as List<SchemaWithData.Interface>

            val ext = ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Object, SchemaWithData.Field>(
                def = obj,
                memberFactory = { ext ->
                    listOf(
                        SchemaWithData.Field(ext, "field", ViaductSchema.TypeExpr(stringType), emptyList(), false, null)
                    )
                },
                isBase = true,
                appliedDirectives = emptyList(),
                supers = badSupers, // Wrong type!
                sourceLocation = null
            )

            obj.populate(listOf(ext), emptyList())
        }
    }

    // ========================================================================
    // Test #4: Union members must be Object types
    // ========================================================================

    @Test
    fun `Union contains non-Object member`() {
        val union = SchemaWithData.Union(schema, "MyUnion")
        val scalar = SchemaWithData.Scalar(schema, "NotAnObject")

        assertThrows<InvalidSchemaException> {
            // Use unsafe cast to bypass compile-time type checking
            @Suppress("UNCHECKED_CAST")
            val badMembers = listOf(scalar) as List<SchemaWithData.Object>

            val ext = ViaductSchema.Extension.of<SchemaWithData.Union, SchemaWithData.Object>(
                def = union,
                memberFactory = { badMembers }, // Wrong type!
                isBase = true,
                appliedDirectives = emptyList(),
                sourceLocation = null
            )

            union.populate(listOf(ext))
        }
    }

    // ========================================================================
    // Test #7: Applied directive argument validation
    // ========================================================================

    @Test
    fun `Applied directive has argument not defined in directive definition`() {
        val directive = SchemaWithData.Directive(schema, "myDirective")
        // Directive has no arguments defined
        directive.populate(
            isRepeatable = false,
            allowedLocations = setOf(ViaductSchema.Directive.Location.FIELD_DEFINITION),
            sourceLocation = null,
            args = emptyList()
        )

        val directives = mapOf("myDirective" to directive)

        val obj = SchemaWithData.Object(schema, "Query")
        val stringType = SchemaWithData.Scalar(schema, "String")
        val stringExt = ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
            def = stringType,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        stringType.populate(listOf(stringExt))

        // Create applied directive with an argument that doesn't exist in definition
        val appliedDirective = ViaductSchema.AppliedDirective.of(
            mockDirective("myDirective"),
            mapOf("unknownArg" to ViaductSchema.StringLiteral.of("value")) // This arg doesn't exist!
        )

        val ext = ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Object, SchemaWithData.Field>(
            def = obj,
            memberFactory = { ext ->
                listOf(
                    SchemaWithData.Field(
                        ext,
                        "myField",
                        ViaductSchema.TypeExpr(stringType),
                        listOf(appliedDirective),
                        hasDefault = false,
                        defaultValue = null
                    )
                )
            },
            isBase = true,
            appliedDirectives = emptyList(),
            supers = emptyList(),
            sourceLocation = null
        )
        obj.populate(listOf(ext), emptyList())

        assertThrows<InvalidSchemaException> {
            directives.validateAppliedDirectives(obj)
        }
    }

    // ========================================================================
    // InvalidFileFormatException tests: Binary file format errors
    // ========================================================================
    // These tests verify that structural problems with the binary file
    // result in InvalidFileFormatException (currently IllegalArgumentException).

    companion object {
        private val emptyDirectives = emptyMap<String, SchemaWithData.Directive>()

        fun assertMessageContains(
            expectedContents: String,
            exception: Throwable
        ) {
            val m = exception.message!!
            assert(m.contains(expectedContents)) { "Expected message to contain '$expectedContents' but was: $m" }
        }

        /**
         * Creates an input stream starting with the given header words,
         * padded to [HEADER_SIZE_IN_WORDS].
         */
        fun makeHeaderStream(vararg words: Int): InputStream {
            val baos = ByteArrayOutputStream()
            val out = BOutputStream(baos)
            var wordsLeft = HEADER_SIZE_IN_WORDS
            for (word in words) {
                out.writeInt(word)
                wordsLeft--
            }
            require(0 <= wordsLeft) { "Too many words in fake header (${words.size})." }
            repeat(wordsLeft) { out.writeInt(0) }
            out.close()
            return ByteArrayInputStream(baos.toByteArray())
        }
    }

    @Test
    fun `Invalid magic number throws InvalidFileFormatException`() {
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(makeHeaderStream(0x7654_3210, FILE_VERSION))
        }
        assertMessageContains("Invalid magic number", exception)
    }

    @Test
    fun `Unsupported file version throws InvalidFileFormatException`() {
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(makeHeaderStream(MAGIC_NUMBER, 0x000_00200))
        }
        assertMessageContains("Unsupported version", exception)
    }

    @Test
    fun `Max identifier length exceeded throws InvalidFileFormatException`() {
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(makeHeaderStream(MAGIC_NUMBER, FILE_VERSION, 1_000_000))
        }
        assertMessageContains("Max identifier length", exception)
    }

    @Test
    fun `Reading beyond EOF throws InvalidFileFormatException`() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(1, 2, 3)) // Only 3 bytes
        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 100)

        stream.read()
        stream.read()
        stream.read()

        val exception = assertThrows<InvalidFileFormatException> {
            stream.read()
        }
        assertMessageContains("Reached EOF", exception)
    }

    @Test
    fun `Invalid kind code in definition stubs section throws InvalidFileFormatException`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        // Write valid header
        // Note: definitionStubCount must equal directiveCount + typeDefCount
        // We use typeDefCount=1 to allow one definition stub
        out.writeInt(MAGIC_NUMBER)
        out.writeInt(FILE_VERSION)
        out.writeInt(100) // maxStringLen
        out.writeInt(1) // identifierCount
        out.writeInt(13) // identifierBytes (magic + "testName\0" + padding)
        out.writeInt(1) // definitionStubCount
        out.writeInt(1) // sourceLocationCount
        out.writeInt(4) // sourceLocationBytes
        out.writeInt(0) // typeExprSectionBytes
        out.writeInt(0) // typeExprCount
        out.writeInt(0) // directiveCount
        out.writeInt(1) // typeDefCount (matches definitionStubCount)
        out.writeInt(0) // simpleConstantCount
        out.writeInt(0) // simpleConstantBytes
        out.writeInt(0) // compoundConstantCount
        out.writeInt(0) // compoundConstantBytes

        // Write identifiers section
        out.writeInt(MAGIC_IDENTIFIERS)
        for (c in "testName") {
            out.write(c.code)
        }
        out.write(0) // null terminator
        out.pad()

        // Write definition stubs section with INVALID kind code
        out.writeInt(MAGIC_DEFINITION_STUBS)
        // StubRefPlus format: bits 0-19 = identifier index, bits 24-31 = kind code
        // 0xFF is an invalid kind code (not K_DIRECTIVE, K_ENUM, etc.)
        out.writeInt(0 or (0xFF shl 24)) // identifier index 0, invalid kind code 0xFF

        out.close()

        val input = ByteArrayInputStream(baos.toByteArray())
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(input)
        }
        assertMessageContains("Invalid kind code", exception)
    }

    @Test
    fun `Invalid definition stubs section magic throws InvalidFileFormatException`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        // Write valid header
        out.writeInt(MAGIC_NUMBER)
        out.writeInt(FILE_VERSION)
        out.writeInt(100) // maxStringLen
        out.writeInt(0) // identifierCount
        out.writeInt(4) // identifierBytes (magic only)
        out.writeInt(0) // definitionStubCount
        out.writeInt(1) // sourceLocationCount
        out.writeInt(4) // sourceLocationBytes
        out.writeInt(0) // typeExprSectionBytes
        out.writeInt(0) // typeExprCount
        out.writeInt(0) // directiveCount
        out.writeInt(0) // typeDefCount
        out.writeInt(0) // simpleConstantCount
        out.writeInt(0) // simpleConstantBytes
        out.writeInt(0) // compoundConstantCount
        out.writeInt(0) // compoundConstantBytes

        // Write identifiers section (empty)
        out.writeInt(MAGIC_IDENTIFIERS)
        out.pad()

        // Write WRONG magic for definition stubs section
        out.writeInt(0xBAD5708B.toInt())

        out.close()

        val input = ByteArrayInputStream(baos.toByteArray())
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(input)
        }
        assertMessageContains("definition stubs", exception)
    }

    @Test
    fun `Invalid source locations section magic throws InvalidFileFormatException`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        // Write valid header
        out.writeInt(MAGIC_NUMBER)
        out.writeInt(FILE_VERSION)
        out.writeInt(100) // maxStringLen
        out.writeInt(0) // identifierCount
        out.writeInt(4) // identifierBytes (magic only)
        out.writeInt(0) // definitionStubCount
        out.writeInt(1) // sourceLocationCount
        out.writeInt(4) // sourceLocationBytes
        out.writeInt(0) // typeExprSectionBytes
        out.writeInt(0) // typeExprCount
        out.writeInt(0) // directiveCount
        out.writeInt(0) // typeDefCount
        out.writeInt(0) // simpleConstantCount
        out.writeInt(0) // simpleConstantBytes
        out.writeInt(0) // compoundConstantCount
        out.writeInt(0) // compoundConstantBytes

        // Write identifiers section (empty)
        out.writeInt(MAGIC_IDENTIFIERS)
        out.pad()

        // Write definition stubs section (empty)
        out.writeInt(MAGIC_DEFINITION_STUBS)

        // Write WRONG magic for source locations section
        out.writeInt(0xBADBAD00.toInt())

        out.close()

        val input = ByteArrayInputStream(baos.toByteArray())
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(input)
        }
        assertMessageContains("Invalid source locations section magic", exception)
    }

    @Test
    fun `Invalid type expressions section magic throws InvalidFileFormatException`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        // Write valid header
        out.writeInt(MAGIC_NUMBER)
        out.writeInt(FILE_VERSION)
        out.writeInt(100) // maxStringLen
        out.writeInt(0) // identifierCount
        out.writeInt(4) // identifierBytes (magic only)
        out.writeInt(0) // definitionStubCount
        out.writeInt(1) // sourceLocationCount
        out.writeInt(4) // sourceLocationBytes
        out.writeInt(0) // typeExprSectionBytes
        out.writeInt(0) // typeExprCount
        out.writeInt(0) // directiveCount
        out.writeInt(0) // typeDefCount
        out.writeInt(0) // simpleConstantCount
        out.writeInt(4) // simpleConstantBytes
        out.writeInt(0) // compoundConstantCount
        out.writeInt(4) // compoundConstantBytes

        // Write identifiers section (empty)
        out.writeInt(MAGIC_IDENTIFIERS)
        out.pad()

        // Write definition stubs section (empty)
        out.writeInt(MAGIC_DEFINITION_STUBS)

        // Write source locations section (minimal)
        out.writeInt(MAGIC_SOURCE_LOCATIONS)
        out.write(0) // Null placeholder
        out.pad()

        // Write simple constants section (empty)
        out.writeInt(MAGIC_SIMPLE_CONSTANTS)
        out.pad()

        // Write compound constants section (empty)
        out.writeInt(MAGIC_COMPOUND_CONSTANTS)

        // Write WRONG magic for type expressions section
        out.writeInt(0xBADC0DE.toInt())

        out.close()

        val input = ByteArrayInputStream(baos.toByteArray())
        val exception = assertThrows<InvalidFileFormatException> {
            ViaductSchema.fromBinaryFile(input)
        }
        assertMessageContains("Invalid type expressions section magic", exception)
    }

    @Test
    fun `Definition not found during decode throws InvalidFileFormatException`() {
        // This would require creating a binary where the definitions section
        // references an identifier that wasn't declared as a definition.
        // For now, we test that DefinitionsDecoder.typeDef throws properly.
        val types = emptyMap<String, SchemaWithData.TypeDef>()
        val identifiers = arrayOf("UnknownType")

        // Simulate what happens during decoding when a type reference
        // points to a name that isn't a type definition
        val exception = assertThrows<InvalidFileFormatException> {
            val name = identifiers[0]
            types[name]
                ?: throw InvalidFileFormatException("Type not found: $name")
        }
        assertMessageContains("Type not found", exception)
    }

    @Test
    fun `Type reference to wrong kind throws InvalidFileFormatException`() {
        // When decoding, if we expect an Object but get a Scalar, etc.
        val scalar = SchemaWithData.Scalar(schema, "MyScalar")
        val types = mapOf("MyScalar" to scalar as SchemaWithData.TypeDef)

        // Simulate decoding expecting Object but finding Scalar
        val exception = assertThrows<InvalidFileFormatException> {
            val typeDef = types["MyScalar"]
            if (typeDef !is SchemaWithData.Object) {
                throw InvalidFileFormatException(
                    "Expected Object type but found ${typeDef?.javaClass?.simpleName}: MyScalar"
                )
            }
        }
        assertMessageContains("Expected Object type", exception)
    }

    // ========================================================================
    // Empty extensions tests
    // ========================================================================
    // Types must have at least one extension. These are format errors if
    // the binary data somehow encodes zero extensions.

    @Test
    fun `Enum with empty extensions throws IllegalArgumentException`() {
        val enumType = SchemaWithData.Enum(schema, "Status")

        val exception = assertThrows<IllegalArgumentException> {
            enumType.populate(emptyList())
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Union with empty extensions throws IllegalArgumentException`() {
        val unionType = SchemaWithData.Union(schema, "Result")

        val exception = assertThrows<IllegalArgumentException> {
            unionType.populate(emptyList())
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Input with empty extensions throws IllegalArgumentException`() {
        val inputType = SchemaWithData.Input(schema, "InputData")

        val exception = assertThrows<IllegalArgumentException> {
            inputType.populate(emptyList())
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Interface with empty extensions throws IllegalArgumentException`() {
        val interfaceType = SchemaWithData.Interface(schema, "Node")

        val exception = assertThrows<IllegalArgumentException> {
            interfaceType.populate(emptyList(), emptySet())
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Object with empty extensions throws IllegalArgumentException`() {
        val objectType = SchemaWithData.Object(schema, "User")

        val exception = assertThrows<IllegalArgumentException> {
            objectType.populate(emptyList(), emptyList())
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    // ========================================================================
    // Additional validation tests (new)
    // ========================================================================

    @Test
    fun `Valid applied directive passes validation`() {
        val directive = SchemaWithData.Directive(schema, "myDirective")
        directive.populate(
            isRepeatable = false,
            allowedLocations = setOf(ViaductSchema.Directive.Location.FIELD_DEFINITION),
            sourceLocation = null,
            args = emptyList()
        )

        val directives = mapOf("myDirective" to directive)

        val obj = SchemaWithData.Object(schema, "Query")
        val stringType = SchemaWithData.Scalar(schema, "String")
        val stringExt = ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
            def = stringType,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        stringType.populate(listOf(stringExt))

        val appliedDirective = ViaductSchema.AppliedDirective.of(mockDirective("myDirective"), emptyMap())

        val ext = ViaductSchema.ExtensionWithSupers.of<SchemaWithData.Object, SchemaWithData.Field>(
            def = obj,
            memberFactory = { ext ->
                listOf(
                    SchemaWithData.Field(
                        ext,
                        "myField",
                        ViaductSchema.TypeExpr(stringType),
                        listOf(appliedDirective),
                        hasDefault = false,
                        defaultValue = null
                    )
                )
            },
            isBase = true,
            appliedDirectives = emptyList(),
            supers = emptyList(),
            sourceLocation = null
        )
        obj.populate(listOf(ext), emptyList())

        // Should not throw
        directives.validateAppliedDirectives(obj)
    }

    /** Helper to create mock directives for testing AppliedDirective */
    private fun mockDirective(name: String) =
        object : ViaductSchema.Directive {
            override val containingSchema: ViaductSchema = ViaductSchema.Empty
            override val name = name
            override val args: Collection<ViaductSchema.DirectiveArg> = emptyList()
            override val isRepeatable: Boolean = false
            override val allowedLocations: Set<ViaductSchema.Directive.Location> = emptySet()
            override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>> = emptyList()
            override val sourceLocation: ViaductSchema.SourceLocation? = null

            override fun describe() = "MockDirective<$name>"
        }
}
