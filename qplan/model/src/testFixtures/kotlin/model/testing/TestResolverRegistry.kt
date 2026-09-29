package model.testing

import model.Arguments
import model.EngineErrorData
import model.Fragment
import model.InclusionCondition
import model.NODE_REFERENCE_ID_PREFIX
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.Selection
import model.SelectionForest
import model.SourceSchemaAdapter
import model.decodeNodeReferenceId
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fieldExpressions
import model.inputType
import model.lowering.ALL_SOURCE_OBJECTS_TYPE
import model.lowering.LOWERED_TYPENAME_FIELD
import model.matchingVariableTypes
import model.merge
import model.registry.FieldCheckerResolver
import model.registry.FieldValueResolver
import model.registry.MissingResolverException
import model.registry.ProviderFragment
import model.registry.ResolutionExecutionContext
import model.registry.ResolverRegistry
import model.registry.ResolverTarget
import model.registry.TypeCheckerResolver
import model.registry.VariableDefinition
import model.registry.snipToDemand
import model.requireArg
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.schemaType
import model.selectionForestOf
import model.usedVariables
import model.variableTemplates
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelation

internal fun resolverRegistryOf(
    schema: GJSchema,
    nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
    fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
    fieldCheckers: Map<ViaductSchema.ObjectField, FieldCheckerResolver> = emptyMap(),
    typeCheckers: Map<ViaductSchema.Object, TypeCheckerResolver> = emptyMap(),
    variableProviders: Map<Arguments.Variable, VariableDeclaration>,
): ResolverRegistry {
    val lowering = NodeResolverLowering(schema, nodeResolvers, fieldResolvers)
    val variablesProviderTemplates =
        lowering.fieldResolvers.flatMap { (field, resolver) ->
            resolver.variablesProviderNames.map { name ->
                Arguments.Variable.of(field as ViaductSchema.ObjectField, name)
            }
        }
    val allVariableTemplates = variableProviders.keys + variablesProviderTemplates
    require(allVariableTemplates.size == variableProviders.size + variablesProviderTemplates.size) {
        "A variable cannot have both a recipe and a variables provider"
    }
    val variablesByField =
        allVariableTemplates
            .groupBy(Arguments.Variable::fieldValueResolverField)
            .mapValues { (_, variables) ->
                variables.associateBy(Arguments.Variable::variableName)
            }
    val registryResolvers =
        lowering.fieldResolvers.mapValues { (field, resolver) ->
            val variablesByName = variablesByField[field].orEmpty()
            resolver
                .mapObjectFragment { fragment ->
                    fragment.mapVariables { variable ->
                        variablesByName[variable.variableName]
                            ?: variable
                    }
                }.mapQueryFragment { fragment ->
                    fragment.mapVariables { variable ->
                        variablesByName[variable.variableName]
                            ?: variable
                    }
                }
        }
    val registryVariableProviders =
        variableProviders.mapValues { (variable, declaration) ->
            val variablesByName = variablesByField.getValue(variable.fieldValueResolverField)
            when (declaration) {
                is FromField ->
                    declaration.mapVariables { referenced ->
                        variablesByName[referenced.variableName] ?: referenced
                    }
                else -> declaration
            }
        }
    return TestResolverRegistry(
        schema = schema,
        fieldResolverDefinitions = registryResolvers,
        fieldCheckers = fieldCheckers,
        typeCheckers = typeCheckers,
        variableDeclarations = registryVariableProviders,
    )
}

/**
 * Lowers source-world node references and node lookups into the canonical field-only world.
 *
 * Node-valued source fields retain their coordinates and source-shaped node references become
 * root-field references to the built-in `Query.node`. That built-in decodes the concrete type and
 * original ID, then dispatches to the corresponding raw node lookup.
 *
 * A lowered field must be declared as `Node` or a subtype whose every possible concrete type has a
 * raw node lookup. Mixed node-resolved and inline possible types are rejected at this composition
 * boundary.
 */
private class NodeResolverLowering(
    private val schema: GJSchema,
    private val nodeResolvers: Map<ViaductSchema.Object, NodeResolverFunction>,
    rawFieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val nodeType: ViaductSchema.Interface? = canonicalNodeType()
    private val nodeFields: Set<ViaductSchema.ObjectField> = loweredNodeFields()

    val fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>

    init {
        validateRawFieldResolvers(rawFieldResolvers)

        val ordinaryResolvers =
            rawFieldResolvers.mapValues { (field, resolver) ->
                resolver.mapOutput { output -> sourceSchema.lowerOutput(field, output) }
            }.toMutableMap()
        nodeType?.let {
            val queryNode = schema.requireObjectField("Query", "node")
            ordinaryResolvers[queryNode] =
                nodeDispatchResolver(queryNode, ordinaryResolvers.getValue(queryNode))
        }
        val typenameResolvers =
            (schema.requireType(ALL_SOURCE_OBJECTS_TYPE) as ViaductSchema.Interface)
                .possibleObjectTypes
                .associate { type ->
                    val field = schema.requireObjectField(type.name, LOWERED_TYPENAME_FIELD)
                    field to
                        FieldResolverDefinition.of(
                            objectFragment = schema.emptyFragmentOf(type.name),
                            function = { _, _ -> type.name },
                        )
                }

        fieldResolvers = ordinaryResolvers + typenameResolvers
    }

    private fun canonicalNodeType(): ViaductSchema.Interface? {
        val candidate = schema.types["Node"]
        if (candidate == null && nodeResolvers.isEmpty()) return null
        return candidate as? ViaductSchema.Interface
            ?: throw IllegalArgumentException("Node resolvers require a canonical Node interface")
    }

    private fun loweredNodeFields(): Set<ViaductSchema.ObjectField> {
        nodeResolvers.forEach { (type, _) ->
            validateCanonicalType(type)
            require(
                schema.typeRelations.relationUnwrapped(
                    schema.sourceCompositeType(nodeType!!),
                    schema.sourceCompositeType(type),
                ) == GraphQLTypeRelation.WiderThan,
            ) {
                "Node-resolver type ${type.name} does not implement Node"
            }
            validateNodeIdField(type)
        }

        return schema.objectTypes
            .flatMap { it.fields }
            .mapNotNullTo(linkedSetOf()) { field ->
                if (!schema.isLoweredNodeField(field)) return@mapNotNullTo null
                if (field.containingDef.name == "Query" && field.name == "node") {
                    return@mapNotNullTo null
                }
                val outputType =
                    sourceSchema.typeExpr(field).baseTypeDef as ViaductSchema.CompositeTypeDef
                val registeredTypes = outputType.possibleObjectTypes.filterTo(linkedSetOf()) {
                    it in nodeResolvers
                }
                val isDeclaredNode =
                    nodeType != null &&
                        schema.typeRelations.relationUnwrapped(
                            schema.sourceCompositeType(nodeType),
                            schema.sourceCompositeType(outputType),
                        ) in
                        setOf(
                            GraphQLTypeRelation.Same,
                            GraphQLTypeRelation.WiderThan,
                        )
                require(isDeclaredNode) {
                    "Field ${field.containingDef.name}/${field.name} is not a Node-valued source field"
                }
                require(
                    registeredTypes.isEmpty() ||
                        registeredTypes == outputType.possibleObjectTypes,
                ) {
                    "Field ${field.containingDef.name}/${field.name} mixes node-resolved " +
                        "and inline object values; declare a Node output whose every possible type " +
                        "has a node resolver"
                }
                field
            }
    }

    private fun validateRawFieldResolvers(fieldResolvers: Map<ViaductSchema.Field, FieldResolverDefinition>) {
        val nodeIdFields = nodeResolvers.keys.mapTo(linkedSetOf(), ::validateNodeIdField)
        fieldResolvers.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingDef.name
            require(field.containingDef is ViaductSchema.Object) {
                "Field resolver $typeName/${field.name} must belong to a concrete object type"
            }
            require(field !in nodeIdFields) {
                "Node id field $typeName/${field.name} cannot have a field resolver"
            }
            require(field.name != LOWERED_TYPENAME_FIELD) {
                "Generated field $typeName/$LOWERED_TYPENAME_FIELD cannot be supplied directly"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.requireType(fragmentType.name) == fragmentType) {
                "${fragmentType.name} is not canonical in this registry's schema"
            }
            require(fragmentType == field.containingDef) {
                "Object fragment type ${fragmentType.name} does not match " +
                    "$typeName/${field.name}"
            }
        }
    }

    private fun nodeDispatchResolver(
        queryNode: ViaductSchema.ObjectField,
        rawQueryNode: FieldResolverDefinition,
    ): FieldResolverDefinition {
        require(rawQueryNode.objectFragment.materializeSelections.isEmpty()) {
            "Built-in Query/node cannot declare an object fragment"
        }
        return rawQueryNode.withNodeDispatch(
            isNodeDispatch = { arguments ->
                (arguments.fieldValues["id"] as? String)
                    ?.startsWith(NODE_REFERENCE_ID_PREFIX) == true
            },
            dispatch = { arguments, selections, executionContext ->
                val encodedId = arguments.fieldValues["id"] as? String
                    ?: throw IllegalArgumentException("Query.node id is not an ID")
                val identity =
                    decodeNodeReferenceId(queryNode, encodedId)
                        ?: throw IllegalArgumentException("Malformed encoded node reference")
                loadNode(identity, selections, executionContext)
            },
        )
    }

    private suspend fun loadNode(
        identity: NodeReferenceIdentity,
        selections: SelectionForest,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? {
        val (type, id) = identity
        val resolver =
            nodeResolvers[type]
                ?: throw IllegalArgumentException("No fixture node resolver for ${type.name}")
        val idField = validateNodeIdField(type)
        val nodeOwnedDemand =
            selections.filter { selection -> selection.key.field.name != idField.name }
        if (
            nodeOwnedDemand.isEmpty() &&
            resolver.mode == NodeResolverFunction.Mode.SELECTIVE
        ) {
            val includeId =
                selections.merge(type).byKey().keys.any { key -> key.field == idField }
            return engineObjectDataOf(
                type,
                if (includeId) mapOf(idField.name to id) else emptyMap(),
            )
        }
        val resolverDemand =
            if (resolver.mode == NodeResolverFunction.Mode.NONSELECTIVE) {
                selectionForestOf()
            } else {
                nodeOwnedDemand
            }
        val sourceResult = resolver(id, resolverDemand, executionContext)
        if (sourceResult == null || sourceResult is EngineErrorData) return sourceResult
        if (sourceResult is RootFieldReferenceData) return sourceResult
        require(sourceResult is EngineObjectData.Sync) {
            "Node resolver for ${type.name} returned a non-object value"
        }
        val result = sourceSchema.lowerNodeResolverOutput(type, sourceResult)
            as EngineObjectData.Sync
        val resultType = result.schemaType
        require(resultType == type) {
            "Node resolver for ${type.name} returned ${resultType.name}"
        }
        val fields =
            result.getSelections().associateWith { selection ->
                result.get(selection)
            }

        /*
         * Node identity belongs to the reference rather than the materialized node-resolver object.
         * Reconstitute the effective object with that reference ID authoritative.
         */
        val includeId =
            resolver.mode != NodeResolverFunction.Mode.SELECTIVE ||
                selections.merge(type).byKey().keys.any { key -> key.field == idField }
        val authoritativeResult = engineObjectDataOf(
            resultType,
            if (includeId) fields + (idField.name to id) else fields - idField.name,
        )
        return if (resolver.mode == NodeResolverFunction.Mode.SELECTIVE) {
            authoritativeResult
        } else {
            authoritativeResult.snipToDemand(selections)
        }
    }

    private fun validateNodeIdField(type: ViaductSchema.Object): ViaductSchema.ObjectField {
        val idField =
            type.field("id")
                ?: throw IllegalArgumentException(
                    "Node-resolver type ${type.name} has no id field",
                )
        require(schema.requireField(type.name, "id") == idField) {
            "${type.name}/id is not canonical in this registry's schema"
        }
        require(idField.args.isEmpty()) {
            "Node id field ${type.name}/id must take no arguments"
        }
        require(
            (idField.type.baseTypeDef as? ViaductSchema.Scalar)?.name == "ID",
        ) {
            "Node id field ${type.name}/id must be ID-typed"
        }
        return idField
    }

    private fun validateCanonicalType(type: ViaductSchema.Object) {
        require(schema.requireType(type.name) == type) {
            "${type.name} is not canonical in this registry's schema"
        }
    }

    private fun validateCanonicalField(
        field: ViaductSchema.Field,
        role: String = "field",
    ) {
        val typeName = field.containingDef.name
        require(schema.requireField(typeName, field.name) == field) {
            "$typeName/${field.name} is not the canonical $role in this registry's schema"
        }
    }
}

private sealed interface DependencyVertex {
    data class Field(
        val field: ViaductSchema.ObjectField,
    ) : DependencyVertex

    data class Variable(
        val variable: Arguments.Variable,
    ) : DependencyVertex
}

private class TestResolverRegistry(
    private val schema: ViaductSchema,
    fieldResolverDefinitions: Map<ViaductSchema.Field, FieldResolverDefinition>,
    private val fieldCheckers: Map<ViaductSchema.ObjectField, FieldCheckerResolver>,
    private val typeCheckers: Map<ViaductSchema.Object, TypeCheckerResolver>,
    variableDeclarations: Map<Arguments.Variable, VariableDeclaration>,
) : ResolverRegistry {
    private val sourceFieldResolvers = fieldResolverDefinitions
    private val fieldResolvers: Map<ViaductSchema.Field, FieldValueResolver>
    private val variablesProviderTemplates =
        fieldResolverDefinitions.flatMap { (field, resolver) ->
            resolver.variablesProviderNames.map { name ->
                Arguments.Variable.of(field as ViaductSchema.ObjectField, name)
            }
        }
    private val variableDefinitions =
        variableDeclarations.mapValues { (_, declaration) ->
            when (declaration) {
                is FromArgument ->
                    VariableDefinition.FromArgument.of(
                        argument = declaration.argument,
                        inputPath = declaration.inputPath,
                    )
                is FromField ->
                    VariableDefinition.FromField.of(
                        providerFragment = declaration.providerFragment,
                        path = declaration.keyPath,
                        responsePath = declaration.responsePath,
                    )
            }
        } + variablesProviderTemplates.associateWith { VariableDefinition.FromProvider }
    private val outgoing: Map<DependencyVertex, Set<DependencyVertex>>

    init {
        fieldCheckers.forEach { (field, checker) ->
            validateCanonicalField(field, "field-checker field")
            require(checker.target.field == field) {
                "Field checker ${checker.target.field.containingDef.name}/${checker.target.field.name} does not belong to " +
                    "${field.containingDef.name}/${field.name}"
            }
        }
        typeCheckers.forEach { (type, checker) ->
            require(schema.requireType(type.name) == type) {
                "${type.name} is not the canonical type-checker type in this registry's schema"
            }
            require(checker.target.type == type) {
                "Type checker ${checker.target.type.name} does not belong to ${type.name}"
            }
        }
        fieldResolverDefinitions.forEach { (field, resolver) ->
            validateCanonicalField(field, "field-resolver field")
            val typeName = field.containingDef.name
            require(field.containingDef is ViaductSchema.Object) {
                "Field resolver $typeName/${field.name} must belong to a concrete object type"
            }
            val fragmentType = resolver.objectFragment.nominalType
            require(schema.requireType(fragmentType.name) == fragmentType) {
                "${fragmentType.name} is not canonical in this registry's schema"
            }
            require(fragmentType == field.containingDef) {
                "Object fragment type ${fragmentType.name} does not match " +
                    "$typeName/${field.name}"
            }
            resolver.queryFragment?.let { queryFragment ->
                require(queryFragment.nominalType == schema.requireQueryTypeDef()) {
                    "Query fragment type ${queryFragment.nominalType.name} does not match Query"
                }
            }
        }
        val missingQueryFields =
            schema.requireQueryTypeDef().fields
                .filter { it !in fieldResolverDefinitions }
        require(missingQueryFields.isEmpty()) {
            "Query fields without field resolvers: " +
                missingQueryFields.map { it.name }.sorted().joinToString()
        }

        variableDeclarations.forEach { (variable, declaration) ->
            validateCanonicalField(variable.fieldValueResolverField, "variable-defining field")
            require(variable.fieldValueResolverField in fieldResolverDefinitions) {
                "Variable ${variable.variableName} belongs to an unregistered resolver"
            }
            when (declaration) {
                is FromArgument -> {
                    val resolver = fieldResolverDefinitions.getValue(variable.fieldValueResolverField)
                    require(declaration.argument.containingDef == variable.fieldValueResolverField) {
                        "Variable ${variable.variableName} argument " +
                            "${declaration.argument.name} does not belong to " +
                            "${variable.fieldValueResolverField.containingDef.name}/${variable.fieldValueResolverField.name}"
                    }
                    validateVariableUses(
                        variable = variable,
                        fragments = listOfNotNull(resolver.objectFragment, resolver.queryFragment),
                        sourceDescription =
                            "argument path " +
                                (
                                    listOf(declaration.argument.name) +
                                        declaration.inputPath.map(ViaductSchema.Field::name)
                                )
                                    .joinToString("."),
                        isCompatible = declaration::isCompatibleWith,
                        isCompatibleWithInclusionCondition =
                            declaration::isCompatibleWithInclusionCondition,
                    )
                }
                is FromField -> {
                    val resolver = fieldResolverDefinitions.getValue(variable.fieldValueResolverField)
                    val expectedType =
                        when (declaration.providerFragment) {
                            ProviderFragment.OBJECT -> variable.fieldValueResolverField.containingDef
                            ProviderFragment.QUERY -> schema.requireQueryTypeDef()
                        }
                    require(declaration.fragment.nominalType == expectedType) {
                        "Variable ${variable.variableName} declaration is not relative to " +
                            expectedType.name
                    }
                    val providerFragment =
                        when (declaration.providerFragment) {
                            ProviderFragment.OBJECT -> resolver.objectFragment
                            ProviderFragment.QUERY ->
                                requireNotNull(resolver.queryFragment) {
                                    "Variable ${variable.variableName} requires a Query fragment"
                                }
                        }
                    validateProviderContainment(
                        field = variable.fieldValueResolverField,
                        fragment = providerFragment,
                        providerFragment = declaration.providerFragment,
                    )
                    validateVariableUses(
                        variable = variable,
                        fragments = listOfNotNull(resolver.objectFragment, resolver.queryFragment),
                        sourceDescription =
                            "${declaration.providerFragment.name.lowercase()} provider path " +
                                declaration.responsePath.joinToString("."),
                        isCompatible = declaration::isCompatibleWith,
                        isCompatibleWithInclusionCondition =
                            declaration::isCompatibleWithInclusionCondition,
                    )
                }
            }
        }
        variablesProviderTemplates.forEach { variable ->
            validateCanonicalField(variable.fieldValueResolverField, "variables-provider field")
            val resolver = fieldResolverDefinitions.getValue(variable.fieldValueResolverField)
            val usedVariables =
                listOfNotNull(resolver.objectFragment, resolver.queryFragment)
                    .flatMap { fragment -> fragment.subselections.usedVariables() }
                    .toSet()
            require(variable in usedVariables) {
                "Variables provider declares unused variable ${variable.variableName} for " +
                    "${variable.fieldValueResolverField.containingDef.name}/${variable.fieldValueResolverField.name}"
            }
        }

        val objectFieldResolvers =
            fieldResolverDefinitions.mapKeys { (field, _) -> field as ViaductSchema.ObjectField }
        outgoing =
            buildMap {
                objectFieldResolvers.forEach { (field, resolver) ->
                    put(
                        DependencyVertex.Field(field),
                        implicatedVertices(resolver.objectFragment, field) +
                            implicatedVertices(resolver.queryFragment, field),
                    )
                }
                variableDefinitions.forEach { (variable, definition) ->
                    put(
                        DependencyVertex.Variable(variable),
                        when (definition) {
                            VariableDefinition.FromProvider -> emptySet<DependencyVertex>()
                            is VariableDefinition.FromArgument -> emptySet<DependencyVertex>()
                            is VariableDefinition.FromField -> {
                                val resolver = fieldResolverDefinitions.getValue(variable.fieldValueResolverField)
                                val providerFragment = when (definition.providerFragment) {
                                    ProviderFragment.OBJECT -> resolver.objectFragment
                                    ProviderFragment.QUERY -> requireNotNull(resolver.queryFragment)
                                }
                                val conditions = definition.inclusionConditions(providerFragment.materializeSelections)
                                val providerType =
                                    when (definition.providerFragment) {
                                        ProviderFragment.OBJECT -> variable.fieldValueResolverField.containingDef
                                        ProviderFragment.QUERY -> schema.requireQueryTypeDef()
                                    }
                                implicatedVertices(
                                    Fragment.of(
                                        providerType,
                                        selectionForestOf(
                                            definition.path.toSelection(
                                                setOf(providerType),
                                                conditions,
                                            ),
                                        ),
                                    ),
                                    variable.fieldValueResolverField,
                                )
                            }
                        },
                    )
                }
            }
        val assembledResolvers = mutableMapOf<ViaductSchema.Field, FieldValueResolver>()
        dependencyOrder(outgoing).forEach { site ->
            when (site) {
                is DependencyVertex.Field -> {
                    val definition = fieldResolverDefinitions.getValue(site.field)
                    assembledResolvers[site.field] =
                        definition.assemble(
                            queryType = schema.requireQueryTypeDef(),
                            variables =
                                variableDefinitions.filterKeys { variable ->
                                    variable.fieldValueResolverField == site.field
                                },
                            validateObjectFragment = { fragment ->
                                validateProviderContainment(
                                    field = site.field,
                                    fragment = fragment,
                                    providerFragment = ProviderFragment.OBJECT,
                                )
                            },
                            field = site.field,
                        )
                }
                is DependencyVertex.Variable -> Unit
            }
        }
        this.fieldResolvers = assembledResolvers
        BranchOrderValidator(
            fieldResolvers = assembledResolvers,
        ).validate()
    }

    override fun contains(field: ViaductSchema.ObjectField): Boolean {
        validateCanonicalField(field)
        return field in fieldResolvers
    }

    override fun createRootQueryInput(): EngineObjectData.Sync {
        val query = schema.requireQueryTypeDef()
        return engineObjectDataOf(schemaType = query)
    }

    override fun resolver(field: ViaductSchema.ObjectField): FieldValueResolver {
        validateCanonicalField(field)
        return fieldResolvers[field]
            ?: throw MissingResolverException(field.containingDef.name, field.name)
    }

    override fun fieldChecker(field: ViaductSchema.ObjectField): FieldCheckerResolver? {
        validateCanonicalField(field)
        return fieldCheckers[field]
    }

    override fun typeChecker(type: ViaductSchema.Object): TypeCheckerResolver? {
        require(schema.requireType(type.name) == type) {
            "${type.name} is not canonical in this registry's schema"
        }
        return typeCheckers[type]
    }

    override fun mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField> {
        require(field in this) { "Resolver field is not registered" }
        return outgoing
            .getValue(DependencyVertex.Field(field))
            .mapNotNullTo(linkedSetOf()) { vertex ->
                (vertex as? DependencyVertex.Field)?.field
            }
    }

    private fun validateProviderContainment(
        field: ViaductSchema.ObjectField,
        fragment: Fragment,
        providerFragment: ProviderFragment,
    ) {
        variableDefinitions.forEach { (variable, definition) ->
            if (variable.fieldValueResolverField != field) return@forEach
            if (
                definition !is VariableDefinition.FromField ||
                definition.providerFragment != providerFragment
            ) {
                return@forEach
            }
            require(fragment.subselections.containsProviderPath(definition.path)) {
                "Variable ${variable.variableName} provider path is not contained by " +
                    "${field.containingDef.name}/${field.name} " +
                    "${providerFragment.name.lowercase()} fragment"
            }
        }
    }

    private fun validateVariableUses(
        variable: Arguments.Variable,
        fragments: List<Fragment>,
        sourceDescription: String,
        isCompatible: (
            ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
            Boolean,
        ) -> Boolean,
        isCompatibleWithInclusionCondition: () -> Boolean,
    ) {
        fragments
            .flatMap { fragment -> fragment.subselections.variableUses(variable) }
            .forEach { use ->
                require(
                    when (use) {
                        is VariableUse.Argument ->
                            isCompatible(
                                use.typeExpr,
                                use.hasDefault,
                            )
                        VariableUse.InclusionCondition ->
                            isCompatibleWithInclusionCondition()
                    },
                ) {
                    "Variable ${variable.variableName} $sourceDescription is incompatible " +
                        when (use) {
                            is VariableUse.Argument -> "with one of its argument locations"
                            VariableUse.InclusionCondition ->
                                "with an inclusion-condition location"
                        }
                }
            }
    }

    private fun SelectionForest.containsProviderPath(providerPath: List<ObjectEngineResult.Key>): Boolean {
        val provider = providerPath.first()
        val remaining = providerPath.drop(1)
        return toSelectionList().any { selection ->
            selection.key == provider &&
                (
                    remaining.isEmpty() ||
                        selection.subselections.containsProviderPath(remaining)
                )
        }
    }

    private fun SelectionForest.toSelectionList(): List<Selection> =
        buildList {
            this@toSelectionList.forEach(::add)
        }

    private sealed interface VariableUse {
        data class Argument(
            val typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
            val hasDefault: Boolean,
        ) : VariableUse

        data object InclusionCondition : VariableUse
    }

    private fun SelectionForest.variableUses(variable: Arguments.Variable): List<VariableUse> =
        buildList {
            this@variableUses.forEach { selection ->
                if (selection.key.arguments != Arguments.Error) {
                    selection.key.arguments.fieldExpressions().forEach { (name, value) ->
                        val argument = selection.key.field.requireArg(name)
                        addAll(
                            value
                                .matchingVariableTypes(
                                    variable = variable,
                                    typeExpr = argument.inputType,
                                    hasDefault = argument.hasDefault,
                                ).map { (typeExpr, hasDefault) ->
                                    VariableUse.Argument(typeExpr, hasDefault)
                                },
                        )
                    }
                }
                if (variable in selection.inclusionCondition.usedVariables()) {
                    add(VariableUse.InclusionCondition)
                }
                addAll(selection.subselections.variableUses(variable))
            }
        }

    private fun validateCanonicalField(
        field: ViaductSchema.Field,
        role: String = "field",
    ) {
        val typeName = field.containingDef.name
        require(schema.requireField(typeName, field.name) == field) {
            "$typeName/${field.name} is not the canonical $role in this registry's schema"
        }
    }

    private fun List<ObjectEngineResult.Key>.toSelection(
        possibleTypes: Set<ViaductSchema.Object>,
        conditions: List<InclusionCondition>,
    ): Selection {
        val key = first()
        val remaining = drop(1)
        val outputType = key.field.type.baseTypeDef
        return Selection.of(
            key = key,
            possibleTypes = possibleTypes,
            inclusionCondition = conditions.first(),
            subselections =
                if (remaining.isEmpty()) {
                    selectionForestOf()
                } else {
                    require(outputType is ViaductSchema.CompositeTypeDef)
                    selectionForestOf(remaining.toSelection(outputType.possibleObjectTypes, conditions.drop(1)))
                },
        )
    }

    private fun implicatedVertices(
        fragment: Fragment?,
        ownerField: ViaductSchema.ObjectField,
    ): Set<DependencyVertex> {
        if (fragment == null) return emptySet()
        val result = mutableSetOf<DependencyVertex>()
        fragment.subselections.forEach { selection ->
            result.addImplicatedBy(selection, ownerField)
        }
        return result
    }

    private fun MutableSet<DependencyVertex>.addImplicatedBy(
        selection: Selection,
        ownerField: ViaductSchema.ObjectField,
    ) {
        if (selection.inclusionCondition === InclusionCondition.Never) return
        selection.possibleTypes.forEach { possibleType ->
            possibleType.field(selection.key.field.name)
                ?.takeIf { it in sourceFieldResolvers }
                ?.let { add(DependencyVertex.Field(it)) }
        }
        (
            selection.key.arguments.variableTemplates() +
                selection.inclusionCondition.usedVariables()
        ).forEach { variable ->
            require(variable in variableDefinitions) {
                "Missing variable definition: \$${variable.variableName}"
            }
            require(variable.fieldValueResolverField == ownerField) {
                "Variable \$${variable.variableName} is not defined by " +
                    "${ownerField.containingDef.name}/${ownerField.name}"
            }
            add(DependencyVertex.Variable(variable))
        }
        selection.subselections.forEach { subselection ->
            addImplicatedBy(subselection, ownerField)
        }
    }

    private fun dependencyOrder(
        outgoing: Map<DependencyVertex, Set<DependencyVertex>>,
        remaining: Set<DependencyVertex> = outgoing.keys,
        ordered: List<DependencyVertex> = emptyList(),
    ): List<DependencyVertex> {
        if (remaining.isEmpty()) return ordered

        val ready =
            remaining.filterTo(linkedSetOf()) { site ->
                outgoing.getValue(site).none { dependency -> dependency in remaining }
            }
        require(ready.isNotEmpty()) {
            "Resolver object fragments contain a demand cycle"
        }
        return dependencyOrder(
            outgoing = outgoing,
            remaining = remaining - ready,
            ordered = ordered + ready,
        )
    }
}

private val Arguments.Variable.fieldValueResolverField: ViaductSchema.ObjectField
    get() = (target as ResolverTarget.FieldValueResolverTarget).field
