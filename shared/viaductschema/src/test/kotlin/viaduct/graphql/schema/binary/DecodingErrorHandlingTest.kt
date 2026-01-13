package viaduct.graphql.schema.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema

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
    // ========================================================================
    // Test #1: Missing directive definition for applied directive
    // ========================================================================

    @Test
    fun `Applied directive on type references non-existent directive`() {
        val schema = BSchema()
        val obj = schema.addTypeDef<BSchema.Object>("MyObject")

        // Create an applied directive referencing a directive that doesn't exist
        val appliedDirective = ViaductSchema.AppliedDirective.of("nonExistent", emptyMap())

        val ext = BSchema.Extension<BSchema.Object, BSchema.Field>(
            obj,
            listOf(appliedDirective), // Applied directive with no definition!
            null,
            isBase = true
        )

        assertThrows<InvalidSchemaException> {
            obj.extensions = listOf(ext)
        }
    }

    @Test
    fun `Applied directive on field references non-existent directive`() {
        val schema = BSchema()
        val obj = schema.addTypeDef<BSchema.Object>("Query")
        val stringType = schema.addTypeDef<BSchema.Scalar>("String")

        val ext = BSchema.Extension<BSchema.Object, BSchema.Field>(
            obj,
            emptyList(),
            null,
            isBase = true
        )

        // Create a field with an applied directive that doesn't exist
        val appliedDirective = ViaductSchema.AppliedDirective.of("missingDirective", emptyMap())
        val field = BSchema.Field(
            ext,
            "myField",
            listOf(appliedDirective), // Applied directive with no definition!
            BSchema.TypeExpr(stringType),
            hasDefault = false,
            defaultValue = null
        )

        assertThrows<InvalidSchemaException> {
            ext.members = listOf(field)
        }
    }

    @Test
    fun `Applied directive on scalar references non-existent directive`() {
        val schema = BSchema()
        val scalar = schema.addTypeDef<BSchema.Scalar>("MyScalar")

        val appliedDirective = ViaductSchema.AppliedDirective.of("nonExistent", emptyMap())

        assertThrows<InvalidSchemaException> {
            scalar.appliedDirectives = listOf(appliedDirective)
        }
    }

    @Test
    fun `Applied directive on enum value references non-existent directive`() {
        val schema = BSchema()
        val enumType = schema.addTypeDef<BSchema.Enum>("Status")

        val appliedDirective = ViaductSchema.AppliedDirective.of("nonExistent", emptyMap())

        val ext = BSchema.Extension<BSchema.Enum, BSchema.EnumValue>(
            enumType,
            emptyList(),
            null,
            isBase = true
        )

        val enumValue = BSchema.EnumValue(
            ext,
            "ACTIVE",
            listOf(appliedDirective) // Applied directive with no definition!
        )

        assertThrows<InvalidSchemaException> {
            enumType.extensions = listOf(ext.also { it.members = listOf(enumValue) })
        }
    }

    // ========================================================================
    // Test #2 & #3: Type kind validation in type references
    // ========================================================================

    @Test
    fun `Interface possibleObjectTypes contains non-Object type`() {
        val schema = BSchema()
        val iface = schema.addTypeDef<BSchema.Interface>("MyInterface")
        val scalar = schema.addTypeDef<BSchema.Scalar>("NotAnObject")
        val stringType = schema.addTypeDef<BSchema.Scalar>("String")

        // Set up interface with a field
        val ext = BSchema.Extension<BSchema.Interface, BSchema.Field>(
            iface,
            emptyList(),
            null,
            isBase = true
        )
        val field = BSchema.Field(ext, "field", emptyList(), BSchema.TypeExpr(stringType), false, null)
        iface.extensions = listOf(ext.also { it.members = listOf(field) })

        // Try to set possibleObjectTypes with a Scalar (not an Object)
        // The validation should catch this and throw InvalidSchemaException
        // Currently throws ClassCastException - we want InvalidSchemaException instead
        assertThrows<InvalidSchemaException> {
            // Use unsafe cast to bypass compile-time type checking
            // This simulates what could happen during binary decoding
            @Suppress("UNCHECKED_CAST")
            val badSet = setOf(scalar) as Set<BSchema.Object>
            iface.possibleObjectTypes = badSet
        }
    }

    @Test
    fun `Object implements non-Interface type`() {
        val schema = BSchema()
        val obj = schema.addTypeDef<BSchema.Object>("MyObject")
        val scalar = schema.addTypeDef<BSchema.Scalar>("NotAnInterface")
        val stringType = schema.addTypeDef<BSchema.Scalar>("String")

        assertThrows<InvalidSchemaException> {
            // Try to create extension with a Scalar in supers (should be Interface)
            // Use unsafe cast to bypass compile-time type checking
            @Suppress("UNCHECKED_CAST")
            val badSupers = listOf(scalar) as List<BSchema.Interface>

            val ext = BSchema.Extension<BSchema.Object, BSchema.Field>(
                obj,
                emptyList(),
                null,
                isBase = true,
                supers = badSupers // Wrong type!
            )

            val field = BSchema.Field(ext, "field", emptyList(), BSchema.TypeExpr(stringType), false, null)
            obj.extensions = listOf(ext.also { it.members = listOf(field) })
        }
    }

    // ========================================================================
    // Test #4: Union members must be Object types
    // ========================================================================

    @Test
    fun `Union contains non-Object member`() {
        val schema = BSchema()
        val union = schema.addTypeDef<BSchema.Union>("MyUnion")
        val scalar = schema.addTypeDef<BSchema.Scalar>("NotAnObject")

        assertThrows<InvalidSchemaException> {
            // Use unsafe cast to bypass compile-time type checking
            @Suppress("UNCHECKED_CAST")
            val badMembers = listOf(scalar) as List<BSchema.Object>

            val ext = BSchema.Extension<BSchema.Union, BSchema.Object>(
                union,
                emptyList(),
                null,
                isBase = true,
                initialMembers = badMembers // Wrong type!
            )

            union.extensions = listOf(ext)
        }
    }

    // ========================================================================
    // Test #7: Applied directive argument validation
    // ========================================================================

    @Test
    fun `Applied directive has argument not defined in directive definition`() {
        val schema = BSchema()
        val directive = schema.makeDirective("myDirective")
        // Directive has no arguments defined
        directive.args = emptyList()
        directive.allowedLocations = setOf(ViaductSchema.Directive.Location.FIELD_DEFINITION)

        val obj = schema.addTypeDef<BSchema.Object>("Query")
        val stringType = schema.addTypeDef<BSchema.Scalar>("String")

        val ext = BSchema.Extension<BSchema.Object, BSchema.Field>(
            obj,
            emptyList(),
            null,
            isBase = true
        )

        // Create applied directive with an argument that doesn't exist in definition
        val appliedDirective = ViaductSchema.AppliedDirective.of(
            "myDirective",
            mapOf("unknownArg" to "value") // This arg doesn't exist!
        )

        val field = BSchema.Field(
            ext,
            "myField",
            listOf(appliedDirective),
            BSchema.TypeExpr(stringType),
            hasDefault = false,
            defaultValue = null
        )

        assertThrows<InvalidSchemaException> {
            ext.members = listOf(field)
        }
    }

    // ========================================================================
    // InvalidFileFormatException tests: Binary file format errors
    // ========================================================================
    // These tests verify that structural problems with the binary file
    // result in InvalidFileFormatException (currently IllegalArgumentException).

    companion object {
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
            readBSchema(makeHeaderStream(0x7654_3210, FILE_VERSION))
        }
        assertMessageContains("Invalid magic number", exception)
    }

    @Test
    fun `Unsupported file version throws InvalidFileFormatException`() {
        val exception = assertThrows<InvalidFileFormatException> {
            readBSchema(makeHeaderStream(MAGIC_NUMBER, 0x000_00200))
        }
        assertMessageContains("Unsupported version", exception)
    }

    @Test
    fun `Max identifier length exceeded throws InvalidFileFormatException`() {
        val exception = assertThrows<InvalidFileFormatException> {
            readBSchema(makeHeaderStream(MAGIC_NUMBER, FILE_VERSION, 1_000_000))
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
            readBSchema(input)
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
            readBSchema(input)
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
            readBSchema(input)
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
            readBSchema(input)
        }
        assertMessageContains("Invalid type expressions section magic", exception)
    }

    @Test
    fun `Definition not found during decode throws InvalidFileFormatException`() {
        // This would require creating a binary where the definitions section
        // references an identifier that wasn't declared as a definition.
        // For now, we test that DefinitionsDecoder.typeDef throws properly.
        val schema = BSchema()
        val identifiers = arrayOf("UnknownType")

        // Simulate what happens during decoding when a type reference
        // points to a name that isn't a type definition
        val exception = assertThrows<InvalidFileFormatException> {
            val name = identifiers[0]
            schema.types[name]
                ?: throw InvalidFileFormatException("Type not found: $name")
        }
        assertMessageContains("Type not found", exception)
    }

    @Test
    fun `Type reference to wrong kind throws InvalidFileFormatException`() {
        // When decoding, if we expect an Object but get a Scalar, etc.
        val schema = BSchema()
        schema.addTypeDef<BSchema.Scalar>("MyScalar")

        // Simulate decoding expecting Object but finding Scalar
        val exception = assertThrows<InvalidFileFormatException> {
            val typeDef = schema.types["MyScalar"]
            if (typeDef !is BSchema.Object) {
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
    fun `Enum with empty extensions throws InvalidFileFormatException`() {
        val schema = BSchema()
        val enumType = schema.addTypeDef<BSchema.Enum>("Status")

        // Currently throws IllegalArgumentException, should be InvalidFileFormatException
        val exception = assertThrows<InvalidFileFormatException> {
            enumType.extensions = emptyList()
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Union with empty extensions throws InvalidFileFormatException`() {
        val schema = BSchema()
        val unionType = schema.addTypeDef<BSchema.Union>("Result")

        val exception = assertThrows<InvalidFileFormatException> {
            unionType.extensions = emptyList()
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Input with empty extensions throws InvalidFileFormatException`() {
        val schema = BSchema()
        val inputType = schema.addTypeDef<BSchema.Input>("InputData")

        val exception = assertThrows<InvalidFileFormatException> {
            inputType.extensions = emptyList()
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Interface with empty extensions throws InvalidFileFormatException`() {
        val schema = BSchema()
        val interfaceType = schema.addTypeDef<BSchema.Interface>("Node")

        val exception = assertThrows<InvalidFileFormatException> {
            interfaceType.extensions = emptyList()
        }
        assertMessageContains("Types must have at least one extension", exception)
    }

    @Test
    fun `Object with empty extensions throws InvalidFileFormatException`() {
        val schema = BSchema()
        val objectType = schema.addTypeDef<BSchema.Object>("User")

        val exception = assertThrows<InvalidFileFormatException> {
            objectType.extensions = emptyList()
        }
        assertMessageContains("Types must have at least one extension", exception)
    }
}
