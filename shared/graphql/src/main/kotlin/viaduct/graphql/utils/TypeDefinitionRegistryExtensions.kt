package viaduct.graphql.utils

import graphql.language.AstPrinter
import graphql.language.DirectiveDefinition
import graphql.language.Node
import graphql.language.TypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry
import java.util.Optional
import java.util.function.Predicate
import kotlin.jvm.optionals.getOrNull

/**
 * Render this TypeDefinitionRegistry to an SDL string
 * @param typePredicate a [Predicate] that can be used to filter schema types before rendering
 * @param directivePredicate a [Predicate] that can be used to filter directive definitions before rendering
 */
fun TypeDefinitionRegistry.toSDL(
    typePredicate: Predicate<TypeDefinition<*>> = Predicates.alwaysTrue(),
    directivePredicate: Predicate<DirectiveDefinition> = Predicates.alwaysTrue()
): String =
    StringBuilder()
        .maybeAppend(schemaDefinition())
        .maybeAppend(directiveDefinitions.values, directivePredicate)
        .maybeAppend(typeDefinitions(), typePredicate)
        .maybeAppend(extensionDefinitions())
        .toString()

/** return a list of all extension definitions */
fun TypeDefinitionRegistry.extensionDefinitions(): List<Node<*>> =
    buildList {
        addAll(schemaExtensionDefinitions)
        scalarTypeExtensions().values.forEach(::addAll)
        objectTypeExtensions().values.forEach(::addAll)
        interfaceTypeExtensions().values.forEach(::addAll)
        unionTypeExtensions().values.forEach(::addAll)
        enumTypeExtensions().values.forEach(::addAll)
        inputObjectTypeExtensions().values.forEach(::addAll)
    }

/** return a list of all type definitions */
fun TypeDefinitionRegistry.typeDefinitions(): List<TypeDefinition<*>> =
    buildList {
        addAll(getTypes(TypeDefinition::class.java))
        addAll(scalars().values)
    }

object Predicates {
    val AlwaysTrue: Predicate<Any> = Predicate { _ -> true }
    val AlwaysFalse: Predicate<Any> = Predicate { _ -> false }

    @Suppress("UNCHECKED_CAST")
    fun <T> alwaysTrue(): Predicate<T> = AlwaysTrue as Predicate<T>

    fun <T> alwaysFalse(): Predicate<T> = AlwaysFalse as Predicate<T>

    fun <T> const(value: Boolean): Predicate<T> = Predicate { _ -> value }
}

private fun <T : Node<*>> StringBuilder.maybeAppend(
    node: Optional<T>,
    predicate: Predicate<T> = Predicates.alwaysTrue()
): StringBuilder = this.maybeAppend(node.getOrNull(), predicate)

private fun <T : Node<*>> StringBuilder.maybeAppend(
    node: T?,
    predicate: Predicate<T> = Predicates.alwaysTrue()
): StringBuilder =
    this.also {
        if (node != null && predicate.test(node)) {
            AstPrinter.printAstTo(node, this)
            append("\n")
        }
    }

private fun <T : Node<*>> StringBuilder.maybeAppend(
    nodes: Iterable<T>,
    predicate: Predicate<T> = Predicates.alwaysTrue()
): StringBuilder =
    this.also {
        for (node in nodes) {
            maybeAppend(node, predicate)
        }
    }
