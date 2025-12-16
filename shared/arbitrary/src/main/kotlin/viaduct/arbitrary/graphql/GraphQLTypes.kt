package viaduct.arbitrary.graphql

import graphql.Scalars
import graphql.introspection.Introspection.DirectiveLocation
import graphql.language.Value
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import graphql.schema.GraphQLUnmodifiedType
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.set
import kotlin.math.min
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.ConfigKey

/** A bag of generated types that can be converted into a GraphQLSchema */
data class GraphQLTypes(
    val interfaces: Map<String, GraphQLInterfaceType>,
    val objects: Map<String, GraphQLObjectType>,
    val inputs: Map<String, GraphQLInputObjectType>,
    val unions: Map<String, GraphQLUnionType>,
    val scalars: Map<String, GraphQLScalarType>,
    val enums: Map<String, GraphQLEnumType>,
    val directives: Map<String, GraphQLDirective>
) {
    val names: Set<String> by lazy {
        val s = mutableSetOf<String>()
        s += interfaces.keys
        s += objects.keys
        s += inputs.keys
        s += unions.keys
        s += scalars.keys
        s += enums.keys
        s += directives.keys
        s.toSet()
    }

    private val directivesByLocation: Map<DirectiveLocation, Set<GraphQLDirective>> by lazy {
        directives.values
            .flatMap { dir ->
                dir.validLocations().map { it to dir }
            }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    /** Try to resolve a GraphQLTypeReference into a GraphQLType */
    fun resolve(ref: GraphQLTypeReference): GraphQLUnmodifiedType? {
        ref.name.let { name ->
            listOf(interfaces, objects, inputs, unions, scalars, enums).forEach {
                if (it.contains(name)) return it[name]
            }
            return null
        }
    }

    /** Get a set of GraphQLDirective's that can be used at a given location */
    fun directivesForLocation(loc: DirectiveLocation): Set<GraphQLDirective> = directivesByLocation[loc] ?: emptySet()

    operator fun plus(directive: GraphQLDirective): GraphQLTypes = copy(directives = directives + (directive.name to directive))

    operator fun plus(iface: GraphQLInterfaceType): GraphQLTypes = copy(interfaces = interfaces + (iface.name to iface))

    companion object {
        val empty: GraphQLTypes = GraphQLTypes(
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
    }
}

/** Generate arbitrary GraphQLTypes from a static Config */
fun Arb.Companion.graphQLTypes(cfg: Config = Config.default): Arb<GraphQLTypes> =
    Arb
        .graphQLNames(cfg)
        .flatMap { names ->
            graphQLTypes(names, cfg)
        }

/** Generate arbitrary GraphQLTypes from a static Config and static names */
fun Arb.Companion.graphQLTypes(
    names: GraphQLNames,
    cfg: Config = Config.default
): Arb<GraphQLTypes> =
    arbitrary { rs ->
        GraphQLTypesGen(cfg, names, rs).gen()
    }

/** Generate arbitrary GraphQLTypes from arbitrary Configs */
fun Arb.Companion.graphQLTypes(cfg: Arb<Config>): Arb<GraphQLTypes> = cfg.flatMap(::graphQLTypes)

/** Internal helper class for generating a GraphQLTypes */
internal class GraphQLTypesGen(
    private val cfg: Config,
    private val names: GraphQLNames,
    private val rs: RandomSource
) {
    private fun sampleWeight(key: ConfigKey<Double>): Boolean = rs.sampleWeight(cfg[key])

    @JvmName("sampleCompoundingWeight")
    private fun sampleWeight(key: ConfigKey<CompoundingWeight>): Boolean = rs.sampleWeight(cfg[key].weight)

    fun gen(): GraphQLTypes =
        // Most of the sub-generators called here can satisfy their type dependencies using
        // the GraphQLNames instance. Sub-generators that require *materialized* types (as
        // opposed to references), are indicated below.
        Arb
            .of(cfg[IncludeTypes])
            .withScalars()
            .withEnums()
            .withInputs() // input field default depend on enums
            .withDirectives() // most generators depend on directives
            .withInterfaces() // depends on inputs for default args
            .withObjects() // depends on interfaces, inputs
            .withUnions()
            .finalize()
            .next(rs)

    internal fun genDescription(): String = Arb.graphQLDescription(cfg).next(rs)

    private fun Arb<GraphQLTypes>.withDirectives(): Arb<GraphQLTypes> =
        map { types ->
            types.copy(
                directives = types.directives + genDirectives(types).associateBy { it.name }
            )
        }

    private fun genDirectives(types: GraphQLTypes): List<GraphQLDirective> {
        val validLocations = DirectiveLocation
            .values()
            .toSet()
            .arbSubset(1..DirectiveLocation.values().size)

        return names.directives.map { name ->
            when (val d = builtinDirectives[name]) {
                null -> {
                    GraphQLDirective
                        .newDirective()
                        .name(name)
                        .description(genDescription())
                        .replaceArguments(genArguments(types, DirectiveHasArgs))
                        .repeatable(sampleWeight(DirectiveIsRepeatable))
                        .also {
                            validLocations.next(rs).forEach(it::validLocation)
                        }.build()
                }
                else -> d
            }
        }
    }

    private fun Arb<GraphQLTypes>.withScalars(): Arb<GraphQLTypes> =
        map { types ->
            types.copy(scalars = types.scalars + genScalars(types).associateBy { it.name })
        }

    private fun genScalars(types: GraphQLTypes): List<GraphQLScalarType> =
        names.scalars.map { name ->
            when (val s = builtinScalars[name]) {
                null ->
                    GraphQLScalarType
                        .newScalar()
                        .name(name)
                        .description(genDescription())
                        .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.SCALAR))
                        .coercing(Scalars.GraphQLString.coercing)
                        .build()
                else -> s
            }
        }

    /**
     * Wrap a provided type in GraphQLList and GraphQLNonNull wrapper types.
     * This function may return a type that has been wrapped multiple times
     */
    internal fun decorate(t: GraphQLType): GraphQLType {
        tailrec fun wrap(
            type: GraphQLType,
            listBudget: Int
        ): GraphQLType =
            if (type !is GraphQLNonNull && sampleWeight(NonNullableness)) {
                wrap(GraphQLNonNull.nonNull(type), listBudget)
            } else if (listBudget > 0 && sampleWeight(Listiness)) {
                wrap(GraphQLList.list(type), listBudget - 1)
            } else {
                type
            }

        return wrap(t, cfg[Listiness].max)
    }

    /**
     * Generate a GraphQLOutputType that is backed by a GraphQLTypeReference and potentially wrapped
     * in GraphQLNonNull and GraphQLList wrappers.
     */
    private fun genOutputTypeRef(): GraphQLOutputType =
        Arb
            .element(names.interfaces + names.scalars + names.unions + names.objects + names.enums)
            .map(GraphQLTypeReference::typeRef)
            .map {
                decorate(it) as GraphQLOutputType
            }.next(rs)

    /**
     * Generate a GraphQLInputType that is backed by a GraphQLTypeReference and potentially wrapped
     * in GraphQLNonNull and GraphQLList wrappers.
     */
    private class InputTypeDescriptor(
        val underlyingTypeName: String,
        val underlyingTypeType: TypeType,
        val type: GraphQLInputType
    ) {
        // This is used to ensure that we do not create cyclic references that graphql-java will reject.
        // This matches GraphQL-Java validation rules, which will reject input
        // cycles that are technically satisfiable even when they contain non-nullable wrappers,
        // such as:
        // input Foo { foos: [[Foo]]! }
        val usedNonNullably: Boolean by lazy {
            tailrec fun loop(t: GraphQLType): Boolean =
                when (t) {
                    is GraphQLNonNull -> true
                    is GraphQLList -> loop(t.originalWrappedType)
                    else -> false
                }
            loop(type)
        }
    }

    private fun genInputTypeDescriptor(): Arb<InputTypeDescriptor> {
        val namePools = setOf(TypeType.Scalar, TypeType.Input, TypeType.Enum)
            .mapNotNull { tt ->
                names.names[tt]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { pool -> tt to pool }
            }

        return Arb
            .element(namePools)
            .flatMap { (tt, pool) ->
                Arb.element(pool).map { tt to it }
            }.map { (tt, name) ->
                InputTypeDescriptor(
                    underlyingTypeName = name,
                    underlyingTypeType = tt,
                    type = decorate(GraphQLTypeReference.typeRef(name)) as GraphQLInputType
                )
            }
    }

    private fun Arb<GraphQLTypes>.withInterfaces(): Arb<GraphQLTypes> =
        map { types ->
            types.copy(interfaces = types.interfaces + genInterfaces(types))
        }

    private fun genInterfaces(types: GraphQLTypes): Map<String, GraphQLInterfaceType> =
        names.interfaces.fold(emptyMap()) { acc, name ->
            val edges = genImplements(acc, InterfaceImplementsInterface)
            val localFields = genFields(types, InterfaceTypeSize)
            val allFields = (edges.flatMap { it.fields } + localFields)
                .distinctBy { it.name }

            val iface = GraphQLInterfaceType
                .newInterface()
                .name(name)
                .description(genDescription())
                .fields(allFields)
                .replaceInterfaces(edges.toList())
                .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.INTERFACE))
                .build()

            acc + (name to iface)
        }

    private fun genImplements(
        pool: Map<String, GraphQLInterfaceType>,
        weightKey: ConfigKey<CompoundingWeight>,
    ): Set<GraphQLInterfaceType> {
        tailrec fun withImplementedInterfaces(
            acc: Set<GraphQLInterfaceType>,
            unchecked: Set<GraphQLInterfaceType>
        ): Set<GraphQLInterfaceType> =
            if (unchecked.isEmpty()) {
                acc
            } else {
                val iface = unchecked.first()
                val parents = iface.interfaces.map { it as GraphQLInterfaceType }
                val toCheck = parents.filterNot(acc::contains).toSet()
                withImplementedInterfaces(
                    acc = acc + iface,
                    unchecked = (unchecked - iface + toCheck)
                )
            }

        tailrec fun loop(
            edges: Set<GraphQLInterfaceType>,
            edgeBudget: Int,
            pool: Map<String, GraphQLInterfaceType>
        ): Set<GraphQLInterfaceType> =
            if (edgeBudget > 0 && pool.isNotEmpty() && sampleWeight(weightKey)) {
                val newEdge = Arb.of(pool.entries).next(rs).value
                loop(
                    edges = edges + newEdge,
                    edgeBudget = edgeBudget - 1,
                    pool = pool - newEdge.name
                )
            } else {
                edges
            }

        // build base edges
        return loop(emptySet(), cfg[weightKey].max, pool)
            .let { edges ->
                // filter out conflicting edges
                edges.nonConflicting()
            }.let { edges ->
                // then add in all interfaces-of-interfaces
                withImplementedInterfaces(emptySet(), edges)
            }
    }

    private fun genArguments(
        types: GraphQLTypes,
        key: ConfigKey<CompoundingWeight>
    ): List<GraphQLArgument> {
        tailrec fun loop(acc: Map<String, GraphQLArgument>): List<GraphQLArgument> =
            if (acc.size != cfg[key].max && sampleWeight(key)) {
                val itd = genInputTypeDescriptor().next(rs)
                val arg = GraphQLArgument
                    .newArgument()
                    .name(Arb.graphQLArgumentName(cfg).next(rs))
                    .description(genDescription())
                    .type(itd.type)
                    .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.ARGUMENT_DEFINITION))
                    .also {
                        if (sampleWeight(DefaultValueWeight)) {
                            val default = genDefaultValue(itd.type, types)
                            it.defaultValueLiteral(default)
                        }
                    }.build()

                loop(acc = acc + (arg.name to arg))
            } else {
                acc.values.toList()
            }

        return loop(emptyMap())
    }

    private fun genDefaultValue(
        type: GraphQLInputType,
        types: GraphQLTypes
    ): Value<*> = Arb.graphQLValueFor(type, types = types, cfg = cfg).next(rs)

    private fun Arb<GraphQLTypes>.withObjects(): Arb<GraphQLTypes> =
        map { types ->
            types.copy(objects = types.objects + genObjects(types).associateBy { it.name })
        }

    private fun genObjects(types: GraphQLTypes): List<GraphQLObjectType> =
        names.objects.map { name ->
            val implements = genImplements(
                types.interfaces,
                ObjectImplementsInterface
            )
            genObject(name, implements, types)
        }

    private fun genObject(
        name: String,
        implements: Set<GraphQLInterfaceType>,
        types: GraphQLTypes
    ): GraphQLObjectType {
        val interfaceFields = implements.flatMap { it.fields }
        val allFields = (interfaceFields + genFields(types, ObjectTypeSize))
            .distinctBy { it.name }

        return GraphQLObjectType
            .newObject()
            .name(name)
            .replaceInterfaces(implements.toList())
            .description(genDescription())
            .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.OBJECT))
            .fields(allFields)
            .build()
    }

    private fun genAppliedDirectives(
        types: GraphQLTypes,
        loc: DirectiveLocation
    ): List<GraphQLAppliedDirective> {
        tailrec fun loop(
            acc: List<GraphQLAppliedDirective>,
            pool: Set<GraphQLDirective>
        ): List<GraphQLAppliedDirective> =
            if (pool.isNotEmpty() && acc.size != cfg[DirectiveWeight].max && sampleWeight(DirectiveWeight)) {
                val dir = Arb.element(pool).next(rs)
                val applied = dir
                    .toAppliedDirective()
                    .transform {
                        val args = dir.arguments.map { arg ->
                            val value = Arb.graphQLValueFor(arg.type, types, cfg).next(rs)
                            arg.toAppliedArgument().transform { it.valueLiteral(value) }
                        }
                        it.replaceArguments(args)
                    }
                loop(
                    acc = acc + applied,
                    pool = if (dir.isRepeatable) pool else (pool - dir)
                )
            } else {
                acc
            }

        return loop(emptyList(), types.directivesForLocation(loc))
    }

    private fun genFields(
        types: GraphQLTypes,
        sizeKey: ConfigKey<IntRange>
    ): List<GraphQLFieldDefinition> =
        Arb
            .int(cfg[sizeKey])
            .map { size ->
                List(size) { genField(types) }.distinctBy { it.name }
            }.next(rs)

    private fun genField(types: GraphQLTypes): GraphQLFieldDefinition =
        GraphQLFieldDefinition
            .newFieldDefinition()
            .name(Arb.graphQLFieldName(cfg).next(rs))
            .description(genDescription())
            .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.FIELD_DEFINITION))
            .arguments(genArguments(types, FieldHasArgs))
            .type(genOutputTypeRef())
            .build()

    private fun Arb<GraphQLTypes>.withInputs(): Arb<GraphQLTypes> =
        map { types ->
            /**
             * while input _type_ generation needs to only know the names of other input objects,
             * input _field_ generation may try to generate default values that require knowing
             * not just the names of other inputs, but also their fields.
             *
             * Break with the pattern used elsewhere in this class and fold over input type generation,
             * allowing later input types to generate values that refer to previously generated input types.
             *
             * Names are sorted to put them into lexicographic order, it's expected that any generated
             * edges that refer to types earlier-in-the-list may be non-nullable. This assumption is also
             * baked into [isUnsatisfiableInputEdge]
             */
            names.inputs.sorted().fold(types) { acc, name ->
                val inp = GraphQLInputObjectType
                    .newInputObject()
                    .name(name)
                    .description(genDescription())
                    .fields(genInputFields(acc, name, InputObjectTypeSize))
                    .replaceAppliedDirectives(genAppliedDirectives(acc, DirectiveLocation.INPUT_OBJECT))
                    .build()

                acc.copy(inputs = acc.inputs + (name to inp))
            }
        }

    private fun genInputFields(
        types: GraphQLTypes,
        hostType: String,
        key: ConfigKey<IntRange>
    ): List<GraphQLInputObjectField> {
        val arbInputField = genInputTypeDescriptor()
            .filterNot { isUnsatisfiableInputEdge(hostType, it) }
            .zip(Arb.graphQLFieldName(cfg))
            .map { (itd, fn) ->
                GraphQLInputObjectField
                    .newInputObjectField()
                    .name(fn)
                    .description(genDescription())
                    .replaceAppliedDirectives(
                        genAppliedDirectives(types, DirectiveLocation.INPUT_FIELD_DEFINITION)
                    ).type(itd.type)
                    .also {
                        if (sampleWeight(DefaultValueWeight)) {
                            it.defaultValueLiteral(genDefaultValue(itd.type, types))
                        }
                    }.build()
            }

        return Arb
            .set(arbInputField, cfg[key])
            .map { it.distinctBy { it.name } }
            .next(rs)
    }

    /**
     * An unsatisfiable input edge is an edge between input types for which it can be impossible to generate a value.
     * This is a problem that is specific to non-nullable input object types, which can create cyclic relationships
     * that require an infinitely large value to satisfy.
     *
     * A simple example of this relationship is:
     *   input A { b: B! }
     *   input B { a: A! }
     *
     * In this example, creating a value for A would require an infinitely nested object, {b: {a: {b: {...}}}
     * This is described in more detail at https://spec.graphql.org/October2021/#sec-Input-Objects.Circular-References
     *
     * Since it is difficult to know during type generation if an input field is going to be part of a cycle,
     * this method uses a heuristic that direct non-nullable edges are only allowed between two objects if the
     * host type name is lexicographically greater than the field's type name.
     *
     * An example of an allowed schema:
     *   input A { b: B }    // host type A < field type B    ->  edge is unsatisfiable
     *   input B { a: A! }   // host type B > field type A    ->  edge is satisfiable
     *
     * This allows generating circular references that can be satisfied with finite values.
     */
    private fun isUnsatisfiableInputEdge(
        hostTypeName: String,
        inputTypeDescriptor: InputTypeDescriptor
    ): Boolean =
        inputTypeDescriptor.underlyingTypeName.compareTo(hostTypeName) != -1 &&
            inputTypeDescriptor.usedNonNullably &&
            inputTypeDescriptor.underlyingTypeType == TypeType.Input

    private fun Arb<GraphQLTypes>.withUnions(): Arb<GraphQLTypes> =
        map { types ->
            types.copy(unions = types.unions + genUnions(types).associateBy { it.name })
        }

    private fun Arb<GraphQLTypes>.finalize(): Arb<GraphQLTypes> =
        if (cfg[GenInterfaceStubsIfNeeded]) {
            map(::addMissingImpls)
        } else {
            this
        }

    private fun addMissingImpls(types: GraphQLTypes): GraphQLTypes {
        tailrec fun loop(
            ifaces: Set<String>,
            objs: List<GraphQLObjectType>
        ): Set<String> =
            if (ifaces.isEmpty() || objs.isEmpty()) {
                ifaces
            } else {
                loop(
                    ifaces - objs
                        .first()
                        .interfaces
                        .map { it.name }
                        .toSet(),
                    objs.drop(1)
                )
            }

        val unimplemented = loop(types.interfaces.keys, types.objects.values.toList())
        val newObjects = unimplemented.map { iname ->
            val ifaces = types.interfaces[iname]!!.let { iface ->
                val parents = iface.interfaces.map { types.interfaces[it.name]!! }
                parents.toSet() + iface
            }
            genObject(iname + "_STUB", ifaces, types)
        }
        return types.copy(
            objects = types.objects + newObjects.associateBy { it.name }
        )
    }

    private fun genUnions(types: GraphQLTypes): List<GraphQLUnionType> {
        if (names.unions.isEmpty() || names.objects.isEmpty()) {
            return emptyList()
        }

        // Arb.set can be finicky with what it calls slippage, which is when it wants to
        // generate a set of a certain size but has to make multiple attempts due to the
        // underlying generator returning the same element multiple times.
        // For unions, it's not critically important that we get the exact right size every
        // time, so just use a list generator and remove duplicates
        val arbMembers = Arb
            .list(
                gen = Arb.element(names.objects),
                range = IntRange(
                    min(names.objects.size, cfg[UnionTypeSize].first),
                    min(names.objects.size, cfg[UnionTypeSize].last)
                )
            ).map { it.distinct() }

        return names.unions.map { name ->
            val members = arbMembers.next(rs).map(::GraphQLTypeReference)

            GraphQLUnionType
                .newUnionType()
                .name(name)
                .description(genDescription())
                .replacePossibleTypes(members)
                .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.UNION))
                .build()
        }
    }

    private fun genEnumValues(types: GraphQLTypes): List<GraphQLEnumValueDefinition> =
        // Generating enum values using Arb.set can throw an IllegalStateException if it can't
        // generate the target size. This becomes more likely as the target size grows and collisions
        // become harder to avoid.
        // Use Arb.list instead which is close enough and more reliable
        Arb
            .list(
                Arb.graphQLEnumValueName(cfg),
                cfg[EnumTypeSize]
            ).map { values ->
                values.toSet().map {
                    GraphQLEnumValueDefinition
                        .newEnumValueDefinition()
                        .name(it)
                        .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.ENUM_VALUE))
                        .build()
                }
            }.next(rs)

    private fun Arb<GraphQLTypes>.withEnums(): Arb<GraphQLTypes> =
        map { types ->
            types.copy(enums = types.enums + genEnums(types).associateBy { it.name })
        }

    private fun genEnums(types: GraphQLTypes): List<GraphQLEnumType> =
        names.enums.map { name ->
            GraphQLEnumType
                .newEnum()
                .name(name)
                .description(genDescription())
                .values(genEnumValues(types))
                .replaceAppliedDirectives(genAppliedDirectives(types, DirectiveLocation.ENUM))
                .build()
        }
}
