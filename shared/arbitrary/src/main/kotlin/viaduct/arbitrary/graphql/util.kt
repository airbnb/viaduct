package viaduct.arbitrary.graphql

import graphql.Directives
import graphql.ExecutionInput
import graphql.Scalars
import graphql.language.Document
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeName
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.SchemaGenerator
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.shuffle
import java.lang.IllegalArgumentException
import kotlin.collections.filter
import kotlin.collections.map
import kotlin.math.max
import kotlin.math.min
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.WeightValidator
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

/**
 * Return subsets of a given arb. Subsets will have a size determined by the provided
 * range, or if no range is provided, then subsets will contain between 0 and size-of-the-input-set
 * elements.
 */
fun <T> Arb<Set<T>>.subset(range: IntRange? = null): Arb<Set<T>> =
    flatMap { set ->
        val first = min(max(range?.first ?: 0, 0), set.size)
        val last = min(max(range?.last ?: 0, 0), set.size)

        Arb.pair(
            Arb.shuffle(set.toList()),
            Arb.int(first..last)
        ).map { (shuffled, count) ->
            shuffled.take(count).toSet()
        }
    }

/**
 * Return an Arb<Set> describing subsets of this Set.
 * @see Arb<Set<T>>.subset
 */
fun <T> Set<T>.arbSubset(range: IntRange? = null): Arb<Set<T>> = Arb.constant(this).subset(range = range)

/** Return an IntRange containing only this Int */
fun Int.asIntRange(): IntRange = IntRange(this, this)

/**
 * Filter an Arb<IntRange> to only yield non-empty IntRange values.
 * This is different from [Arb.Companion.intRange], which can yield empty
 * IntRange values.
 */
fun Arb<IntRange>.nonEmpty(): Arb<IntRange> = this.filterNot { it.isEmpty() }

/** Return a new Arb containing only non-null values */
@Suppress("UNCHECKED_CAST")
fun <T> Arb<T?>.filterNotNull(): Arb<T> = filter { it != null }.map { it as T }

/** Generate an Arb that zips values of this arb with the values of another Arb */
fun <T, U> Arb<T>.zip(other: Arb<U>): Arb<Pair<T, U>> = Arb.bind(this, other) { t, u -> t to u }

internal val builtinScalars: Map<String, GraphQLScalarType> =
    listOf(
        Scalars.GraphQLBoolean,
        Scalars.GraphQLID,
        Scalars.GraphQLInt,
        Scalars.GraphQLFloat,
        Scalars.GraphQLString
    ).associateBy { it.name }

// @deprecated has some challenges and is filtered out
// - GJ seems to require that the "reason" arg is non-null:
//   https://github.com/graphql-java/graphql-java/blob/228535194df5468cda8b465d56a7624c13d6a7a7/src/main/java/graphql/schema/idl/SchemaGeneratorHelper.java#L244
// - the spec requires that when applied to input fields or arguments, that the types
//   of those objects be nullable or have a default value. The type generator doesn't have
//   enough context to know this. So let's drop deprecated for now
internal val builtinDirectives: Map<String, GraphQLDirective> =
    listOf(
        Directives.OneOfDirective,
        // Directives.DeprecatedDirective,
        Directives.SpecifiedByDirective,
    ).associateBy { it.name }

/**
 * This method is a replacement for [Arb.Companion.choose]. This method will
 * never pick an Arb with a 0 weight. [Arb.Companion.choose] can, via edge cases, select an Arb
 * that is a assigned a weight of 0.
 */
fun <T> Arb.Companion.weightedChoose(
    weightedArb: Pair<Double, Arb<T>>,
    fallbackArb: Arb<T>
): Arb<T> {
    val weight =
        weightedArb.first.also {
            WeightValidator(it)?.let { msg ->
                throw IllegalArgumentException(msg)
            }
        }

    return when (weight) {
        0.0 -> fallbackArb
        1.0 -> weightedArb.second
        else -> {
            val intWeight = (weight * 1000).toInt()
            Arb.choose(
                intWeight to weightedArb.second,
                (1000 - intWeight) to fallbackArb
            )
        }
    }
}

/**
 * [Arb.Companion.choose] can, via edge cases, select an Arb that is assigned a weight of 0.
 * This method is a replacement for [Arb.Companion.choose] that will never pick an Arb with a 0 weight.
 */
fun <T> Arb.Companion.weightedChoose(arbs: List<Pair<Double, Arb<T>>>): Arb<T> {
    val weightedArbs = arbs
        .filter { (weight, _) -> weight > 0.0 }
        .map { (weight, arb) -> (weight * 1000).toInt() to arb }

    require(weightedArbs.size > 0)

    return if (weightedArbs.size == 1) {
        weightedArbs.first().second
    } else {
        Arb.choose(
            weightedArbs[0],
            weightedArbs[1],
            *weightedArbs.drop(2).toTypedArray()
        )
    }
}

/** A unit arb, that always returns Unit */
fun Arb.Companion.unit(): Arb<Unit> = Arb.of(Unit)

/**
 * Transform a Collection of Arb<T> into an Arb of List<T>.
 *
 * Example:
 *   val list = listOf(Arb.of(1), Arb.of(2), Arb.of(3))
 *   val items = list.collect().next(rs)   // listOf(1, 2, 3)
 */
fun <T> Collection<Arb<T>>.collect(): Arb<List<T>> = Arb.bind(this.toList()) { it }

/**
 * Get a boolean from this random source, with probability of a true
 * value being equal to the provided weight
 */
fun RandomSource.sampleWeight(weight: Double): Boolean =
    when (weight) {
        0.0 -> false
        1.0 -> true
        else -> random.nextDouble(0.0, 1.0) <= weight
    }

/**
 * Return an integer describing how many times the provided [CompoundingWeight]
 * was sampled before it hit its `max` sample count or returned false.
 */
fun RandomSource.count(weight: CompoundingWeight): Int {
    tailrec fun loop(count: Int): Int =
        if (count == weight.max) {
            count
        } else if (!sampleWeight(weight.weight)) {
            count
        } else {
            loop(count + 1)
        }
    return loop(0)
}

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

/** Return a mocked [GraphQLSchema] described by this String value */
val String.asSchema: GraphQLSchema get() = SchemaGenerator.createdMockedSchema(this)

/** Return a parsed [Document] described by this String value */
val String.asDocument: Document get() =
    Parser.parse(
        ParserEnvironment.newParserEnvironment()
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
