package viaduct.graphql.schema.graphqljava

import graphql.language.AbstractDescribedNode
import graphql.language.ArrayValue
import graphql.language.Definition
import graphql.language.DirectiveDefinition
import graphql.language.Document
import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.ObjectTypeDefinition
import graphql.language.SDLExtensionDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.SchemaExtensionDefinition
import graphql.language.TypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.parser.InvalidSyntaxException
import graphql.parser.MultiSourceReader
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.Reader
import java.io.StringReader
import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.utils.DefaultSchemaFactory

object SchemaDefinitionReconciler {
    fun parse(reader: Reader): TypeDefinitionRegistry = reconcile(readDefinitions(reader))

    fun parseSources(sources: Sequence<Pair<String, String>>): TypeDefinitionRegistry =
        reconcile(
            sources.flatMap { (name, content) ->
                if (content.lineSequence().all { line -> line.substringBefore('#').all { it in " \t,\uFEFF" } }) {
                    emptyList()
                } else {
                    // MultiSourceReader scans prior sources for every AST location; keep each lookup local.
                    MultiSourceReader.newMultiSourceReader().reader(StringReader(content), name).trackData(true).build().use(::readDefinitions)
                }
            }.toList()
        )

    private fun readDefinitions(reader: Reader): List<Definition<*>> =
        try {
            Parser.parse(
                ParserEnvironment.newParserEnvironment()
                    .document(reader)
                    .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                    .build(),
            ).definitions
        } catch (e: InvalidSyntaxException) {
            val source = e.location?.sourceName
            val plugin = SchemaProvenance.parse(source)?.supplyingPlugin
            val message = "at $source:${e.location?.line}:${e.location?.column}: ${e.message}"
            throw IllegalArgumentException(if (plugin == null) "Invalid developer SDL $message" else SchemaProvenance.pluginError(listOf(plugin), message), e)
        }

    private fun reconcile(definitions: List<Definition<*>>): TypeDefinitionRegistry {
        val accepted = mutableListOf<IndexedValue<Definition<*>>>()
        val byIdentity = mutableMapOf<String, MutableList<Definition<*>>>()
        val claimOrder = compareBy<Definition<*>> { provenance(it)?.generator != null }
            .thenBy { it.sourceLocation?.sourceName }
            .thenBy { it.sourceLocation?.line }
            .thenBy { it.sourceLocation?.column }
        definitions.withIndex().sortedWith { left, right -> claimOrder.compare(left.value, right.value) }.forEach { indexed ->
            val candidate = indexed.value
            val existing = byIdentity.getOrPut(identity(candidate)) { mutableListOf() }
            if (provenance(candidate)?.generator != null) {
                val identical = existing.firstOrNull { semanticallyEqual(it, candidate) }
                val sameScalarSupplier = candidate !is ScalarTypeDefinition || DefaultSchemaFactory.isDefaultScalar(candidate.name) ||
                    provenance(identical)?.supplyingPlugin == provenance(candidate)?.supplyingPlugin
                if (identical != null && sameScalarSupplier) return@forEach
            }
            if (candidate !is SDLExtensionDefinition && existing.isNotEmpty()) {
                val claims = (existing + candidate).joinToString(" and ") { "${it.sourceLocation?.sourceName}:${it.sourceLocation?.line}:${it.sourceLocation?.column}" }
                val plugins = (existing + candidate).mapNotNull { provenance(it)?.supplyingPlugin }
                val message = "Conflicting GraphQL definition '${(candidate as? NamedNode<*>)?.name ?: "schema"}' supplied by $claims"
                throw IllegalArgumentException(if (plugins.isEmpty()) message else SchemaProvenance.pluginError(plugins, message))
            }
            accepted += indexed
            existing += candidate
        }
        val reconciled = accepted.sortedBy { it.index }.map { it.value }
        validateMembers(reconciled)
        return SchemaParser().buildRegistry(Document.newDocument().definitions(reconciled).build())
    }

    private fun validateMembers(definitions: List<Definition<*>>) {
        val members = mutableMapOf<String, Node<*>>()
        definitions.forEach { definition ->
            val name = (definition as? NamedNode<*>)?.name ?: "schema"
            val (kind, nodes) = when (definition) {
                is ObjectTypeDefinition -> "object-field" to definition.fieldDefinitions
                is InterfaceTypeDefinition -> "interface-field" to definition.fieldDefinitions
                is InputObjectTypeDefinition -> "input-field" to definition.inputValueDefinitions
                is EnumTypeDefinition -> "enum-value" to definition.enumValueDefinitions
                is UnionTypeDefinition -> "union-member" to definition.memberTypes
                is SchemaDefinition -> "schema-operation" to definition.operationTypeDefinitions
                is SchemaExtensionDefinition -> "schema-operation" to definition.operationTypeDefinitions
                else -> return@forEach
            }
            nodes.forEach { node ->
                val member = (node as? NamedNode<*>)?.name ?: node.toString()
                val previous = members.putIfAbsent("$kind:$name:$member", node)
                if (previous != null) {
                    val claims = listOf(previous, node).joinToString(" and ") { "${it.sourceLocation?.sourceName}:${it.sourceLocation?.line}:${it.sourceLocation?.column}" }
                    val plugins = listOf(previous, node).mapNotNull { SchemaProvenance.parse(it.sourceLocation?.sourceName)?.supplyingPlugin }
                    val message = "Conflicting GraphQL member '$name.$member' supplied by $claims"
                    throw IllegalArgumentException(if (plugins.isEmpty()) message else SchemaProvenance.pluginError(plugins, message))
                }
            }
        }
    }

    private fun provenance(definition: Definition<*>?) = SchemaProvenance.parse(definition?.sourceLocation?.sourceName)

    private fun identity(definition: Definition<*>): String =
        when (definition) {
            is SDLExtensionDefinition -> "extension:${definition.javaClass.name}:${(definition as? NamedNode<*>)?.name}"
            is TypeDefinition<*> -> "type:${(definition as NamedNode<*>).name}"
            is DirectiveDefinition -> "directive:${definition.name}"
            is SchemaDefinition -> "schema"
            else -> "${definition.javaClass.name}:${(definition as? NamedNode<*>)?.name}"
        }

    fun semanticallyEqual(
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
            if (left is ArrayValue || key == ObjectTypeDefinition.CHILD_DIRECTIVES ||
                left is EnumTypeDefinition && key == EnumTypeDefinition.CHILD_ENUM_VALUE_DEFINITIONS ||
                left is UnionTypeDefinition && key == UnionTypeDefinition.CHILD_MEMBER_TYPES
            ) {
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
}
