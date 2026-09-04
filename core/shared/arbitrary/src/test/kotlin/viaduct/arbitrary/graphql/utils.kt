package viaduct.arbitrary.graphql

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLUnionType
import io.kotest.property.Arb
import io.kotest.property.checkAll
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.Config
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

private val minimalSdl = """
    type Query {
        int: Int
        float: Float
        bool: Boolean
        str: String
    }
""".trimIndent()

internal fun mkGJSchema(
    sdl: String,
    includeMinimal: Boolean = true
): GraphQLSchema =
    sdl
        .let {
            if (includeMinimal) {
                """
                $minimalSdl
                $sdl
                """.trimIndent()
            } else {
                sdl
            }
        }.asSchema

internal fun mkConfig(
    enull: Double = 0.0,
    inull: Double = 0.0,
    maxValueDepth: Int = MaxValueDepth.default,
    schemaSize: Int = SchemaSize.default,
    genInterfaceStubs: Boolean = GenInterfaceStubsIfNeeded.default,
    listValueSize: Int = ListValueSize.default.first
): Config =
    Config.default +
        (ExplicitNullValueWeight to enull) +
        (ImplicitNullValueWeight to inull) +
        (MaxValueDepth to maxValueDepth) +
        (SchemaSize to schemaSize) +
        (GenInterfaceStubsIfNeeded to genInterfaceStubs) +
        (ListValueSize to listValueSize..listValueSize)

fun ViaductSchema.mkEngineSelectionSet(
    typeName: String,
    selections: String,
    variables: Map<String, Any?> = emptyMap()
): EngineSelectionSet =
    EngineSelectionSetFactoryImpl(this)
        .engineSelectionSet(
            SelectionsParser.parse(typeName, selections),
            variables
        )

internal suspend fun Arb<*>.assertNoErrors() =
    checkAll {
        markSuccess()
    }

/**
 * Pushes schema generation to cover every TypeType at once (including custom scalars), guarantees
 * every interface has an implementing object, and uses large type/field counts.
 *
 * Slower than [Config.default] -- callers should use a low iteration count (5-20). Kept local to
 * this test module rather than exported as a shared preset, since the knobs it tunes are only
 * meaningful for tests that want to exercise every TypeType at once.
 */
internal val coverageConfig: Config = Config.default +
    (SchemaSize to 150) +
    (
        // Types not listed here fall back to the default weight of 1.0.
        TypeTypeWeights to mapOf(
            TypeType.Object to 4.0,
            TypeType.Interface to 1.5,
            TypeType.Input to 1.5
        )
    ) +
    (GenCustomScalars to true) +
    (GenInterfaceStubsIfNeeded to true) +
    (ObjectImplementsInterface to CompoundingWeight(.6, 4)) +
    (InterfaceImplementsInterface to CompoundingWeight(.4, 3)) +
    (ObjectTypeSize to 4..10) +
    (InterfaceTypeSize to 3..8) +
    (InputObjectTypeSize to 3..8) +
    (UnionTypeSize to 3..8) +
    (EnumTypeSize to 3..8) +
    (FieldArgumentWeight to CompoundingWeight(.5, 3)) +
    (DefaultValueWeight to 0.0) +
    (AppliedDirectiveWeight to CompoundingWeight(.4, 3)) +
    (DirectiveHasArgs to CompoundingWeight.Never) +
    (OneOfTypeWeight to .3) +
    (DescriptionLength to 0..0)

/** Type-kind breakdown and coverage checks for a list of GraphQL named types. */
internal class TypeKindCoverage(types: List<GraphQLNamedType>) {
    val objects = types.filterIsInstance<GraphQLObjectType>()
    val interfaces = types.filterIsInstance<GraphQLInterfaceType>()
    val unions = types.filterIsInstance<GraphQLUnionType>()
    val inputs = types.filterIsInstance<GraphQLInputObjectType>()
    val enums = types.filterIsInstance<GraphQLEnumType>()
    val scalars = types.filterIsInstance<GraphQLScalarType>()
    val customScalars = scalars.filterNot { it.name in builtinScalars }

    val everyInterfaceImplemented: Boolean =
        interfaces.all { iface -> objects.any { obj -> obj.interfaces.any { it.name == iface.name } } }

    fun summary(label: String): String =
        "[$label] objects=${objects.size} interfaces=${interfaces.size} unions=${unions.size} " +
            "inputs=${inputs.size} enums=${enums.size} scalars=${scalars.size} (custom=${customScalars.size})"
}

class MockEngineCtx(
    override val globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault,
    override val fieldRefs: FieldRefs = FieldRefs.empty
) : EngineCtx {
    data class MockNodeReference(override val id: String, override val type: GraphQLObjectType) : NodeReference

    data class MockRootFieldReference(
        override val rootFieldPath: List<String>,
        override val type: GraphQLObjectType,
        override val args: Map<String, Any?>
    ) : RootFieldReference

    override fun createNodeReference(
        id: String,
        objectType: GraphQLObjectType
    ): NodeReference = MockNodeReference(id, objectType)

    override fun createRootFieldReference(
        rootFieldPath: List<String>,
        type: GraphQLObjectType,
        args: Map<String, Any?>
    ): RootFieldReference = MockRootFieldReference(rootFieldPath, type, args)

    companion object {
        operator fun invoke(
            schema: ViaductSchema,
            globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault
        ): MockEngineCtx = MockEngineCtx(globalIDCodec, FieldRefs(schema))
    }
}
