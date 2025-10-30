@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.schema.GraphQLInputType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductSchema
import viaduct.mapping.graphql.RawValue
import viaduct.mapping.graphql.ValueMapper

class GraphQLValuesTest {
    private object ToStringMapper : ValueMapper<ViaductSchema.TypeExpr, RawValue, String> {
        override fun invoke(
            type: ViaductSchema.TypeExpr,
            value: RawValue
        ): String = "$type:$value"
    }

    val cfg = Config.default + (GenInterfaceStubsIfNeeded to true) +
        (SchemaSize to 10) +
        (ObjectTypeSize to 1..2) +
        (ListValueSize to 0..1) +
        (MaxValueDepth to 1)

    private val graphQLTypesWithInputs: Arb<GraphQLTypes> =
        Arb.graphQLTypes(cfg).filter { it.allInputs.isNotEmpty() }

    private val GraphQLTypes.allInputs: Collection<GraphQLInputType>
        get() = inputs.values + enums.values + scalars.values

    @Test
    fun `graphQLValueFor -- type and typeresolver`(): Unit =
        runBlocking {
            graphQLTypesWithInputs.flatMap { types ->
                Arb.of(types.allInputs).flatMap { type ->
                    // The ".Companion" notation is not needed, though it gives a hint to the code coverage scanner
                    // that a function is covered.
                    Arb.graphQLValueFor(
                        type,
                        TypeReferenceResolver.fromTypes(types),
                        cfg
                    )
                }
            }.assertNoErrors()
        }

    @Test
    fun `graphQLValueFor -- type and types`(): Unit =
        runBlocking {
            graphQLTypesWithInputs.flatMap { types ->
                Arb.of(types.allInputs).flatMap { type ->
                    Arb.graphQLValueFor(type, types, cfg)
                }
            }.assertNoErrors()
        }

    @Test
    fun `rawValueFor -- type and resolver`(): Unit =
        runBlocking {
            graphQLTypesWithInputs.flatMap { types ->
                Arb.of(types.allInputs).flatMap { type ->
                    Arb.rawValueFor(
                        type,
                        TypeReferenceResolver.fromTypes(types),
                        cfg
                    )
                }
            }.assertNoErrors()
        }

    @Test
    fun `rawValueFor -- type and types`(): Unit =
        runBlocking {
            graphQLTypesWithInputs.flatMap { types ->
                Arb.of(types.allInputs).flatMap { type ->
                    Arb.rawValueFor(type, types, cfg)
                }
            }.assertNoErrors()
        }

    @Test
    fun `rawValueFor -- typedef`(): Unit =
        runBlocking {
            Arb.typeExpr(cfg).flatMap { type ->
                Arb.rawValueFor(type.baseTypeDef, cfg)
            }.assertNoErrors()
        }

    @Test
    fun `rawValueFor -- bridge typeexpr`(): Unit =
        runBlocking {
            Arb.typeExpr(cfg).flatMap { type ->
                Arb.rawValueFor(type, cfg)
            }.assertNoErrors()
        }

    @Test
    fun `mappedValueFor -- mapped bridge typedef `(): Unit =
        runBlocking {
            Arb.typeExpr(cfg).flatMap { type ->
                Arb.mappedValueFor(type.baseTypeDef, ToStringMapper, cfg)
            }.assertNoErrors()
        }

    @Test
    fun `mappedValueFor -- mapped bridge typexpr `(): Unit =
        runBlocking {
            Arb.typeExpr(cfg).flatMap { expr ->
                Arb.mappedValueFor(expr, ToStringMapper, cfg)
            }.assertNoErrors()
        }
}
