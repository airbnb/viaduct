package viaduct.graphql.utils

import graphql.language.Argument
import graphql.language.ArrayValue
import graphql.language.Description
import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.DirectiveLocation
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.language.InputValueDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.OperationDefinition
import graphql.language.OperationTypeDefinition
import graphql.language.SDLDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.SourceLocation
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.parser.MultiSourceReader
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.io.FileReader
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import viaduct.graphql.Scalars
import viaduct.utils.slf4j.logger

/**
 * Provides default schema components (directives and root types) for Viaduct schemas,
 * adding core Viaduct directives and intelligently creating root types only when needed.
 */
object DefaultSchemaProvider {
    const val SDL_FILENAME: String = "@@viaduct_default_schema.graphqls"

    private val log by logger()
    private val sourceLocation = SourceLocation(-1, -1, SDL_FILENAME)

    /**
     * Enum containing all default Viaduct directives with their complete definitions.
     * This provides a single source of truth for directive names and their creation logic.
     */
    enum class DefaultDirective(
        val directiveName: String
    ) {
        RESOLVER("resolver") {
            override fun createDefinition(sourceLocation: SourceLocation): DirectiveDefinition {
                val description = Description("@resolver directive", sourceLocation, false)

                return DirectiveDefinition
                    .newDirectiveDefinition()
                    .name(directiveName)
                    .description(description)
                    .directiveLocation(DirectiveLocation("FIELD_DEFINITION"))
                    .directiveLocation(DirectiveLocation("OBJECT"))
                    .sourceLocation(sourceLocation)
                    .build()
            }
        },

        BACKING_DATA("backingData") {
            override fun createDefinition(sourceLocation: SourceLocation): DirectiveDefinition {
                val description = Description("@backingData directive", sourceLocation, false)

                val classArgument = InputValueDefinition
                    .newInputValueDefinition()
                    .name("class")
                    .type(NonNullType(TypeName("String")))
                    .sourceLocation(sourceLocation)
                    .build()

                return DirectiveDefinition
                    .newDirectiveDefinition()
                    .name(directiveName)
                    .description(description)
                    .directiveLocation(DirectiveLocation("FIELD_DEFINITION"))
                    .inputValueDefinition(classArgument)
                    .sourceLocation(sourceLocation)
                    .build()
            }
        },

        SCOPE("scope") {
            override fun createDefinition(sourceLocation: SourceLocation): DirectiveDefinition {
                val description = Description("@scope directive", sourceLocation, false)

                val toArgument = InputValueDefinition
                    .newInputValueDefinition()
                    .name("to")
                    .type(NonNullType(ListType(NonNullType(TypeName("String")))))
                    .sourceLocation(sourceLocation)
                    .build()

                return DirectiveDefinition
                    .newDirectiveDefinition()
                    .name(directiveName)
                    .description(description)
                    .directiveLocation(DirectiveLocation("OBJECT"))
                    .directiveLocation(DirectiveLocation("INPUT_OBJECT"))
                    .directiveLocation(DirectiveLocation("ENUM"))
                    .directiveLocation(DirectiveLocation("INTERFACE"))
                    .directiveLocation(DirectiveLocation("UNION"))
                    .inputValueDefinition(toArgument)
                    .repeatable(true)
                    .sourceLocation(sourceLocation)
                    .build()
            }
        },

        ID_OF("idOf") {
            override fun createDefinition(sourceLocation: SourceLocation): DirectiveDefinition {
                val description = Description("@idOf directive", sourceLocation, false)

                val typeArgument = InputValueDefinition
                    .newInputValueDefinition()
                    .name("type")
                    .type(NonNullType(TypeName("String")))
                    .sourceLocation(sourceLocation)
                    .build()

                return DirectiveDefinition
                    .newDirectiveDefinition()
                    .name(directiveName)
                    .description(description)
                    .directiveLocation(DirectiveLocation("FIELD_DEFINITION"))
                    .directiveLocation(DirectiveLocation("INPUT_FIELD_DEFINITION"))
                    .directiveLocation(DirectiveLocation("ARGUMENT_DEFINITION"))
                    .inputValueDefinition(typeArgument)
                    .sourceLocation(sourceLocation)
                    .build()
            }
        };

        /**
         * Creates the DirectiveDefinition for this directive.
         */
        abstract fun createDefinition(sourceLocation: SourceLocation): DirectiveDefinition
    }

    /**
     * Enum to control if parts of the Node schmea (ie the Node type definition or
     * the Query.node/nodes fields) should be included in the default schema.
     */
    enum class IncludeNodeSchema {
        /** Always include */
        Always,

        /** Never include */
        Never,

        /** Automatically include if Node or one of its subtypes is referenced */
        IfUsed;

        companion object {
            operator fun invoke(include: Boolean): IncludeNodeSchema = if (include) Always else Never
        }
    }

    /**
     * Generates the complete default schema in SDL format as a String.
     * This includes all default directives, the Node interface, standard scalars,
     * Node query fields, and root types (Query, Mutation, Subscription) if they have extensions.
     *
     * @param includeNodeDef whether to include the Node definition fields (node/nodes) -
     *   defaults to [IncludeNodeSchema.IfUsed]
     * @param includeNodeQueries whether to include standard Node query fields (node/nodes) -
     *   defaults to [IncludeNodeSchema.IfUsed]
     * @param existingSDLFiles the schema fragments for which the default SDL will be generated
     * @return the complete default schema in SDL format
     */
    fun getDefaultSDL(
        includeNodeDefinition: IncludeNodeSchema = IncludeNodeSchema.IfUsed,
        includeNodeQueries: IncludeNodeSchema = IncludeNodeSchema.IfUsed,
        existingSDLFiles: List<File> = emptyList()
    ): String {
        val extantRegistry = if (existingSDLFiles.isNotEmpty()) {
            val reader = MultiSourceReader.newMultiSourceReader().let { readerBuilder ->
                for (f in existingSDLFiles) {
                    readerBuilder.reader(FileReader(f), f.path)
                }
                readerBuilder.build()
            }
            SchemaParser().parse(reader)
        } else {
            TypeDefinitionRegistry()
        }

        val builder = RegistryBuilder(TypeDefinitionRegistry(), extantRegistry)

        // Generate all default schema components
        addDefaults(
            builder = builder,
            includeNodeDefinition = includeNodeDefinition,
            includeNodeQueries = includeNodeQueries,
            forceAddRootTypes = true,
            allowExisting = false
        )
        return builder.building.toSDL()
    }

    /**
     * Adds all default schema components (directives and root types) to the provided
     * TypeDefinitionRegistry.
     *
     * @param registry the TypeDefinitionRegistry to enhance with default schema components
     * @param includeNodeDefinition whether to include standard Node query fields (node/nodes) -
     *   defaults to [IncludeNodeSchema.IfUsed]
     * @param includeNodeQueries whether to include standard Node query fields (node/nodes) -
     *   defaults to [IncludeNodeSchema.IfUsed]
     * @param forceAddRootTypes whether to force adding root types even without extensions
     * @param allowExisting whether to allow existing definitions without throwing errors
     */
    fun addDefaults(
        registry: TypeDefinitionRegistry,
        includeNodeDefinition: IncludeNodeSchema = IncludeNodeSchema.IfUsed,
        includeNodeQueries: IncludeNodeSchema = IncludeNodeSchema.IfUsed,
        forceAddRootTypes: Boolean = false,
        allowExisting: Boolean = false,
    ) {
        addDefaults(
            builder = RegistryBuilder(registry, TypeDefinitionRegistry()),
            includeNodeDefinition = includeNodeDefinition,
            includeNodeQueries = includeNodeQueries,
            forceAddRootTypes = forceAddRootTypes,
            allowExisting = allowExisting,
        )
    }

    private fun addDefaults(
        builder: RegistryBuilder,
        includeNodeDefinition: IncludeNodeSchema,
        includeNodeQueries: IncludeNodeSchema,
        forceAddRootTypes: Boolean,
        allowExisting: Boolean
    ): RegistryBuilder {
        addDefaultDirectives(builder, allowExisting)
        addStandardScalars(builder, allowExisting)

        val hasNodeTypeReference by lazy {
            includeNodeDefinition == IncludeNodeSchema.Always ||
                includeNodeQueries == IncludeNodeSchema.Always ||
                hasNodeTypeReference(builder)
        }

        // Conditionally add Query.node/s fields
        when (includeNodeQueries) {
            IncludeNodeSchema.Always -> addNodeQueryFields(builder, allowExisting)
            IncludeNodeSchema.Never -> {}
            IncludeNodeSchema.IfUsed -> {
                if (hasNodeTypeReference) {
                    addNodeQueryFields(builder, allowExisting)
                }
            }
        }

        // Conditionally add Node definition
        when (includeNodeDefinition) {
            IncludeNodeSchema.Always -> addNodeInterface(builder, allowExisting)
            IncludeNodeSchema.Never -> {}
            IncludeNodeSchema.IfUsed -> {
                if (hasNodeTypeReference) {
                    addNodeInterface(builder, allowExisting)
                }
            }
        }

        addRootTypes(builder, forceAddRootTypes, allowExisting)
        return builder
    }

    /**
     * Returns the set of default Viaduct scalars that are scalars provided in addition to standard GraphQL scalars.
     * This includes all scalars from Scalars.viaductStandardScalars such as Date, DateTime,
     * Long, BackingData, and others.
     *
     * @return the set of default Viaduct scalars
     */
    fun defaultScalars(): Set<GraphQLScalarType> = Scalars.viaductStandardScalars

    /**
     * Adds default Viaduct directives to the schema if they don't already exist.
     * This includes @resolver, @backingData, @scope, and @idOf directives.
     *
     * @param registry the TypeDefinitionRegistry to enhance with default directives
     * @param allowExisting whether to allow existing definitions without throwing errors
     */
    private fun addDefaultDirectives(
        builder: RegistryBuilder,
        allowExisting: Boolean = false
    ) {
        DefaultDirective.values().forEach { directive ->
            val existingDef = builder.getDirectiveDefinition(directive.directiveName)
            if (existingDef != null) {
                val locationInfo = " (currently defined @ ${existingDef.sourceLocation}."
                maybeThrow(
                    allowExisting,
                    "Core Viaduct directive @${directive.directiveName} cannot be redefined in user schemas$locationInfo " +
                        "This directive is automatically provided by the framework."
                )
                return@forEach
            }

            val definition = directive.createDefinition(sourceLocation)
            builder.add(definition)
            log.debug("Added default @{} directive", directive.directiveName)
        }
    }

    /**
     * Adds the Node interface to the schema if it doesn't already exist.
     * This provides the standard GraphQL Node interface pattern for entities
     * that can be uniquely identified and refetched.
     *
     * @param registry the TypeDefinitionRegistry to enhance with the Node interface
     * @param allowExisting whether to allow existing definitions without throwing errors
     */
    private fun addNodeInterface(
        builder: RegistryBuilder,
        allowExisting: Boolean = false
    ) {
        if (builder.getType("Node").isPresent) {
            maybeThrow(
                allowExisting,
                "Node interface cannot be redefined in user schemas. " +
                    "This interface is automatically provided by the framework."
            )
            return
        }

        val idField = FieldDefinition
            .newFieldDefinition()
            .name("id")
            .type(NonNullType(TypeName("ID")))
            .sourceLocation(sourceLocation)
            .build()

        val scopeDirective = createScopeDirective(listOf("*"))

        val nodeInterface = InterfaceTypeDefinition
            .newInterfaceTypeDefinition()
            .name("Node")
            .definition(idField)
            .directive(scopeDirective)
            .sourceLocation(sourceLocation)
            .build()

        builder.add(nodeInterface)
        log.debug("Added default Node interface")
    }

    /**
     * Adds standard Node query fields (node and nodes) to Query type extension.
     * This provides the standard GraphQL Node interface query pattern for fetching
     * objects by their global IDs.
     *
     * @param registry the TypeDefinitionRegistry to enhance with Node query fields
     * @param allowExisting whether to allow existing definitions without throwing errors
     */
    private fun addNodeQueryFields(
        builder: RegistryBuilder,
        allowExisting: Boolean = false
    ) {
        // Check if Query type extension with node/nodes fields already exists
        @Suppress("UNCHECKED_CAST")
        val existingQueryDefinitions = (builder.objectTypeExtensions["Query"] ?: emptyList()) +
            (builder.getType("Query").map { listOf(it) }.orElse(emptyList()) as List<ObjectTypeDefinition>)
        val hasEitherNodeField = existingQueryDefinitions.any { ext ->
            ext.fieldDefinitions.any { field -> field.name == "node" || field.name == "nodes" }
        }

        if (hasEitherNodeField) {
            maybeThrow(
                allowExisting,
                "Node query fields (node/nodes) cannot be redefined in user schemas. " +
                    "This extension is automatically provided by the framework."
            )
            return
        }

        // Create node field: node(id: ID!): Node
        val nodeIdArgument = InputValueDefinition
            .newInputValueDefinition()
            .name("id")
            .description(Description("The ID of an object", sourceLocation, false))
            .type(NonNullType(TypeName("ID")))
            .sourceLocation(sourceLocation)
            .build()

        val nodeField = FieldDefinition
            .newFieldDefinition()
            .name("node")
            .description(Description("Fetches an object given its ID", sourceLocation, false))
            .type(TypeName("Node"))
            .directive(createResolverDirective())
            .inputValueDefinition(nodeIdArgument)
            .sourceLocation(sourceLocation)
            .build()

        // Create nodes field: nodes(ids: [ID!]!): [Node]!
        val nodesIdsArgument = InputValueDefinition
            .newInputValueDefinition()
            .name("ids")
            .description(Description("The IDs of objects", sourceLocation, false))
            .type(NonNullType(ListType(NonNullType(TypeName("ID")))))
            .sourceLocation(sourceLocation)
            .build()

        val nodesField = FieldDefinition
            .newFieldDefinition()
            .name("nodes")
            .description(Description("Fetches objects given their IDs", sourceLocation, false))
            .type(NonNullType(ListType(TypeName("Node"))))
            .directive(createResolverDirective())
            .inputValueDefinition(nodesIdsArgument)
            .sourceLocation(sourceLocation)
            .build()

        // Create Query extension with @scope(to: ["*"])
        val scopeDirective = createScopeDirective(listOf("*"))

        val queryExtension = ObjectTypeExtensionDefinition
            .newObjectTypeExtensionDefinition()
            .name("Query")
            .fieldDefinition(nodeField)
            .fieldDefinition(nodesField)
            .directive(scopeDirective)
            .sourceLocation(sourceLocation)
            .build()

        builder.add(queryExtension)
        log.debug("Added default Node query fields (node, nodes)")
    }

    /**
     * Adds all standard Viaduct scalars to the schema if they don't already exist.
     * This includes all scalars from Scalars.viaductStandardScalars such as Date, DateTime,
     * Long, BackingData, and others.
     *
     * @param registry the TypeDefinitionRegistry to enhance with standard scalars
     * @param allowExisting whether to allow existing definitions without throwing errors
     * @throws RuntimeException if any standard scalar is already defined in user schemas and allowExisting is false
     */
    private fun addStandardScalars(
        builder: RegistryBuilder,
        allowExisting: Boolean = false
    ) {
        defaultScalars().forEach { scalar ->
            if (builder.getType(scalar.name).isPresent) {
                maybeThrow(
                    allowExisting,
                    "Standard Viaduct scalar '${scalar.name}' cannot be redefined in user schemas. " +
                        "This scalar is automatically provided by the framework."
                )
                return@forEach
            }

            val scalarDefinition = ScalarTypeDefinition
                .newScalarTypeDefinition()
                .name(scalar.name)
                .description(Description(scalar.description ?: "Standard Viaduct scalar", null, false))
                .sourceLocation(sourceLocation)
                .build()

            builder.add(scalarDefinition)
            log.debug("Added default {} scalar", scalar.name)
        }
    }

    /**
     * Intelligently adds root types (Query, Mutation, Subscription) only if they have
     * type extensions but no existing type definition.
     *
     * If the user has provided a custom schema definition (e.g., `schema { query: CustomQuery }`),
     * this method respects that and does not add default root types or schema definition.
     *
     * @param registry the TypeDefinitionRegistry to enhance with root types
     * @param force whether to force adding root types even without extensions
     * @param allowExisting whether to allow existing definitions without throwing errors
     */
    private fun addRootTypes(
        builder: RegistryBuilder,
        force: Boolean = false,
        allowExisting: Boolean = false
    ) {
        // Check if user has provided a custom schema definition
        val existingSchemaDefinition = builder.getSchemaDefinition()
        if (existingSchemaDefinition != null) {
            log.debug("Custom schema definition detected. Skipping default root type generation.")
            return
        }

        val objectExtensions = builder.objectTypeExtensions

        val queryTypeName = "Query"
        val mutationTypeName = "Mutation"
        val subscriptionTypeName = "Subscription"

        addRootType(builder, queryTypeName, objectExtensions, allowExisting = allowExisting)
        val didAddMutation = addRootType(builder, mutationTypeName, objectExtensions, ifNeeded = !force, allowExisting = allowExisting)
        val didAddSubscription = addRootType(builder, subscriptionTypeName, objectExtensions, ifNeeded = !force, allowExisting = allowExisting)

        val opDefinitions = mutableListOf<OperationTypeDefinition>(
            OperationTypeDefinition
                .newOperationTypeDefinition()
                .name(
                    OperationDefinition.Operation.QUERY.name
                        .lowercase()
                ).typeName(TypeName.newTypeName(queryTypeName).build())
                .build()
        )
        if (didAddMutation) {
            opDefinitions.add(
                OperationTypeDefinition
                    .newOperationTypeDefinition()
                    .name(
                        OperationDefinition.Operation.MUTATION.name
                            .lowercase()
                    ).typeName(TypeName.newTypeName(mutationTypeName).build())
                    .build()
            )
        }
        if (didAddSubscription) {
            opDefinitions.add(
                OperationTypeDefinition
                    .newOperationTypeDefinition()
                    .name(
                        OperationDefinition.Operation.SUBSCRIPTION.name
                            .lowercase()
                    ).typeName(TypeName.newTypeName(subscriptionTypeName).build())
                    .build()
            )
        }
        val schemaDef = SchemaDefinition
            .newSchemaDefinition()
            .operationTypeDefinitions(opDefinitions)
            .build()
        builder.add(schemaDef)
    }

    private fun addRootType(
        builder: RegistryBuilder,
        typeName: String,
        objectExtensions: Map<String, List<*>>,
        ifNeeded: Boolean = false,
        allowExisting: Boolean = false
    ): Boolean {
        val hasExtensions = objectExtensions.containsKey(typeName)
        val hasDefinition = builder.getType(typeName).isPresent

        if (hasDefinition) {
            maybeThrow(
                allowExisting,
                "Root type $typeName cannot be manually defined in user schemas. " +
                    "The framework automatically provides root types when $typeName extensions are detected. " +
                    "Use 'extend type $typeName { ... }' instead of 'type $typeName { ... }'."
            )
            return false
        }

        // force add if ifNeeded is false, otherwise only add if there are extensions
        val shouldAdd = !ifNeeded || hasExtensions

        if (shouldAdd) {
            val rootType = createEmptyRootType(typeName, hasExtensions)
            builder.add(rootType)
            log.debug(
                "Added default {} root type (detected {} extensions)",
                typeName,
                objectExtensions[typeName]?.size ?: 0
            )
        }

        return shouldAdd
    }

    private fun createEmptyRootType(
        typeName: String,
        hasExtensions: Boolean
    ): ObjectTypeDefinition {
        // Create @scope directive with to: ["*"] to make root type accessible to all scopes
        val scopeDirective = createScopeDirective(listOf("*"))

        val builder = ObjectTypeDefinition
            .newObjectTypeDefinition()
            .name(typeName)
        if (!hasExtensions) {
            // Create a deprecated dummy field to ensure the root type is valid
            val dummyField = FieldDefinition
                .newFieldDefinition()
                .name("_")
                .type(TypeName("String"))
                .directive(
                    Directive
                        .newDirective()
                        .name("deprecated")
                        .argument(
                            Argument
                                .newArgument()
                                .name("reason")
                                .value(
                                    StringValue
                                        .newStringValue("Dummy field to ensure root type is valid. Do not use.")
                                        .sourceLocation(sourceLocation)
                                        .build()
                                ).sourceLocation(sourceLocation)
                                .build()
                        ).build()
                ).sourceLocation(sourceLocation)
                .build()

            // only add dummy field if there are no extensions, otherwise the extensions will provide fields
            builder.fieldDefinition(dummyField)
        }
        return builder
            .directive(scopeDirective)
            .sourceLocation(sourceLocation)
            .build()
    }

    private fun createScopeDirective(to: List<String>): Directive {
        val toArgument = Argument
            .newArgument()
            .name("to")
            .value(
                ArrayValue
                    .newArrayValue()
                    .values(
                        to.map {
                            StringValue
                                .newStringValue(it)
                                .sourceLocation(sourceLocation)
                                .build()
                        }
                    ).build()
            ).sourceLocation(sourceLocation)
            .build()

        return Directive
            .newDirective()
            .name("scope")
            .argument(toArgument)
            .sourceLocation(sourceLocation)
            .build()
    }

    private fun hasNodeTypeReference(builder: RegistryBuilder): Boolean = hasNodeTypeReference(builder.building) || hasNodeTypeReference(builder.extant)

    private fun hasNodeTypeReference(registry: TypeDefinitionRegistry): Boolean {
        registry.getTypes(ImplementingTypeDefinition::class.java).forEach {
            if (hasNodeTypeReference(it)) {
                return true
            }
        }

        registry.objectTypeExtensions().forEach { (_, exts) ->
            if (exts.any(::hasNodeTypeReference)) {
                return true
            }
        }

        registry.interfaceTypeExtensions().forEach { (_, exts) ->
            if (exts.any(::hasNodeTypeReference)) {
                return true
            }
        }

        return false
    }

    private fun hasNodeTypeReference(def: ImplementingTypeDefinition<*>): Boolean =
        hasNodeTypeReference(def.implements) ||
            hasNodeTypeReference(def.fieldDefinitions)

    private fun hasNodeTypeReference(types: List<Type<*>>): Boolean = types.any(::hasNodeTypeReference)

    private fun hasNodeTypeReference(type: Type<*>): Boolean =
        when (type) {
            is NonNullType -> hasNodeTypeReference(type.type)
            is ListType -> hasNodeTypeReference(type.type)
            is TypeName -> type.name == "Node"
            else -> throw IllegalArgumentException("Unsupported type $type")
        }

    @JvmName("hasNodeTypeReference2")
    private fun hasNodeTypeReference(fields: List<FieldDefinition>): Boolean =
        fields.any {
            hasNodeTypeReference(it.type)
        }

    private fun createResolverDirective(): Directive =
        Directive
            .newDirective()
            .name("resolver")
            .sourceLocation(sourceLocation)
            .build()

    /**
     * Conditionally throws a RuntimeException based on allowExisting flag.
     * If allowExisting is true, the exception is not thrown.
     * This provides DRY exception handling for duplicate definitions.
     */
    private fun maybeThrow(
        allowExisting: Boolean,
        message: String
    ) {
        if (!allowExisting) {
            throw IllegalStateException(message)
        }
    }

    @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
    private fun SourceLocation.toString(): String =
        if (this.line == -1 && this.column == -1) {
            this.sourceName ?: "unknown location"
        } else {
            "${this.sourceName ?: "unknown source"}:${this.line}:${this.column}"
        }

    private class RegistryBuilder(
        val building: TypeDefinitionRegistry,
        val extant: TypeDefinitionRegistry
    ) {
        val objectTypeExtensions: Map<String, List<ObjectTypeExtensionDefinition>> get() =
            building.objectTypeExtensions() + extant.objectTypeExtensions()

        fun getDirectiveDefinition(name: String): DirectiveDefinition? =
            building
                .getDirectiveDefinition(name)
                .or { extant.getDirectiveDefinition(name) }
                .getOrNull()

        @Suppress("DEPRECATION")
        fun getType(name: String): Optional<TypeDefinition<*>> = building.getType(name).or { extant.getType(name) }

        fun getSchemaDefinition(): SchemaDefinition? =
            building.schemaDefinition().getOrNull()
                ?: extant.schemaDefinition().getOrNull()

        fun add(def: SDLDefinition<*>): RegistryBuilder {
            building.add(def)
            return this
        }
    }
}
