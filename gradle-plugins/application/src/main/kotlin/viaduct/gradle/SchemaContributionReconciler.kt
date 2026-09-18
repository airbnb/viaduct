package viaduct.gradle

import graphql.language.AbstractDescribedNode
import graphql.language.ArrayValue
import graphql.language.AstPrinter
import graphql.language.Definition
import graphql.language.DirectiveDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.SDLExtensionDefinition
import graphql.language.SchemaDefinition
import graphql.language.SchemaExtensionDefinition
import graphql.language.TypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import graphql.parser.Parser
import java.io.File
import org.gradle.api.GradleException

internal object SchemaContributionReconciler {
    fun reconcile(
        applicationSchemaFiles: Collection<File>,
        contributionFiles: Collection<File>,
        outputDirectory: File,
    ): List<File> {
        outputDirectory.deleteRecursively()
        outputDirectory.mkdirs()

        val definitions =
            parse(applicationSchemaFiles, fromApplication = true) +
                parse(contributionFiles, fromApplication = false)
        validateUniqueDefinitions(definitions)
        validateExtensionMembers(definitions)

        val accepted = mutableListOf<ParsedDefinition>()
        definitions.filterNot(ParsedDefinition::fromApplication).forEach { candidate ->
            val alreadyDefined = definitions.any { existing ->
                existing !== candidate &&
                    (existing.fromApplication || existing in accepted) &&
                    definitionIdentity(existing.definition) == definitionIdentity(candidate.definition) &&
                    semanticallyEqual(existing.definition, candidate.definition)
            }
            if (!alreadyDefined) accepted += candidate
        }

        return accepted.mapIndexed { index, parsed ->
            val name = (parsed.definition as? NamedNode<*>)?.name ?: "schema"
            outputDirectory.resolve("${index.toString().padStart(4, '0')}-$name.graphqls").apply {
                writeText("${AstPrinter.printAst(parsed.definition)}\n")
            }
        }
    }

    private fun parse(
        files: Collection<File>,
        fromApplication: Boolean,
    ): List<ParsedDefinition> =
        expandSchemaFiles(files).flatMap { file ->
            Parser().parseDocument(file.readText()).definitions.map { definition ->
                ParsedDefinition(definition, file, fromApplication)
            }
        }

    private fun validateUniqueDefinitions(definitions: List<ParsedDefinition>) {
        definitions
            .filterNot { it.definition is SDLExtensionDefinition }
            .groupBy { definitionIdentity(it.definition) }
            .forEach { (_, candidates) ->
                val variants = candidates.distinctBySemanticDefinition()
                if (variants.size > 1) throwConflict(candidates)
            }
    }

    private fun validateExtensionMembers(definitions: List<ParsedDefinition>) {
        definitions
            .flatMap(::extensionMembers)
            .groupBy(ExtensionMember::identity)
            .forEach { (_, candidates) ->
                val variants = candidates.distinctBySemanticNode()
                if (variants.size > 1) {
                    throw GradleException(
                        "Conflicting GraphQL extension member '${candidates.first().displayName}' was contributed by:\n" +
                            candidates.map(ExtensionMember::source).distinct().joinToString("\n") { "- ${it.absolutePath}" },
                    )
                }
            }
    }

    private fun extensionMembers(parsed: ParsedDefinition): List<ExtensionMember> {
        val definition = parsed.definition
        val typeName = (definition as? NamedNode<*>)?.name

        fun members(
            kind: String,
            nodes: List<Node<*>>,
        ): List<ExtensionMember> =
            nodes.map { node ->
                val memberName = (node as? NamedNode<*>)?.name ?: AstPrinter.printAstCompact(node)
                ExtensionMember("$kind:$typeName:$memberName", "$typeName.$memberName", node, parsed.source)
            }

        return when (definition) {
            is ObjectTypeExtensionDefinition -> members("object-field", definition.fieldDefinitions)
            is InterfaceTypeExtensionDefinition -> members("interface-field", definition.fieldDefinitions)
            is InputObjectTypeExtensionDefinition -> members("input-field", definition.inputValueDefinitions)
            is EnumTypeExtensionDefinition -> members("enum-value", definition.enumValueDefinitions)
            is UnionTypeExtensionDefinition -> members("union-member", definition.memberTypes)
            is SchemaExtensionDefinition -> members("schema-operation", definition.operationTypeDefinitions)
            else -> emptyList()
        }
    }

    private fun definitionIdentity(definition: Definition<*>): String =
        when (definition) {
            is SchemaExtensionDefinition -> "schema-extension"
            is SDLExtensionDefinition -> "extension:${definition.javaClass.name}:${(definition as NamedNode<*>).name}"
            is TypeDefinition<*> -> "type:${(definition as NamedNode<*>).name}"
            is DirectiveDefinition -> "directive:${definition.name}"
            is SchemaDefinition -> "schema"
            else -> "${definition.javaClass.name}:${(definition as? NamedNode<*>)?.name.orEmpty()}"
        }

    private fun List<ParsedDefinition>.distinctBySemanticDefinition(): List<ParsedDefinition> =
        fold(mutableListOf()) { distinct, candidate ->
            if (distinct.none { semanticallyEqual(it.definition, candidate.definition) }) distinct += candidate
            distinct
        }

    private fun List<ExtensionMember>.distinctBySemanticNode(): List<ExtensionMember> =
        fold(mutableListOf()) { distinct, candidate ->
            if (distinct.none { semanticallyEqual(it.node, candidate.node) }) distinct += candidate
            distinct
        }

    private fun semanticallyEqual(
        left: Node<*>,
        right: Node<*>,
    ): Boolean {
        if (left.javaClass != right.javaClass || !left.isEqualTo(right)) return false
        if (left is DirectiveDefinition && right is DirectiveDefinition && left.isRepeatable != right.isRepeatable) return false
        if (left is AbstractDescribedNode<*> && right is AbstractDescribedNode<*>) {
            if (left.description?.content != right.description?.content) return false
        }

        val leftChildren = left.namedChildren.children
        val rightChildren = right.namedChildren.children
        if (leftChildren.keys != rightChildren.keys) return false
        return leftChildren.all { (key, leftNodes) ->
            val rightNodes = rightChildren.getValue(key)
            if (leftNodes.size != rightNodes.size) return@all false
            if (left is ArrayValue) {
                leftNodes.zip(rightNodes).all { (leftNode, rightNode) -> semanticallyEqual(leftNode, rightNode) }
            } else {
                val unmatched = rightNodes.toMutableList()
                leftNodes.all { leftNode ->
                    val index = unmatched.indexOfFirst { rightNode -> semanticallyEqual(leftNode, rightNode) }
                    if (index < 0) false else unmatched.removeAt(index).let { true }
                }
            }
        }
    }

    private fun throwConflict(variants: List<ParsedDefinition>): Nothing {
        val name = (variants.first().definition as? NamedNode<*>)?.name ?: "schema"
        throw GradleException(
            "Conflicting GraphQL definition '$name' was contributed by:\n" +
                variants.map(ParsedDefinition::source).distinct().joinToString("\n") { "- ${it.absolutePath}" },
        )
    }

    private fun expandSchemaFiles(files: Collection<File>): List<File> =
        files.flatMap { file ->
            when {
                file.isDirectory -> file.walkTopDown()
                    .filter { it.isFile && it.extension == "graphqls" }
                    .toList()
                file.isFile && file.extension == "graphqls" -> listOf(file)
                else -> emptyList()
            }
        }.sortedBy { it.absolutePath }

    private data class ParsedDefinition(
        val definition: Definition<*>,
        val source: File,
        val fromApplication: Boolean,
    )

    private data class ExtensionMember(
        val identity: String,
        val displayName: String,
        val node: Node<*>,
        val source: File,
    )
}
