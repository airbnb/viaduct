package viaduct.engine.runtime

import graphql.language.Argument
import graphql.language.Comment
import graphql.language.Directive
import graphql.language.Field
import graphql.language.IgnoredChars
import graphql.language.SelectionSet
import graphql.language.SourceLocation
import java.util.function.Consumer

/**
 * Marker interface for metadata that can be attached to a field.
 */
interface FieldMetadata

/**
 * A field with metadata attached to it.
 *
 * This class is used to attach additional information to a field, such as metadata about how the field was rewritten.
 * The additional information is stored as a list of [FieldMetadata] objects.
 */
class FieldWithMetadata(
    name: String,
    alias: String?,
    arguments: List<Argument> = emptyList(),
    directives: List<Directive> = emptyList(),
    selectionSet: SelectionSet?,
    sourceLocation: SourceLocation?,
    comments: List<Comment> = emptyList(),
    ignoredChars: IgnoredChars,
    additionalData: Map<String, String> = emptyMap(),
    val metadata: List<FieldMetadata> = emptyList()
) : Field(name, alias, arguments, directives, selectionSet, sourceLocation, comments, ignoredChars, additionalData) {
    public override fun deepCopy(): FieldWithMetadata {
        return FieldWithMetadata(
            name,
            alias,
            deepCopy(arguments),
            deepCopy(directives),
            deepCopy(selectionSet),
            sourceLocation,
            comments,
            ignoredChars,
            additionalData,
            metadata
        )
    }

    public override fun toString(): String {
        return "FieldWithMetadata{" +
            "name='" + name + '\'' +
            ", alias='" + alias + '\'' +
            ", arguments=" + arguments +
            ", directives=" + directives +
            ", selectionSet=" + selectionSet +
            ", metadata=" + metadata +
            '}'
    }

    override fun transform(builderConsumer: Consumer<Builder>?): Field {
        return super.transform(builderConsumer).let {
            fromFieldWithMetadata(it, metadata)
        }
    }

    companion object {
        fun fromFieldWithMetadata(
            field: Field,
            metadata: FieldMetadata
        ): FieldWithMetadata {
            return fromFieldWithMetadata(field, listOf(metadata))
        }

        fun fromFieldWithMetadata(
            field: Field,
            metadata: List<FieldMetadata>
        ): FieldWithMetadata {
            val allMetadata =
                if (field is FieldWithMetadata) {
                    field.metadata + metadata
                } else {
                    metadata
                }
            return FieldWithMetadata(
                field.name,
                field.alias,
                field.arguments,
                field.directives,
                field.selectionSet,
                field.sourceLocation,
                field.comments,
                field.ignoredChars,
                field.additionalData,
                allMetadata
            )
        }
    }
}
