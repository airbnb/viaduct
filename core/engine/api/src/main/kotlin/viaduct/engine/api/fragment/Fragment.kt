package viaduct.engine.api.fragment

import com.airbnb.viaduct.errors.ViaductInternalExecutionException
import com.airbnb.viaduct.errors.ViaductInvalidConfigurationException
import graphql.execution.MergedField
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import org.intellij.lang.annotations.Language
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.graphql.utils.addTypeName

data class Fragment(
    private val fragmentSource: FragmentSource,
    val variables: FragmentVariables = FragmentVariables.EMPTY
) : Iterable<MergedField> {
    constructor(
        @Language("GraphQL") documentString: String,
        variables: FragmentVariables = FragmentVariables.EMPTY
    ) :
        this(
            FragmentSource.create(documentString),
            variables
        )

    constructor(
        @Language("GraphQL") documentString: String,
        variables: Map<String, Any?>
    ) :
        this(
            FragmentSource.create(documentString),
            variables = FragmentVariables.fromMap(variables)
        )

    constructor(doc: Document, variables: Map<String, Any>) :
        this(
            FragmentSource.create(doc),
            variables = FragmentVariables.fromMap(variables)
        )

    val document: String
        get() = fragmentSource.documentString()

    val parsedDocument: Document
        get() = fragmentSource.document()

    /**
     * The field definition in the fragment document, with __typename added
     */
    val definition by lazy {
        try {
            getFragmentDefinition(parsedDocument, variables.asMap().keys)
        } catch (e: Exception) {
            throw ViaductFragmentParsingError(
                "Error when parsing fragment: ${e.message}. " +
                    "Fragment string:\n\n=====\n$document",
                cause = e
            )
        }
    }

    private val fieldsMap by lazy {
        mergeFields(definition.selectionSet)
    }

    override fun iterator(): Iterator<MergedField> {
        return fieldsMap.map { it }.listIterator()
    }

    private fun mergeFields(selectionSet: SelectionSet): List<MergedField> {
        return collectFields(selectionSet).groupBy {
            it.name + it.arguments
        }.map {
            MergedField.newMergedField(it.value).build()
        }
    }

    private fun collectFields(selectionSet: SelectionSet): List<Field> {
        return selectionSet.selections.map { selection ->
            when (selection) {
                is Field -> listOf(selection)
                is InlineFragment -> collectFields(selection.selectionSet)
                is FragmentSpread -> throw ViaductInvalidConfigurationException(
                    "Named fragments not supported in required selection sets"
                )

                else -> throw ViaductInvalidConfigurationException(
                    "Invalid field selection $selection"
                )
            }
        }.flatten()
    }

    private fun getFragmentDefinition(
        document: Document,
        variableNames: Set<String>
    ): FragmentDefinition {
        val fragmentDefinitions = document.getDefinitionsOfType(FragmentDefinition::class.java)

        require(fragmentDefinitions.size == 1) {
            "Invalid fragment definition"
        }

        val fragmentDefinition = fragmentDefinitions.first()

        val variableReferencesByName = fragmentDefinitions.allVariableReferencesByName()
        val unboundVariableNames = variableReferencesByName.keys.filterNot(variableNames::contains)
        if (unboundVariableNames.isNotEmpty()) {
            throw ViaductInternalExecutionException(
                "Fragment '${fragmentDefinition.name}' has unbound variables:\n" +
                    unboundVariableNames.joinToString("\n") { "* $it" } +
                    "\nEnsure they are passed in the `vars` argument when creating this fragment."
            )
        }

        return fragmentDefinition.addTypeName()
    }

    companion object {
        val empty: Fragment = Fragment(FragmentSource.Companion.Empty)
    }
}

/**
 * A FragmentSource describes the data needed to generate the body of a Fragment.
 * See [FragmentSource.create] for methods of generating a FragmentSource
 */
interface FragmentSource {
    /** render this FragmentSource as a String */
    fun documentString(): String

    /** render this FragmentSource as a parsed GraphQL Document */
    fun document(): Document

    companion object {
        /** create a FragmentSource from a string that describes a GraphQL document */
        fun create(str: String): FragmentSource = FromString(str)

        /** create a FragmentSource from a parsed Document */
        fun create(doc: Document): FragmentSource = FromDocument(doc)

        /** An empty FragmentSource */
        object Empty : FragmentSource {
            private val doc = Document.newDocument().build()

            override fun documentString(): String = ""

            override fun document(): Document = doc
        }

        /** A FragmentSource based on an unparsed document string */
        private data class FromString(val str: String) : FragmentSource {
            private val doc by lazy {
                CachedDocumentParser.parseDocument(str)
            }

            override fun documentString(): String = str

            override fun document(): Document = doc
        }

        /** A FragmentSource based on a parsed Document object */
        private data class FromDocument(val doc: Document) : FragmentSource {
            private val str by lazy {
                AstPrinter.printAstCompact(doc)
            }

            override fun documentString(): String = str

            override fun document(): Document = doc
        }
    }
}

class ViaductFragmentParsingError(message: String, cause: Throwable) : RuntimeException(message, cause)

fun fragment(
    @Language("GraphQL") fragment: String,
    vars: FragmentVariables = FragmentVariables.EMPTY
): Fragment = Fragment(fragment, vars)

data class FragmentTraversalState(val errors: List<Error> = listOf())

fun FragmentDefinition.addTypeName(): FragmentDefinition {
    return this.transform {
        val selSet = this.selectionSet.addTypeName()
        it.selectionSet(selSet)
    }
}

fun FragmentDefinition.toDocumentString(): String =
    this
        .let { Document.newDocument().definition(it).build() }
        .let(AstPrinter::printAst)
