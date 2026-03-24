package viaduct.arbitrary.graphql

import graphql.schema.GraphQLSchema
import io.kotest.property.Arb
import io.kotest.property.checkAll
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema

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

internal fun mkViaductSchema(
    sdl: String,
    includeMinimal: Boolean = true
): ViaductSchema = ViaductSchema.fromGraphQLSchema(mkGJSchema(sdl, includeMinimal))

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

internal suspend fun Arb<*>.assertNoErrors() =
    checkAll {
        markSuccess()
    }
