package viaduct.arbitrary.graphql

import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.property.Arb
import io.kotest.property.checkAll
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
