@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.checkInvariants

class GraphQLNamesTest : KotestPropertyBase() {
    @Test
    fun `Arb_graphQLNames`(): Unit =
        runBlocking {
            Arb.int(min = 1, max = 1000)
                .forAll { count ->
                    val cfg = Config.default + (SchemaSize to count)
                    val names = Arb.graphQLNames(cfg).bind()

                    val subtotal =
                        names.interfaces.size +
                            names.objects.size +
                            names.inputs.size +
                            names.unions.size +
                            names.scalars.size +
                            names.enums.size +
                            names.directives.size

                    subtotal == names.allNames.size
                }
        }

    @Test
    fun `Arb_graphQLNames -- IncludeCustomScalars`() {
        Arb.of(true, false)
            .flatMap { incl ->
                val cfg = Config.default + (GenCustomScalars to incl)
                Arb.graphQLNames(cfg)
                    .map { names -> incl to names.scalars - builtinScalars.keys }
            }
            .checkInvariants { (incl, scalars), check ->
                if (!incl) {
                    check.isTrue(
                        scalars.isEmpty(),
                        "Found ${scalars.size} scalars when IncludeCustomScalars is $incl"
                    )
                }
            }
    }

    @Test
    fun `Arb_graphQLNames -- TypeTypeWeights -- zero`(): Unit =
        runBlocking {
            // all weights are 0
            val cfg = Config.default +
                (TypeTypeWeights to TypeTypeWeights.zero) +
                (IncludeBuiltinScalars to false) +
                (IncludeBuiltinDirectives to false)

            Arb.graphQLNames(cfg).forAll { names -> names.allNames.isEmpty() }
        }

    @Test
    fun `Arb_graphQLNames -- TypeTypeWeights -- skewed weights`(): Unit =
        runBlocking {
            val ttw = TypeTypeWeights.default + (TypeType.Enum to 10.0) + (TypeType.Union to 0.0)
            Arb.int(0 until 1_000)
                .flatMap { schemaSize ->
                    val cfg = Config.default + (TypeTypeWeights to ttw) + (SchemaSize to schemaSize)
                    Arb.graphQLNames(cfg)
                }.forAll { names ->
                    names.unions.isEmpty() && names.enums.size >= names.objects.size
                }
        }

    @Test
    fun `graphQLNames plus`(): Unit =
        runBlocking {
            Arb.pair(
                Arb.graphQLNames(),
                Arb.graphQLNames()
            ).forAll { (a, b) ->
                (a.allNames + b.allNames) == (a + b).allNames
            }
        }

    @Test
    fun `filter`(): Unit =
        runBlocking {
            Arb.graphQLNames().forAll { names ->
                names.filter { false } == GraphQLNames.empty
            }

            Arb.graphQLNames().forAll { names ->
                names.filter { true } == names
            }
        }
}
