@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.Scalars
import graphql.introspection.Introspection
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.intRange
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.checkInvariants

class GraphQLTypesTest : KotestPropertyBase() {
    // A default iteration count for all tests in this suite
    // This value can require some maintenance as the system becomes more
    // complex and tests become slower to run. It would be good to keep this value
    // above 100.
    private val iterCount = 2_500

    /**
     * Tests in this suite can be slow when using [Config.default].
     * Define a minimal config that has most features disabled.
     * Tests will need to modify this config to enable the features that they are interested in.
     */
    private val minimalConfig = Config.default +
        (SchemaSize to 10) +
        (DefaultValueWeight to 0.0) +
        (DirectiveWeight to CompoundingWeight.Never) +
        (DirectiveHasArgs to CompoundingWeight.Never) +
        (FieldHasArgs to CompoundingWeight.Never) +
        (Listiness to CompoundingWeight.Never) +
        (InterfaceImplementsInterface to CompoundingWeight.Never) +
        (ObjectImplementsInterface to CompoundingWeight.Never) +
        (DescriptionLength to 0..0) +
        (InputObjectTypeSize to 1..1) +
        (ObjectTypeSize to 1..1) +
        (UnionTypeSize to 1..1) +
        (InterfaceTypeSize to 1..1) +
        (EnumTypeSize to 1..1) +
        (MaxValueDepth to 0) +
        (StringValueSize to 1..1) +
        (ListValueSize to 0..0)

    private fun mkGen(
        cfg: Config = minimalConfig,
        names: GraphQLNames = GraphQLNames.empty,
        random: RandomSource = RandomSource.default()
    ): GraphQLTypesGen = GraphQLTypesGen(cfg, names, random)

    private fun Arb.Companion.compoundingWeight(
        weights: List<Double> = listOf(0.0, 1.0),
        max: IntRange = 0..10
    ): Arb<CompoundingWeight> =
        Arb.pair(Arb.of(weights), Arb.int(max))
            .map { (weight, max) -> CompoundingWeight(weight, max) }

    @Test
    fun `Arb-graphQLTypes can be used to generate a valid schema`(): Unit =
        runBlocking {
            Arb.graphQLTypes(minimalConfig).checkAll(iterCount) {
                SchemaGenerator.mkSchema(it).getOrThrow()
                markSuccess()
            }
        }

    @Test
    fun `GraphQLTypesGen -- genDirectives`(): Unit =
        runBlocking {
            Arb.set(Arb.graphQLName())
                .flatMap { dirNames ->
                    val names = GraphQLNames(mapOf(TypeType.Directive to dirNames))
                    val cfg = minimalConfig + (DirectiveHasArgs to CompoundingWeight.Never)
                    Arb.graphQLTypes(names, cfg).map { dirNames to it }
                }
                .checkInvariants(iterCount) { (dirNames, types), check ->
                    check.containsAtLeastElementsIn(
                        types.directives.keys,
                        dirNames,
                        "Missing directives"
                    )
                }
        }

    @Test
    fun `GraphQLTypesGen -- DirectiveIsRepeatable`(): Unit =
        runBlocking {
            Arb.of(0.0, 1.0)
                .flatMap { weight ->
                    val cfg = minimalConfig + (DirectiveIsRepeatable to weight)
                    Arb.graphQLTypes(cfg).map { weight to it }
                }
                .forAll(iterCount) { (weight, types) ->
                    types.directives.values
                        .filterNot { builtinDirectives.containsKey(it.name) }
                        .all { it.isRepeatable == (weight == 1.0) }
                }
        }

    @Test
    fun `GraphQLTypesGen -- Listiness and NonNullableness`(): Unit =
        runBlocking {
            fun GraphQLType.countDecorations(): Pair<Int, Int> {
                tailrec fun loop(
                    lists: Int,
                    nonNulls: Int,
                    t: GraphQLType
                ): Pair<Int, Int> =
                    if (t is GraphQLList) {
                        loop(lists + 1, nonNulls, t.originalWrappedType)
                    } else if (t is GraphQLNonNull) {
                        loop(lists, nonNulls + 1, t.originalWrappedType)
                    } else {
                        Pair(lists, nonNulls)
                    }
                return loop(0, 0, this)
            }

            val names = GraphQLNames(mapOf(TypeType.Scalar to setOf("A")))
            Arb.pair(
                Arb.compoundingWeight(),
                Arb.element(0.0, 1.0),
            ).checkAll(iterCount) { (listiness, nonNullableness) ->
                val cfg = minimalConfig +
                    (Listiness to listiness) +
                    (NonNullableness to nonNullableness)

                val (lists, nonNulls) = mkGen(cfg, names)
                    .decorate(Scalars.GraphQLInt)
                    .countDecorations()

                if (listiness.weight == 1.0 && lists < listiness.max) {
                    markFailure()
                } else if (nonNullableness == 1.0 && nonNulls != (lists + 1)) {
                    markFailure()
                } else if (listiness.weight == 0.0 && lists != 0) {
                    markFailure()
                } else if (nonNullableness == 0.0 && nonNulls != 0) {
                    markFailure()
                } else {
                    markSuccess()
                }
            }
        }

    @Test
    fun `GraphQLTypesGen - genDescription`(): Unit =
        runBlocking {
            Arb.intRange(0..100)
                .nonEmpty()
                .checkAll(iterCount) { range ->
                    val desc = mkGen(cfg = minimalConfig + (DescriptionLength to range)).genDescription()
                    if (range.contains(desc.length)) {
                        markSuccess()
                    } else {
                        markFailure()
                    }
                }
        }

    @Test
    fun `GraphQLTypes - FieldHasArgs`(): Unit =
        runBlocking {
            Arb.compoundingWeight()
                .flatMap { cw ->
                    val cfg = minimalConfig + (FieldHasArgs to cw)
                    Arb.graphQLTypes(cfg).map { cw to it }
                }
                .checkInvariants(iterCount) { (cw, types), check ->
                    val fields = (types.objects.values + types.interfaces.values)
                        .flatMap { it.fields }

                    if (cw.weight == 1.0 && cw.max > 0 && fields.isNotEmpty()) {
                        check.isTrue(
                            fields.any { it.arguments.isNotEmpty() },
                            "No field arguments generated with FieldHasArgs $cw",
                        )
                    } else {
                        fields.forEach { f ->
                            check.isTrue(
                                f.arguments.isEmpty(),
                                "Unexpected arguments on field ${f.name} with FieldHasArgs $cw",
                            )
                        }
                    }
                }
        }

    @Test
    fun `GraphQLTypes - DefaultValueWeight`(): Unit =
        runBlocking {
            Arb.of(0.0, 1.0)
                .flatMap { weight ->
                    val cfg = minimalConfig + (DefaultValueWeight to weight)
                    Arb.graphQLTypes(cfg).map { weight to it }
                }
                .checkInvariants(iterCount) { (weight, types), check ->
                    (types.objects.values + types.interfaces.values)
                        .flatMap { it.fields }
                        .flatMap { it.arguments }
                        .forEach { arg ->
                            check.isTrue(
                                arg.hasSetDefaultValue() == (weight == 1.0),
                                "arg default: {0}",
                                arrayOf(arg.name)
                            )
                        }

                    (types.inputs.values)
                        .flatMap { it.fields }
                        .forEach { field ->
                            check.isTrue(
                                field.hasSetDefaultValue() == (weight == 1.0),
                                "input field default: {0}",
                                arrayOf(field.name)
                            )
                        }

                    types.directives.values
                        .filterNot { builtinDirectives.containsKey(it.name) }
                        .flatMap { it.arguments }
                        .forEach { arg ->
                            check.isTrue(
                                arg.hasSetDefaultValue() == (weight == 1.0),
                                "dir arg default: {0}",
                                arrayOf(arg.name)
                            )
                        }
                }
        }

    @Test
    fun `GraphQLTypes - IncludeBuiltinScalars`(): Unit =
        runBlocking {
            Arb.of(true, false)
                .flatMap { incl ->
                    val cfg = minimalConfig + (IncludeBuiltinScalars to incl)
                    Arb.graphQLTypes(cfg).map { incl to it }
                }
                .checkInvariants(iterCount) { (incl, types), check ->
                    if (incl) {
                        builtinScalars.keys.subtract(types.scalars.keys).let { missing ->
                            check.isEmpty(missing, "Missing scalars: $missing")
                        }
                    } else {
                        builtinScalars.keys.intersect(types.scalars.keys).let { intersect ->
                            check.isEmpty(
                                intersect,
                                "Expected no included directives but found $intersect"
                            )
                        }
                    }
                }
        }

    @Test
    fun `GraphQLTypes - IncludeBuiltinDirectives`(): Unit =
        runBlocking {
            Arb.of(true, false)
                .flatMap { incl ->
                    val cfg = minimalConfig + (IncludeBuiltinDirectives to incl)
                    Arb.graphQLTypes(cfg).map { incl to it }
                }
                .checkInvariants(iterCount) { (incl, types), check ->
                    if (incl) {
                        check.isTrue(
                            types.directives.keys.containsAll(builtinDirectives.keys),
                            "Missing directives"
                        )
                    } else {
                        types.directives.keys.intersect(builtinDirectives.keys).let { intersect ->
                            check.isEmpty(
                                intersect,
                                "Expected no included directives but found $intersect"
                            )
                        }
                    }
                }
        }

    @Test
    fun `GraphQLTypes - DirectiveHasArgs`(): Unit =
        runBlocking {
            Arb.compoundingWeight()
                .flatMap { cw ->
                    val cfg = minimalConfig +
                        (DirectiveHasArgs to cw) +
                        (IncludeBuiltinDirectives to false)
                    Arb.graphQLTypes(cfg).map { cw to it }
                }.checkInvariants(iterCount) { (cw, types), check ->
                    if (cw.weight == 0.0 || cw.max == 0) {
                        types.directives.values.forEach { dir ->
                            check.isTrue(
                                dir.arguments.isEmpty(),
                                "Unexpected arguments on directive {0} with DirectiveHasArgs {1}: {2}",
                                arrayOf(dir.name, cw.toString(), dir.arguments.toString())
                            )
                        }
                    } else if (types.directives.isNotEmpty()) {
                        check.isTrue(
                            types.directives.values.any { it.arguments.isNotEmpty() },
                            "No directives with arguments generated with DirectiveHasArgs {0}",
                            arrayOf(cw.toString())
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - Directiveness`(): Unit =
        runBlocking {
            // for this test it is helpful to guarantee that the schema contains a directive that can be applied
            // in any location
            val testDirective = GraphQLDirective.newDirective()
                .name("testDirective")
                .validLocations(*Introspection.DirectiveLocation.values())
                .build()

            Arb.compoundingWeight()
                .flatMap { cw ->
                    val cfg = minimalConfig +
                        (DirectiveWeight to cw) +
                        (IncludeBuiltinScalars to false) +
                        (IncludeBuiltinDirectives to false) +
                        (IncludeTypes to GraphQLTypes.empty + testDirective)
                    Arb.graphQLTypes(cfg).map { cw to it }
                }
                .checkInvariants(iterCount) { (cw, types), check ->
                    val elements: List<GraphQLDirectiveContainer> = types.interfaces.values +
                        types.interfaces.values.flatMap { it.fields } +
                        types.inputs.values +
                        types.inputs.values.flatMap { it.fields } +
                        types.objects.values +
                        types.objects.values.flatMap { it.fields } +
                        types.enums.values +
                        types.enums.values.flatMap { it.values } +
                        types.scalars.values

                    if (cw.weight == 1.0 && cw.max > 0 && elements.isNotEmpty()) {
                        check.isTrue(
                            elements.any { it.appliedDirectives.isNotEmpty() },
                            "No applied directives found with Directiveness {0}",
                            arrayOf(cw.toString())
                        )
                    } else {
                        elements.forEach {
                            check.isTrue(
                                it.appliedDirectives.isEmpty(),
                                "Found unexpected directives on type {0} with Directiveness {1}",
                                arrayOf(it.toString(), cw.toString())
                            )
                        }
                    }
                }
        }

    @Test
    fun `GraphQLTypes - FieldNameLength`(): Unit =
        runBlocking {
            Arb.intRange(2..100)
                .nonEmpty()
                .flatMap { range ->
                    val cfg = minimalConfig + (FieldNameLength to range)
                    Arb.graphQLTypes(cfg).map { range to it }
                }.checkInvariants(iterCount) { (range, types), check ->
                    val names = types.objects.values.flatMap { it.fields }.map { it.name } +
                        types.inputs.values.flatMap { it.fields }.map { it.name } +
                        types.enums.values.flatMap { it.values }.map { it.name }

                    names.forEach { name ->
                        check.isTrue(
                            range.contains(name.length),
                            "field name `{0}` has length {1} when range = {2}",
                            arrayOf(name, name.length.toString(), range.toString())
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - InputObjectTypeSize`(): Unit =
        runBlocking {
            Arb.intRange(1..100)
                .nonEmpty()
                .flatMap { range ->
                    val cfg = minimalConfig + (InputObjectTypeSize to range)
                    Arb.graphQLTypes(cfg).map { range to it }
                }.checkInvariants(iterCount) { (range, types), check ->
                    types.inputs.values.forEach { inp ->
                        check.isTrue(
                            inp.fields.size <= range.last,
                            "input {0} has fields {1} outside of range {2}",
                            arrayOf(inp.name, inp.fields.size.toString(), range.toString())
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - InterfaceTypeSize`(): Unit =
        runBlocking {
            Arb.intRange(1..100)
                .nonEmpty()
                .flatMap { range ->
                    val cfg = minimalConfig + (InterfaceTypeSize to range)
                    Arb.graphQLTypes(cfg).map { range to it }
                }.checkInvariants(iterCount) { (range, types), check ->
                    types.interfaces.values.forEach { iface ->
                        check.isTrue(
                            iface.fields.size <= range.last,
                            "interface {0} has fields {1} outside of range {2}",
                            arrayOf(iface.name, iface.fields.size.toString(), range.toString())
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - ObjectTypeSize`(): Unit =
        runBlocking {
            Arb.intRange(1..100)
                .nonEmpty()
                .flatMap { range ->
                    val cfg = minimalConfig + (ObjectTypeSize to range)
                    Arb.graphQLTypes(cfg).map { range to it }
                }.checkInvariants(iterCount) { (range, types), check ->
                    types.objects.values.forEach { obj ->
                        check.isTrue(
                            obj.fields.size <= range.last,
                            "object {0} has fields {1} outside of range {2}",
                            arrayOf(obj.name, obj.fields.size.toString(), range.toString())
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - ObjectImplementsInterface`(): Unit =
        runBlocking {
            // ensure that types includes at least 1 interface
            val baseTypes = GraphQLTypes.empty + GraphQLInterfaceType.newInterface()
                .name("I")
                .field(
                    GraphQLFieldDefinition.newFieldDefinition()
                        .name("f")
                        .type(Scalars.GraphQLInt)
                        .build()
                )
                .build()

            Arb.compoundingWeight()
                .flatMap { cw ->
                    val cfg = minimalConfig +
                        (ObjectImplementsInterface to cw)
                    (IncludeTypes to baseTypes)
                    Arb.graphQLTypes(cfg).map { cw to it }
                }.checkInvariants(iterCount) { (cw, types), check ->
                    val expectInterfaces = cw.weight == 1.0 && cw.max > 0
                    val firstBad = types.objects.values.firstOrNull { it.interfaces.isNotEmpty() != expectInterfaces }
                    if (firstBad != null) {
                        check.addFailure(
                            null,
                            "ObjectImplementsInterface: ${firstBad.name} implements ${firstBad.interfaces.size} interfaces",
                            emptyArray()
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - UnionTypeSize`(): Unit =
        runBlocking {
            Arb.intRange(1..100)
                .nonEmpty()
                .flatMap { range ->
                    val cfg = minimalConfig + (UnionTypeSize to range)
                    Arb.graphQLTypes(cfg).map { range to it }
                }.checkInvariants(iterCount) { (range, types), check ->
                    // Due to collisions, union types may contain fewer members than the
                    // lowest range value
                    types.unions.values.forEach { union ->
                        check.isTrue(
                            union.types.size <= range.last,
                            "union {0} has members {1} greater than range range {2}",
                            arrayOf(union.name, union.types.size.toString(), range.toString())
                        )
                    }
                }
        }

    @Test
    fun `GraphQLTypes - resolve present references`(): Unit =
        runBlocking {
            // resolve present types
            Arb.graphQLNames().map { names ->
                val types = mkGen(names = names).gen()
                names to types
            }.forAll(iterCount) { (names, types) ->
                val refs = names.names
                    .filter {
                        it.key != TypeType.Directive
                    }
                    .flatMap { it.value }
                    .map(GraphQLTypeReference::typeRef)

                refs.all { ref ->
                    types.resolve(ref) != null
                }
            }
        }

    @Test
    fun `GraphQLTypes - resolve missing references`(): Unit =
        runBlocking {
            // resolve present types
            Arb.graphQLNames().map { names ->
                val types = mkGen(names = names).gen()
                names to types
            }.forAll(iterCount) { (names, types) ->
                val otherRefs = Arb.set(Arb.graphQLName(), 1..100)
                    .map { otherNames ->
                        otherNames.filterNot(names.allNames::contains)
                            .map(GraphQLTypeReference::typeRef)
                    }
                    .bind()

                otherRefs.all { types.resolve(it) == null }
            }
        }

    @Test
    fun `GraphQLTypes -- IncludeTypes`(): Unit =
        runBlocking {
            Arb.graphQLTypes(minimalConfig).flatMap { a ->
                val cfg = minimalConfig + (IncludeTypes to a)
                Arb.graphQLTypes(cfg).map { b -> a to b }
            }.forAll(iterCount) { (a, b) ->
                b.names.containsAll(a.names)
            }
        }

    @Test
    fun `GraphQLTypes -- GenInterfaceStubsIfNeeded`(): Unit =
        runBlocking {
            val cfg = minimalConfig + (GenInterfaceStubsIfNeeded to true)
            Arb.graphQLTypes(cfg).forAll(iterCount) { types ->
                val ifaces = types.interfaces.keys
                val implemented = types.objects.values.flatMap { it.interfaces.map { it.name } }
                val unimplemented = ifaces - implemented
                unimplemented.isEmpty()
            }
        }
}
