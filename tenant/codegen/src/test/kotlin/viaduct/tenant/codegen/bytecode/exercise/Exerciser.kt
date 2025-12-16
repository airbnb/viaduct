@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package viaduct.tenant.codegen.bytecode.exercise

import kotlinx.coroutines.test.runBlockingTest
import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.invariants.InvariantChecker

internal val logger = LoggerFactory.getLogger(Exerciser::class.java)

// Why does the public version assume a particular package?  This
// entire file makes all kinds of assumptions of how the Viaduct
// codegenerator translates GraphQL types into Java bytecode.  So it's
// consistent that as one of those assumptions it assumes their
// package memberships.

class Exerciser(
    internal val check: InvariantChecker,
    internal val classResolver: ClassResolver,
    val schema: ViaductSchema,
    private val graphqlSchema: ViaductGraphQLSchema,
    internal val classLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
) {
    // limit checks to 500 failures
    private val InvariantChecker.full: Boolean get() = this.size > 500

    private fun maybeCheck(fn: () -> Unit) {
        if (!check.full) fn()
    }

    suspend fun exerciseGeneratedCodeV2(): InvariantChecker {
        for (def in schema.types.values) {
            if (def.name.startsWith("__")) continue
            check.withContext(def.name) {
                when (def) {
                    is ViaductSchema.Enum -> exerciseEnumV2(def)
                    is ViaductSchema.Input -> exerciseInputV2(def, graphqlSchema)
                    is ViaductSchema.Interface -> exerciseInterface(def)
                    is ViaductSchema.Object -> runBlockingTest {
                        exerciseObjectV2(def, graphqlSchema)
                        for (field in def.fields) {
                            if (!field.args.none()) {
                                check.withContext("${field.name}_Arguments") {
                                    exerciseArgInputV2(field, graphqlSchema)
                                }
                            }
                        }
                    }

                    is ViaductSchema.Union -> exerciseUnion(def)
                }
            }
            if (check.full) break
        }

        return check
    }
}
