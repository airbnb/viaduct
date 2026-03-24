package viaduct.engine.runtime.rewriter

import graphql.language.Argument
import graphql.language.Comment
import graphql.language.Directive
import graphql.language.Field
import graphql.language.IgnoredChars
import graphql.language.SelectionSet
import graphql.language.SourceLocation
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.FieldMetadata
import viaduct.engine.runtime.FieldWithMetadata

class FieldWithMetadataTest {
    @Test
    fun `test FieldWithMetadata properties`() {
        val name = "testField"
        val alias = "aliasField"
        val arguments = listOf<Argument>()
        val directives = listOf<Directive>()
        val selectionSet: SelectionSet? = null
        val sourceLocation: SourceLocation? = null
        val comments = listOf<Comment>()
        val ignoredChars = IgnoredChars.EMPTY
        val additionalData = mapOf<String, String>()
        val metadata = listOf<FieldMetadata>()

        val fieldWithMetadata = FieldWithMetadata(
            name,
            alias,
            arguments,
            directives,
            selectionSet,
            sourceLocation,
            comments,
            ignoredChars,
            additionalData,
            metadata
        )

        assertEquals(name, fieldWithMetadata.name)
        assertEquals(alias, fieldWithMetadata.alias)
        assertEquals(arguments, fieldWithMetadata.arguments)
        assertEquals(directives, fieldWithMetadata.directives)
        assertEquals(selectionSet, fieldWithMetadata.selectionSet)
        assertEquals(sourceLocation, fieldWithMetadata.sourceLocation)
        assertEquals(comments, fieldWithMetadata.comments)
        assertEquals(ignoredChars, fieldWithMetadata.ignoredChars)
        assertEquals(additionalData, fieldWithMetadata.additionalData)
        assertEquals(metadata, fieldWithMetadata.metadata)
    }

    @Test
    fun `test FieldWithMetadata deepCopy`() {
        val name = "testField"
        val alias = "aliasField"
        val arguments = listOf<Argument>()
        val directives = listOf<Directive>()
        val selectionSet: SelectionSet? = null
        val sourceLocation: SourceLocation? = null
        val comments = listOf<Comment>()
        val ignoredChars = IgnoredChars.EMPTY
        val additionalData = mapOf<String, String>()
        val metadata = listOf<FieldMetadata>()

        val fieldWithMetadata = FieldWithMetadata(
            name,
            alias,
            arguments,
            directives,
            selectionSet,
            sourceLocation,
            comments,
            ignoredChars,
            additionalData,
            metadata
        )

        val copiedField = fieldWithMetadata.deepCopy()

        assertEquals(fieldWithMetadata.name, copiedField.name)
        assertEquals(fieldWithMetadata.alias, copiedField.alias)
        assertEquals(fieldWithMetadata.arguments, copiedField.arguments)
        assertEquals(fieldWithMetadata.directives, copiedField.directives)
        assertEquals(fieldWithMetadata.selectionSet, copiedField.selectionSet)
        assertEquals(fieldWithMetadata.sourceLocation, copiedField.sourceLocation)
        assertEquals(fieldWithMetadata.comments, copiedField.comments)
        assertEquals(fieldWithMetadata.ignoredChars, copiedField.ignoredChars)
        assertEquals(fieldWithMetadata.additionalData, copiedField.additionalData)
        assertEquals(fieldWithMetadata.metadata, copiedField.metadata)
    }

    @Test
    fun `test FieldWithMetadata fromFieldWithMetadata single metadata`() {
        val field = Field("testField")
        val metadata = object : FieldMetadata {}

        val fieldWithMetadata = FieldWithMetadata.fromFieldWithMetadata(field, metadata)

        assertEquals(field.name, fieldWithMetadata.name)
        assertEquals(listOf(metadata), fieldWithMetadata.metadata)
    }

    @Test
    fun `test FieldWithMetadata fromFieldWithMetadata multiple metadata`() {
        val field = Field("testField")
        val metadata1 = object : FieldMetadata {}
        val metadata2 = object : FieldMetadata {}

        val fieldWithMetadata = FieldWithMetadata.fromFieldWithMetadata(field, listOf(metadata1, metadata2))

        assertEquals(field.name, fieldWithMetadata.name)
        assertEquals(listOf(metadata1, metadata2), fieldWithMetadata.metadata)
    }

    @Test
    fun `test FieldWithMetadata toString`() {
        val name = "testField"
        val alias = "aliasField"
        val arguments = listOf<Argument>()
        val directives = listOf<Directive>()
        val selectionSet: SelectionSet? = null
        val sourceLocation: SourceLocation? = null
        val comments = listOf<Comment>()
        val ignoredChars = IgnoredChars.EMPTY
        val additionalData = mapOf<String, String>()
        val metadata = listOf<FieldMetadata>()

        val fieldWithMetadata = FieldWithMetadata(
            name,
            alias,
            arguments,
            directives,
            selectionSet,
            sourceLocation,
            comments,
            ignoredChars,
            additionalData,
            metadata
        )

        val expectedString = "FieldWithMetadata{name='testField', alias='aliasField', arguments=[], directives=[], selectionSet=null, metadata=[]}"
        assertEquals(expectedString, fieldWithMetadata.toString())
    }

    @Test
    fun `test FieldWithMetadata transform`() {
        val name = "testField"
        val alias = "aliasField"
        val arguments = listOf<Argument>()
        val directives = listOf<Directive>()
        val selectionSet: SelectionSet? = null
        val sourceLocation: SourceLocation? = null
        val comments = listOf<Comment>()
        val ignoredChars = IgnoredChars.EMPTY
        val additionalData = mapOf<String, String>()
        val metadata = listOf<FieldMetadata>()

        val fieldWithMetadata = FieldWithMetadata(
            name,
            alias,
            arguments,
            directives,
            selectionSet,
            sourceLocation,
            comments,
            ignoredChars,
            additionalData,
            metadata
        )

        val transformedField = fieldWithMetadata.transform(Consumer { it.name("newName") })

        assertEquals("newName", transformedField.name)
        assertEquals(metadata, (transformedField as FieldWithMetadata).metadata)
    }

    @Test
    fun `test FieldWithMetadata fromFieldWithMetadata with existing metadata`() {
        val field = FieldWithMetadata(
            name = "testField",
            alias = "aliasField",
            arguments = listOf(),
            directives = listOf(),
            selectionSet = null,
            sourceLocation = null,
            comments = listOf(),
            ignoredChars = IgnoredChars.EMPTY,
            additionalData = mapOf(),
            metadata = listOf(object : FieldMetadata {})
        )
        val newMetadata = object : FieldMetadata {}

        val fieldWithMetadata = FieldWithMetadata.fromFieldWithMetadata(field, newMetadata)

        assertEquals(field.name, fieldWithMetadata.name)
        assertEquals(field.metadata + newMetadata, fieldWithMetadata.metadata)
    }
}
