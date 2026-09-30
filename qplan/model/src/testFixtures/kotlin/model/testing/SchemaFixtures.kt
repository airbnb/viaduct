package model.testing

import graphql.language.NamedNode
import graphql.language.Node
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.lowering.LOWERING_SYNTHETIC_NAME_TOKEN
import model.lowering.VIADUCT_IGNORE_SYMBOL
import model.lowering.ViaductAndGJSchema
import model.requireType
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.toGraphQLSchema

private val STANDARD_SCALAR_NAMES = setOf("Int", "Float", "String", "Boolean", "ID")
private val SCALARS_REQUIRING_REGISTRATION = setOf("Int", "Float", "ID")
private val STANDARD_DIRECTIVE_NAMES =
    setOf(
        "skip",
        "include",
        "deprecated",
        "specifiedBy",
        "oneOf",
        "parent",
        "namespaceType",
    )

fun ViaductAndGJSchema.Companion.fromSDL(schemaSDL: String): ViaductAndGJSchema {
    val graphQLSchema = parseSchema(schemaSDL)
    require(graphQLSchema.mutationType == null) {
        "Mutation roots are outside the model"
    }
    require(graphQLSchema.subscriptionType == null) {
        "Subscription roots are outside the model"
    }
    require(graphQLSchema.queryType.name == "Query") {
        "The model requires the query root to be named Query"
    }
    val schemas = ViaductAndGJSchema.fromGraphQLSchema(graphQLSchema)
    val scalarsNeeded =
        SCALARS_REQUIRING_REGISTRATION.filterTo(mutableSetOf()) {
            graphQLSchema.getType(it) != null
        }
    schemas.loweredSchema.toGraphQLSchema(scalarsNeeded = scalarsNeeded)
    return schemas
}

private fun parseSchema(schemaSDL: String): GraphQLSchema {
    validateReservedNames(schemaSDL)
    val registry = SchemaParser().parse(schemaSDL)
    val nonStandardScalars =
        (
            registry.scalars().keys +
                registry.scalarTypeExtensions().keys
        ) - STANDARD_SCALAR_NAMES
    require(nonStandardScalars.isEmpty()) {
        "Non-standard scalar types are outside the model: " +
            nonStandardScalars.sorted().joinToString()
    }

    val unsupportedDirectives =
        registry.directiveDefinitions
            .filterKeys { it !in STANDARD_DIRECTIVE_NAMES }
            .filterValues { definition ->
                definition.directiveLocations.map { it.name }.toSet() != setOf("FIELD") ||
                    definition.inputValueDefinitions.isNotEmpty()
            }
            .keys
    require(unsupportedDirectives.isEmpty()) {
        "Only no-argument non-standard FIELD directives are supported: " +
            unsupportedDirectives.sorted().joinToString()
    }

    return UnExecutableSchemaGenerator
        .makeUnExecutableSchema(registry)
}

private fun validateReservedNames(schemaSDL: String) {
    val invalidNames = linkedSetOf<String>()
    val ignoredNames = linkedSetOf<String>()

    fun visit(node: Node<*>) {
        val name = (node as? NamedNode<*>)?.name
        if (name != null && name.contains(LOWERING_SYNTHETIC_NAME_TOKEN)) {
            invalidNames.add(name)
        }
        if (name == VIADUCT_IGNORE_SYMBOL) {
            ignoredNames.add(name)
        }
        node.children.forEach(::visit)
    }

    Parser.parse(schemaSDL).children.forEach(::visit)
    require(invalidNames.isEmpty()) {
        "Source schema names cannot contain reserved token " +
            "$LOWERING_SYNTHETIC_NAME_TOKEN: ${invalidNames.sorted().joinToString()}"
    }
    require(ignoredNames.isEmpty()) {
        "Source schema names cannot use reserved symbol $VIADUCT_IGNORE_SYMBOL"
    }
}

internal fun ViaductAndGJSchema.sourceCompositeType(type: ViaductSchema.CompositeTypeDef): GraphQLCompositeType {
    require(loweredSchema.requireType(type.name) == type) {
        "${type.name} is not canonical in this schema"
    }
    return graphQLSchema.getType(type.name) as? GraphQLCompositeType
        ?: throw IllegalArgumentException("${type.name} is not a source composite type")
}

/** The canonical concrete object types available to fixture registry lowering. */
internal val ViaductAndGJSchema.objectTypes: List<ViaductSchema.Object>
    get() =
        graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLObjectType>()
            .filterNot { it.name.startsWith("__") }
            .map { loweredSchema.requireType(it.name) as ViaductSchema.Object }
