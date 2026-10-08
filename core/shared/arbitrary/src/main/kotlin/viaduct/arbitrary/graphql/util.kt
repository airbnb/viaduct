package viaduct.arbitrary.graphql

import graphql.Directives
import graphql.ExecutionInput
import graphql.Scalars
import graphql.introspection.Introspection
import graphql.language.Document
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeName
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.FastSchemaGenerator
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import io.kotest.property.RandomSource
import kotlin.collections.flatMap
import kotlin.collections.map
import kotlin.collections.toSet
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.ConfigKey
import viaduct.arbitrary.common.sampleWeight
import viaduct.engine.SchemaFactory
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.gj
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective
import viaduct.graphql.utils.allChildren

internal fun Set<GraphQLInterfaceType>.nonConflicting(): Set<GraphQLInterfaceType> {
    // A challenge in identifying non-conflicting interfaces is in knowing the
    // origins of any given field definition. For example, given:
    //
    //     interface A { foo: String }
    //     interface B implements A { foo: String }
    //     interface C { foo: String }
    //
    // We can say that A and B are not in conflict because B has inherited the definition of `foo`
    // from A by implementing it. It would be valid for a type to implement both A and B
    //
    // However, A and C are in conflict because even though they define compatible definitions of
    // `foo`, there is no relationship between types A and C. It would be invalid for a type to
    // implement A and C together.
    //
    // This method builds the disjoint sets of related interfaces using a naive version
    // of union-find.
    tailrec fun loop(
        leaderToMembers: Map<GraphQLInterfaceType, Set<GraphQLInterfaceType>>,
        memberToLeader: Map<GraphQLInterfaceType, GraphQLInterfaceType>,
        pool: Set<GraphQLInterfaceType>
    ): List<Set<GraphQLInterfaceType>> {
        if (pool.isEmpty()) {
            return leaderToMembers.values.toList()
        }

        val item = pool.first()
        val maybeLeader = item.interfaces
            .sortedBy { it.name }
            .firstOrNull { leaderToMembers.containsKey(it) }
            ?.let { it as GraphQLInterfaceType }

        return when {
            maybeLeader != null && item.name < maybeLeader.name -> {
                // promote this item to the leader of its group
                val oldMembers = leaderToMembers[maybeLeader] ?: emptySet()
                val newLeaderToMembers = leaderToMembers - maybeLeader + (item to (oldMembers + maybeLeader))
                val newMemberToLeader = oldMembers.fold(memberToLeader) { acc, member ->
                    acc + (member to item)
                }
                loop(newLeaderToMembers, newMemberToLeader, pool - item)
            }
            maybeLeader != null -> {
                // add this item to an existing group
                loop(
                    leaderToMembers + (maybeLeader to (leaderToMembers[maybeLeader]!! + item)),
                    memberToLeader + (item to maybeLeader),
                    pool - item
                )
            }
            else -> {
                // add this item to a new group
                loop(
                    leaderToMembers + (item to setOf(item)),
                    memberToLeader + (item to item),
                    pool - item
                )
            }
        }
    }

    val sets = loop(emptyMap(), emptyMap(), this)

    val nonOverlapping = sets.fold(emptyMap<String, GraphQLInterfaceType>()) { acc, group ->
        val groupFields = group.flatMap { it.fields }.map { it.name }
        if (groupFields.none(acc::containsKey)) {
            acc + groupFields.associateWith { group.first() }
        } else {
            acc
        }
    }
    return nonOverlapping.values.toSet()
}

internal val builtinScalars: Map<String, GraphQLScalarType> =
    listOf(
        Scalars.GraphQLBoolean,
        Scalars.GraphQLID,
        Scalars.GraphQLInt,
        Scalars.GraphQLFloat,
        Scalars.GraphQLString
    ).associateBy { it.name }

/** Names of [builtinScalars], for callers outside this module that only need membership checks. */
val builtinScalarNames: Set<String> = builtinScalars.keys

internal val builtinDirectives: Map<String, GraphQLDirective> =
    listOf(
        Directives.DeferDirective,
        Directives.DeprecatedDirective,
        Directives.ExperimentalDisableErrorPropagationDirective,
        Directives.IncludeDirective,
        Directives.OneOfDirective,
        Directives.SkipDirective,
        Directives.SpecifiedByDirective,
    ).associateBy { it.name }

/** Names of the builtin directives. */
val builtinDirectiveNames: Set<String> = builtinDirectives.keys

/** Names declared by Viaduct's default schema. */
val viaductDefaultNames: Set<String> = setOf("Node") + DefaultDirective.values().map { it.directiveName }

internal fun String.isNonDefaultName(): Boolean = !startsWith("__") && this !in viaductDefaultNames

/** convert this [graphql.language.Type] representation into its [graphql.schema.GraphQLType] counterpart */
fun Type<*>.asSchemaType(schema: EngineSchema): GraphQLType = asSchemaType(schema.schema)

/** convert this [graphql.language.Type] representation into its [graphql.schema.GraphQLType] counterpart */
fun Type<*>.asSchemaType(schema: GraphQLSchema): GraphQLType =
    when (this) {
        is ListType -> GraphQLList.list(type.asSchemaType(schema))
        is NonNullType -> GraphQLNonNull.nonNull(type.asSchemaType(schema))
        is TypeName -> schema.getTypeAs(this.name)
        else ->
            throw UnsupportedOperationException("unsupported language Type: $this")
    }

/** convert this [graphql.schema.GraphQLType] representation into its [graphql.language.Type] counterpart */
fun GraphQLType.asAstType(): Type<*> =
    when (this) {
        is GraphQLList ->
            ListType(GraphQLTypeUtil.unwrapOneAs<GraphQLInputType>(this).asAstType())
        is GraphQLNonNull ->
            NonNullType(GraphQLTypeUtil.unwrapOneAs<GraphQLInputType>(this).asAstType())
        is GraphQLNamedType -> TypeName(name)
        else ->
            throw UnsupportedOperationException("unsupported schema Type: $this")
    }

/** Return a mocked [EngineSchema] described by this String value */
val String.asViaductSchema: EngineSchema
    get() = SchemaFactory().fromSdl(this)

/** Return a mocked [GraphQLSchema] described by this String value */
val String.asSchema: GraphQLSchema get() = FastSchemaGenerator().makeExecutableSchema(SchemaParser().parse(this), RuntimeWiring.MOCKED_WIRING)

/** Return a parsed [Document] described by this String value */
val String.asDocument: Document get() =
    Parser.parse(
        ParserEnvironment
            .newParserEnvironment()
            .document(this)
            // use the SDL parser options, which will parse an unlimited number of tokens
            .parserOptions(ParserOptions.getDefaultSdlParserOptions())
            .build()
    )

/** A [Comparator] that orders [ExecutionInput]s by how many nodes are in their parsed document */
val ExecutionInputComparator: Comparator<ExecutionInput> =
    // Perf note:
    // This Comparator reparses a document text to compare the size of the node trees.
    // A faster alternative would be to skip the parse step and just compare the document string lengths.
    // For a test that takes ~15s with a ~10% failure rate, the perf improvement of comparing document text
    // lengths is about 300ms, or about 2%. This seems like a reasonable perf penalty to be consistent
    // with DocumentComparator and is expected to be tolerable for most tests.
    Comparator.comparingInt { it.query.asDocument.allChildren.size }

/** A [Comparator] that orders [Document]s by their node count */
val DocumentComparator: Comparator<Document> =
    Comparator.comparingInt { it.allChildren.size }

/** throw [ResolverException] if sampling the weight described by [key] returns true */
internal fun maybeThrowResolverException(
    cfg: Config,
    key: ConfigKey<Double>,
    rs: RandomSource
) {
    if (rs.sampleWeight(cfg[key])) {
        throw ResolverException(key)
    }
}

/** return all object types in the current schema */
internal val EngineSchema.objects: List<GraphQLObjectType>
    get() =
        this.schema.typeMap.mapNotNull { (name, type) ->
            if (type is GraphQLObjectType && !Introspection.isIntrospectionTypes(name)) {
                type
            } else {
                null
            }
        }

/** return all object coordinates in the current schema */
internal val EngineSchema.objectCoordinates: Set<Coordinate>
    get() = objects.flatMap { it.objectCoordinates }.toSet()

/** return all composite type names in the current schema */
internal val EngineSchema.compositeTypeNames: Set<TypeOrFieldCoordinate>
    get() =
        buildSet {
            schema.allTypesAsList.forEach {
                if (it is GraphQLCompositeType) {
                    add(it.name to null)
                }
            }
        }

/** return all coordinates for the given type in the current schema */
internal fun EngineSchema.objectCoordinates(type: GraphQLCompositeType): Set<Coordinate> =
    rels.possibleObjectTypes(type)
        .flatMap(GraphQLObjectType::objectCoordinates)
        .toSet()

internal val GraphQLObjectType.objectCoordinates: Set<Coordinate>
    get() = fields.map { f -> name to f.name }.toSet()

internal val EngineSchema.nodeImpls: Set<String>
    get() {
        val nodeType = schema.getType("Node")
            ?.let { it as? GraphQLInterfaceType }
            ?: return emptySet()

        return rels.possibleObjectTypes(nodeType)
            .map { it.name }
            .toSet()
    }

internal fun Coordinate.supportsSubselections(schema: EngineSchema): Boolean = GraphQLTypeUtil.unwrapAll(schema.schema.getFieldDefinition(this.gj).type) is GraphQLCompositeType

class ResolverException(val key: ConfigKey<*>) : Exception() {
    override val message: String =
        "This is a synthetic ResolverException configured by ${key.javaClass.name}"
}

typealias TypeOrFieldCoordinate = Pair<String, String?>
