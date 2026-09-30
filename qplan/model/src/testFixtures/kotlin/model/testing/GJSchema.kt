package model.testing

import graphql.language.NamedNode
import graphql.language.Node
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.EngineErrorData
import model.EngineObjectDataEntry
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.engineObjectDataOf
import model.lowering.LOWERING_SYNTHETIC_NAME_TOKEN
import model.lowering.VIADUCT_IGNORE_SYMBOL
import model.lowering.lowerSchema
import model.lowering.loweredFieldFromSourceCoordinate
import model.lowering.sourceTypeExpr
import model.nodeRootFieldReferenceOf
import model.outputType
import model.qplanSchemaTypeOrNull
import model.requireObjectField
import model.requireType
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.toGraphQLSchema
import viaduct.graphql.schema.graphqljava.viaductSchema
import viaduct.graphql.schema.isNode
import viaduct.graphql.utils.GraphQLTypeRelations

/**
 * A fixture pair of the GraphQL-visible source schema and the canonical decoded [ViaductSchema].
 *
 * Construct the reasoning world's one schema before its values and assumptions so every non-error
 * value is created through this exact canonical graph. [EngineErrorData] is schema-independent.
 * The retained source schema parses and validates GraphQL selections.
 */
internal class GJSchema private constructor(
    internal val graphQLSchema: GraphQLSchema,
    internal val typeRelations: GraphQLTypeRelations,
    internal val loweredSchema: ViaductSchema,
) : ViaductSchema by loweredSchema {
    internal fun sourceCompositeType(type: ViaductSchema.CompositeTypeDef): GraphQLCompositeType {
        require(requireType(type.name) == type) {
            "${type.name} is not canonical in this schema"
        }
        return graphQLSchema.getType(type.name) as? GraphQLCompositeType
            ?: throw IllegalArgumentException("${type.name} is not a source composite type")
    }

    /** The canonical concrete object types available to fixture registry lowering. */
    internal val objectTypes: List<ViaductSchema.Object>
        get() =
            graphQLSchema.allTypesAsList
                .filterIsInstance<GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .map { requireType(it.name) as ViaductSchema.Object }

    internal fun fieldFromSource(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field = loweredSchema.loweredFieldFromSourceCoordinate(typeName, fieldName)

    internal fun sourceTypeExpr(field: ViaductSchema.Field): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = loweredSchema.sourceTypeExpr(field)

    internal fun isLoweredNodeField(field: ViaductSchema.Field): Boolean = sourceTypeExpr(field).baseTypeDef.isNode

    internal fun lowerSourceOutput(
        field: ViaductSchema.Field,
        output: ResolverOutputData?,
    ): ResolverOutputData? {
        val sourceTypeExpr = sourceTypeExpr(field)
        return if (isLoweredNodeField(field)) {
            lowerNodeReferences(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
            )
        } else {
            lowerOrdinaryOutput(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
                loweredTypeExpr = field.outputType,
            )
        }
    }

    internal fun lowerNodeResolverOutput(
        type: ViaductSchema.Object,
        output: ResolverOutputData?,
    ): ResolverOutputData? {
        if (output == null || output is EngineErrorData || output is RootFieldReferenceData) {
            return output
        }
        require(output is EngineObjectData.Sync) {
            "Node resolver for ${type.name} returned a non-object value"
        }
        return lowerOrdinaryObject(
            output,
            ViaductSchema.TypeExpr(type),
        )
    }

    internal fun lowerRootFieldReference(
        rootFieldPath: List<String>,
        sourceTypeName: String,
        arguments: Map<String, Any?>,
    ): RootFieldReferenceData {
        require(rootFieldPath.isNotEmpty()) { "Root-field-reference path must not be empty" }
        var sourceParent = graphQLSchema.queryType
        val canonicalPath =
            rootFieldPath.mapIndexed { index, fieldName ->
                val sourceField =
                    requireNotNull(sourceParent.getFieldDefinition(fieldName)) {
                        "Root-field-reference path has no field ${sourceParent.name}/$fieldName"
                    }
                val sourceOutput = sourceField.type.unwrapNonNull()
                require(sourceOutput !is GraphQLList && sourceOutput is GraphQLObjectType) {
                    "Root-field-reference path field ${sourceParent.name}/$fieldName " +
                        "must return a singular object"
                }
                val canonicalField = fieldFromSource(sourceParent.name, fieldName)
                require(canonicalField is ViaductSchema.ObjectField) {
                    "Root-field-reference path field ${sourceParent.name}/$fieldName " +
                        "does not lower to an object field"
                }
                if (index == rootFieldPath.lastIndex) {
                    require(sourceOutput.name == sourceTypeName) {
                        "Root-field-reference type $sourceTypeName does not match " +
                            "${sourceParent.name}/$fieldName type ${sourceOutput.name}"
                    }
                } else {
                    sourceParent = sourceOutput
                }
                canonicalField
            }
        return RootFieldReferenceData.of(canonicalPath, arguments)
    }

    private fun lowerNodeReferences(
        output: ResolverOutputData?,
        sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): ResolverOutputData? =
        when {
            output == null || output is EngineErrorData || output is RootFieldReferenceData -> output
            sourceTypeExpr.isList -> {
                require(output is List<*>) {
                    "Node-list field resolver did not return a list"
                }
                val sourceElementType = checkNotNull(sourceTypeExpr.unwrapList())
                output.map { value ->
                    lowerNodeReferences(
                        output = value,
                        sourceTypeExpr = sourceElementType,
                    )
                }
            }
            else -> {
                require(output is EngineObjectData.Sync) {
                    "Node field resolver did not return a node reference"
                }
                val outputType =
                    requireType(output.type.name) as? ViaductSchema.Object
                        ?: throw IllegalArgumentException(
                            "Node field resolver returned unknown object type ${output.type.name}",
                        )
                val idField = requireObjectField(outputType.name, "id")
                val id = output.get(idField.name)
                require(id !is EngineErrorData && id is String) {
                    "Node reference ${outputType.name}/id must contain a non-error ID"
                }
                val declaredType = sourceTypeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef
                require(outputType in declaredType.possibleObjectTypes) {
                    "Node reference ${outputType.name} is not valid for " +
                        sourceTypeExpr.baseTypeDef.name
                }
                nodeRootFieldReferenceOf(
                    queryNode = requireObjectField("Query", "node"),
                    type = outputType,
                    id = id,
                )
            }
        }

    private fun lowerOrdinaryOutput(
        output: ResolverOutputData?,
        sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        loweredTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): ResolverOutputData? =
        when {
            output == null || output is EngineErrorData || output is RootFieldReferenceData -> output
            sourceTypeExpr.isList && loweredTypeExpr.isList -> {
                require(output is List<*>) {
                    "Source output for $sourceTypeExpr is not a list"
                }
                val sourceElementType = checkNotNull(sourceTypeExpr.unwrapList())
                val loweredElementType = checkNotNull(loweredTypeExpr.unwrapList())
                output.map { value ->
                    lowerOrdinaryOutput(
                        output = value,
                        sourceTypeExpr = sourceElementType,
                        loweredTypeExpr = loweredElementType,
                    )
                }
            }
            !sourceTypeExpr.isList && !loweredTypeExpr.isList -> {
                val sourceType = sourceTypeExpr.baseTypeDef
                if (sourceType !is ViaductSchema.CompositeTypeDef) {
                    output
                } else {
                    require(output is EngineObjectData.Sync) {
                        "Source output for ${sourceType.name} is not an object"
                    }
                    lowerOrdinaryObject(output, loweredTypeExpr)
                }
            }
            else -> error("Source and lowered type expressions have different list shapes")
        }

    private fun lowerOrdinaryObject(
        output: EngineObjectData.Sync,
        loweredTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): EngineObjectData.Sync {
        output.qplanSchemaTypeOrNull?.let { return output }

        val outputType =
            requireType(output.type.name) as? ViaductSchema.Object
                ?: throw IllegalArgumentException(
                    "Source resolver returned unknown object type ${output.type.name}",
                )
        val declaredType = loweredTypeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef
        require(outputType in declaredType.possibleObjectTypes) {
            "Source object ${outputType.name} is not valid for ${declaredType.name}"
        }
        val sourceObject =
            graphQLSchema.getObjectType(outputType.name)
                ?: throw IllegalArgumentException(
                    "${outputType.name} is not a source GraphQL object type",
                )
        val fields =
            output.getSelections().map { selection ->
                requireNotNull(sourceObject.getFieldDefinition(selection)) {
                    "Source object ${outputType.name} has no field named $selection"
                }
                val loweredField = fieldFromSource(outputType.name, selection)
                require(loweredField is ViaductSchema.ObjectField) {
                    "${outputType.name}/$selection does not lower to an object field"
                }
                require(loweredField.args.isEmpty()) {
                    "Passive object field ${outputType.name}/$selection must be argumentless"
                }
                EngineObjectDataEntry.of(
                    selection = loweredField.name,
                    field = loweredField,
                    value = lowerSourceOutput(loweredField, output.get(selection)),
                )
            }
        return engineObjectDataOf(outputType, fields)
    }

    companion object {
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

        @JvmStatic
        fun fromSDL(schemaSDL: String): GJSchema {
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
            val typeRelations = GraphQLTypeRelations(graphQLSchema)
            val sourceSchema = graphQLSchema.viaductSchema()
            val builtLowered = lowerSchema(sourceSchema)
            val scalarsNeeded =
                SCALARS_REQUIRING_REGISTRATION.filterTo(mutableSetOf()) {
                    graphQLSchema.getType(it) != null
                }
            builtLowered.toGraphQLSchema(scalarsNeeded = scalarsNeeded)
            return GJSchema(
                graphQLSchema = graphQLSchema,
                typeRelations = typeRelations,
                loweredSchema = builtLowered,
            )
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
    }
}

private fun GraphQLOutputType.unwrapNonNull(): GraphQLOutputType =
    if (this is GraphQLNonNull) {
        wrappedType as GraphQLOutputType
    } else {
        this
    }
