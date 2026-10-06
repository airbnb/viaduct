package viaduct.engine.runtime2.model.testing

import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.ScalarTypeDefinition
import graphql.language.TypeName
import graphql.parser.Parser
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.SchemaTransformer
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import graphql.util.TreeTransformerUtil
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.lowering.LOWERING_SYNTHETIC_NAME_TOKEN
import viaduct.engine.runtime2.schema.lowering.VIADUCT_IGNORE_SYMBOL
import viaduct.graphql.Scalars
import viaduct.graphql.schema.graphqljava.toGraphQLSchema

private val VIADUCT_SCALARS = Scalars.viaductStandardScalars.filterNot { it.name == "Time" }.associateBy { it.name }
private val STANDARD_SCALAR_NAMES = setOf("Int", "Float", "String", "Boolean", "ID") + VIADUCT_SCALARS.keys
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
        "resolver",
    )

fun ViaductAndGJSchema.Companion.fromSDL(schemaSDL: String): ViaductAndGJSchema {
    val graphQLSchema = parseSchema(schemaSDL)
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
    schemas.loweredSchema.toGraphQLSchema(scalarsNeeded = scalarsNeeded, additionalScalars = VIADUCT_SCALARS.keys.intersect(graphQLSchema.typeMap.keys))
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

    fun registerReferencedScalars(node: Node<*>) {
        if (node is TypeName && node.name in VIADUCT_SCALARS && node.name !in registry.scalars()) {
            registry.add(ScalarTypeDefinition.newScalarTypeDefinition().name(node.name).build())
        }
        node.children.forEach(::registerReferencedScalars)
    }
    registerReferencedScalars(Parser.parse(schemaSDL))
    return SchemaTransformer.transformSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(registry),
        object : GraphQLTypeVisitorStub() {
            override fun visitGraphQLScalarType(
                node: GraphQLScalarType,
                context: TraverserContext<GraphQLSchemaElement>,
            ): TraversalControl {
                val scalar = VIADUCT_SCALARS[node.name] ?: return TraversalControl.CONTINUE
                return TreeTransformerUtil.changeNode(context, scalar.transform { it.definition(node.definition) })
            }
        },
    )
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
