@file:Suppress("Detekt.MatchingDeclarationName")

package viaduct.arbitrary.graphql

import graphql.Scalars
import graphql.language.BooleanValue
import graphql.language.StringValue
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.SchemaTransformer
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config
import viaduct.engine.api.Coordinate
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective

/** Viaduct's default schema, the source for the `Node` interface and directive definitions used below. */
private val viaductDefaultSchema: GraphQLSchema by lazy {
    val registry = TypeDefinitionRegistry()
    DefaultSchemaFactory.addDefaults(
        registry = registry,
        includeNodeDefinition = DefaultSchemaFactory.IncludeNodeSchema.Always,
        includeNodeQueries = DefaultSchemaFactory.IncludeNodeSchema.Never,
    )
    val wiring = RuntimeWiring.newRuntimeWiring()
        .apply {
            DefaultSchemaFactory.defaultScalars().forEach { scalar(it) }
            type("Node") { it.typeResolver { _ -> null } }
        }
        .build()
    SchemaGenerator().makeExecutableSchema(registry, wiring)
}

private fun viaductDirective(directive: DefaultDirective): GraphQLDirective = viaductDefaultSchema.getDirective(directive.directiveName)!!

/**
 * Return the standard `Node` interface, with its `id: ID!` field, wrapped in [GraphQLTypes].
 * Configure [IncludeTypes] to this result so generated object types can implement `Node`.
 * Directives are removed from the interface and its field so including it does not require
 * Viaduct directive definitions.
 */
fun viaductNodeTypes(): GraphQLTypes {
    val nodeInterface = (viaductDefaultSchema.getType("Node") as GraphQLInterfaceType)
        .transform { builder ->
            builder
                .replaceAppliedDirectives(emptyList())
                .replaceDirectives(emptyList())
                .replaceFields(
                    (viaductDefaultSchema.getType("Node") as GraphQLInterfaceType).fieldDefinitions.map { field ->
                        field.transform { it.replaceAppliedDirectives(emptyList()).replaceDirectives(emptyList()) }
                    }
                )
        }
    return GraphQLTypes.empty.copy(interfaces = mapOf(nodeInterface.name to nodeInterface))
}

/**
 * Generate an arbitrary [GraphQLSchema], then add Viaduct directives and connections according to [cfg].
 * Eligible fields may receive `@idOf` or `@resolver`, and object types implementing `Node` may receive
 * `@resolver`. [ConnectionCount] controls the addition of types annotated with `@edge` and `@connection`.
 */
fun Arb.Companion.viaductDirectiveSchema(cfg: Config = Config.default): Arb<GraphQLSchema> =
    arbitrary { rs ->
        AddViaductDirectives(Arb.graphQLSchema(cfg).next(rs), cfg, rs)
    }

/**
 * Add Viaduct directives and connections to [gjSchema] according to [cfg], using its existing types
 * as candidates for `@idOf`, `@resolver`, and connections. Include definitions for the added directives.
 */
@JvmName("arbViaductDirectiveSchema")
fun Arb.Companion.viaductDirectiveSchema(
    gjSchema: GraphQLSchema,
    cfg: Config = Config.default
): Arb<GraphQLSchema> =
    arbitrary { rs ->
        AddViaductDirectives(gjSchema, cfg, rs)
    }

internal object AddViaductDirectives {
    operator fun invoke(
        gjSchema: GraphQLSchema,
        cfg: Config,
        rs: RandomSource
    ): GraphQLSchema =
        gjSchema
            .let { AddIdOfDirectives(it, cfg, rs) }
            .let { AddConnections(it, cfg, rs) }
            .let { AddDeclaredResolvers(it, cfg, rs) }
}

/**
 * Attach `@idOf(type: "...")` to `ID`-typed object fields, referencing one of the schema's direct
 * `Node` implementors.
 *
 * Fields named `id` are skipped -- Viaduct forbids `@idOf` on a Node's own `id` -- as are fields
 * inherited from an implemented interface, since `@idOf` changes the field's mapped Kotlin return
 * type (`String` -> `GlobalID<Foo>`) and would break the override contract against an interface
 * that lacks it.
 */
internal object AddIdOfDirectives {
    operator fun invoke(
        gjSchema: GraphQLSchema,
        cfg: Config,
        rs: RandomSource
    ): GraphQLSchema {
        val weight = cfg[IdOfFieldWeight]
        val nodeNames = gjSchema.nodeImplementorNames()
        if (weight <= 0.0 || nodeNames.isEmpty()) return gjSchema

        val targets = mutableMapOf<Coordinate, String>()
        gjSchema.nonDefaultObjects().forEach { obj ->
            val inherited = obj.inheritedFieldNames()
            obj.fieldDefinitions.forEach field@{ field ->
                if (field.name == "id" || field.name in inherited) return@field
                if (!field.isNonListIdScalar()) return@field
                if (!rs.sampleWeight(weight)) return@field
                targets[obj.name to field.name] = Arb.of(nodeNames).next(rs)
            }
        }
        if (targets.isEmpty()) return gjSchema

        val directive = viaductDirective(DefaultDirective.ID_OF)
        return gjSchema.withDirectiveDefinition(directive).transformObjectFields { objName, field ->
            targets[objName to field.name]?.let { target ->
                field.withAppliedDirective(directive.appliedIdOf(target))
            }
        }
    }
}

/** Add [ConnectionCount] connections with forward, backward, or bidirectional pagination. */
internal object AddConnections {
    private data class Shape(
        val edge: GraphQLObjectType,
        val connection: GraphQLObjectType,
        val host: Coordinate,
        val paginationArguments: List<GraphQLArgument>
    )

    operator fun invoke(
        gjSchema: GraphQLSchema,
        cfg: Config,
        rs: RandomSource
    ): GraphQLSchema {
        val count = Arb.int(cfg[ConnectionCount]).next(rs)
        if (count <= 0) return gjSchema

        val nodeNames = gjSchema.nodeImplementorNames()
        if (nodeNames.isEmpty()) return gjSchema

        val edgeDirective = viaductDirective(DefaultDirective.EDGE)
        val connectionDirective = viaductDirective(DefaultDirective.CONNECTION)

        val takenNames = gjSchema.allTypesAsList.map { it.name }.toMutableSet()
        val synthetic = mutableSetOf<String>()
        val usedHosts = mutableSetOf<Coordinate>()
        val shapes = mutableListOf<Shape>()

        repeat(count) { index ->
            val nodeName = Arb.of(nodeNames).next(rs)
            val edgeName = uniqueName("${nodeName}Edge", takenNames).also { takenNames += it }
            val connectionName = uniqueName("${nodeName}Connection", takenNames).also { takenNames += it }
            synthetic += setOf(edgeName, connectionName)

            // Only retype a field the object declares itself -- retyping one inherited from an
            // interface would make the object stop satisfying that interface's field signature.
            // Fields already carrying @idOf are excluded: this only replaces a field's type and
            // arguments, so an @idOf added by AddIdOfDirectives would be left behind on a field
            // that no longer returns an ID.
            val host = gjSchema.nonDefaultObjects()
                .filterNot { it.name in synthetic }
                .flatMap { obj ->
                    val inherited = obj.inheritedFieldNames()
                    obj.fieldDefinitions
                        .filterNot { it.name in inherited || it.hasAppliedDirective(DefaultDirective.ID_OF.directiveName) }
                        .map { obj.name to it.name }
                }
                .filterNot { it in usedHosts }
                .ifEmpty { return@repeat }
            val hostField = Arb.of(host).next(rs)
            usedHosts += hostField

            shapes += Shape(
                edge = GraphQLObjectType
                    .newObject()
                    .name(edgeName)
                    .field(
                        GraphQLFieldDefinition
                            .newFieldDefinition()
                            .name("node")
                            .type(GraphQLTypeReference.typeRef(nodeName))
                            .build()
                    )
                    .withAppliedDirective(edgeDirective.toAppliedDirective())
                    .build(),
                connection = GraphQLObjectType
                    .newObject()
                    .name(connectionName)
                    .field(
                        GraphQLFieldDefinition
                            .newFieldDefinition()
                            .name("edges")
                            .type(GraphQLList.list(GraphQLTypeReference.typeRef(edgeName)))
                            .build()
                    )
                    .withAppliedDirective(connectionDirective.toAppliedDirective())
                    .build(),
                host = hostField,
                paginationArguments = paginationArguments[index % paginationArguments.size]
            )
        }
        if (shapes.isEmpty()) return gjSchema

        val retypedTo = shapes.associate { it.host to it }
        return gjSchema
            .withDirectiveDefinition(edgeDirective)
            .withDirectiveDefinition(connectionDirective)
            .transform { builder -> shapes.forEach { builder.additionalType(it.edge).additionalType(it.connection) } }
            .transformObjectFields { objName, field ->
                retypedTo[objName to field.name]?.let { shape ->
                    field.transform { b ->
                        b.type(GraphQLTypeReference.typeRef(shape.connection.name))
                        b.replaceArguments(shape.paginationArguments)
                    }
                }
            }
    }

    private val paginationArguments: List<List<GraphQLArgument>> = run {
        val forward = listOf(
            GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt).build(),
            GraphQLArgument.newArgument().name("after").type(Scalars.GraphQLString).build(),
        )
        val backward = listOf(
            GraphQLArgument.newArgument().name("last").type(Scalars.GraphQLInt).build(),
            GraphQLArgument.newArgument().name("before").type(Scalars.GraphQLString).build(),
        )
        listOf(forward, backward, forward + backward)
    }

    /** Uniquifies [base] against every name already in the schema, across all type kinds. */
    private fun uniqueName(
        base: String,
        taken: Set<String>
    ): String {
        if (base !in taken) return base
        var i = 2
        while ("$base$i" in taken) i++
        return "$base$i"
    }
}

/**
 * Attach `@resolver(isSelective:, isBatching:)` to ordinary object fields
 * ([DeclaredFieldResolverWeight]) and to direct `Node` implementors themselves
 * ([DeclaredNodeResolverWeight]), sampling each argument from [SelectiveResolverWeight] and
 * [BatchingResolverWeight] so declared resolvers match the shape of generated ones.
 */
internal object AddDeclaredResolvers {
    operator fun invoke(
        gjSchema: GraphQLSchema,
        cfg: Config,
        rs: RandomSource
    ): GraphQLSchema {
        val fieldWeight = cfg[DeclaredFieldResolverWeight]
        val nodeWeight = cfg[DeclaredNodeResolverWeight]
        if (fieldWeight <= 0.0 && nodeWeight <= 0.0) return gjSchema

        val directive = viaductDirective(DefaultDirective.RESOLVER)

        fun applied(): GraphQLAppliedDirective =
            directive.appliedResolver(
                isSelective = rs.sampleWeight(cfg[SelectiveResolverWeight]),
                isBatching = rs.sampleWeight(cfg[BatchingResolverWeight]),
            )

        val nodeNames = gjSchema.nodeImplementorNames().toSet()
        val fieldTargets = mutableMapOf<Coordinate, GraphQLAppliedDirective>()
        val objectTargets = mutableMapOf<String, GraphQLAppliedDirective>()

        gjSchema.nonDefaultObjects().forEach { obj ->
            if (fieldWeight > 0.0) {
                obj.fieldDefinitions.forEach { field ->
                    if (rs.sampleWeight(fieldWeight)) {
                        fieldTargets[obj.name to field.name] = applied()
                    }
                }
            }
            if (nodeWeight > 0.0 && obj.name in nodeNames && rs.sampleWeight(nodeWeight)) {
                objectTargets[obj.name] = applied()
            }
        }
        if (fieldTargets.isEmpty() && objectTargets.isEmpty()) return gjSchema

        return gjSchema
            .withDirectiveDefinition(directive)
            .transformObjectFields { objName, field ->
                fieldTargets[objName to field.name]?.let { field.withAppliedDirective(it) }
            }
            .transformObjects { obj -> objectTargets[obj.name]?.let { obj.transform { b -> b.withAppliedDirective(it) } } }
    }
}

/** Object types excluding introspection and Viaduct defaults. */
private fun GraphQLSchema.nonDefaultObjects(): List<GraphQLObjectType> = allTypesAsList.filterIsInstance<GraphQLObjectType>().filter { it.name.isNonDefaultName() }

private fun GraphQLSchema.nodeImplementorNames(): List<String> {
    val node = getType("Node") as? GraphQLInterfaceType ?: return emptyList()
    return getImplementations(node).map { it.name }.filter { it.isNonDefaultName() }
}

/** Field names this object gets from an implemented interface rather than declaring itself. */
private fun GraphQLObjectType.inheritedFieldNames(): Set<String> =
    interfaces
        .filterIsInstance<GraphQLInterfaceType>()
        .flatMapTo(mutableSetOf()) { iface -> iface.fieldDefinitions.map(GraphQLFieldDefinition::getName) }

private fun GraphQLSchema.withDirectiveDefinition(directive: GraphQLDirective): GraphQLSchema = if (getDirective(directive.name) != null) this else transform { it.additionalDirective(directive) }

/** Applies [change] to every object field it returns a replacement for, leaving the rest untouched. */
private fun GraphQLSchema.transformObjectFields(change: (String, GraphQLFieldDefinition) -> GraphQLFieldDefinition?): GraphQLSchema =
    transformObjects { obj ->
        var updated: GraphQLObjectType? = null
        obj.fieldDefinitions.forEach { field ->
            val replacement = change(obj.name, field) ?: return@forEach
            updated = (updated ?: obj).transform { it.field(replacement) }
        }
        updated
    }

/** Applies [change] to every object it returns a replacement for, leaving the rest untouched. */
private fun GraphQLSchema.transformObjects(change: (GraphQLObjectType) -> GraphQLObjectType?): GraphQLSchema =
    SchemaTransformer.transformSchema(
        this,
        object : GraphQLTypeVisitorStub() {
            override fun visitGraphQLObjectType(
                node: GraphQLObjectType,
                context: TraverserContext<GraphQLSchemaElement>
            ): TraversalControl {
                change(node)?.let { changeNode(context, it) }
                return TraversalControl.CONTINUE
            }
        }
    )

private fun GraphQLFieldDefinition.withAppliedDirective(directive: GraphQLAppliedDirective): GraphQLFieldDefinition = transform { it.withAppliedDirective(directive) }

// List IDs are excluded because the codegen backends disagree on GlobalID variance.
private fun GraphQLFieldDefinition.isNonListIdScalar(): Boolean {
    tailrec fun hasList(t: GraphQLType): Boolean =
        when (t) {
            is GraphQLNonNull -> hasList(t.wrappedType)
            is GraphQLList -> true
            else -> false
        }
    return !hasList(type) && (GraphQLTypeUtil.unwrapAll(type) as? GraphQLNamedType)?.name == "ID"
}

private fun GraphQLDirective.appliedIdOf(targetTypeName: String): GraphQLAppliedDirective {
    val typeArg = getArgument("type")
        .toAppliedArgument()
        .transform { it.valueLiteral(StringValue.newStringValue(targetTypeName).build()) }
    return toAppliedDirective().transform { it.replaceArguments(listOf(typeArg)) }
}

private fun GraphQLDirective.appliedResolver(
    isSelective: Boolean,
    isBatching: Boolean
): GraphQLAppliedDirective {
    val selectiveArg = getArgument("isSelective")
        .toAppliedArgument()
        .transform { it.valueLiteral(BooleanValue(isSelective)) }
    val batchingArg = getArgument("isBatching")
        .toAppliedArgument()
        .transform { it.valueLiteral(BooleanValue(isBatching)) }
    return toAppliedDirective().transform { it.replaceArguments(listOf(selectiveArg, batchingArg)) }
}
