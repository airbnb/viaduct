@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.contract.submutations

import viaduct.api.Resolver
import viaduct.tenant.runtime.contract.submutations.resolverbases.MutationResolvers
import viaduct.tenant.runtime.fixtures.recursivesubmutationcontract.RecursiveSubmutationContractTest

class KotlinRecursiveSubmutationContractTest : RecursiveSubmutationContractTest() {
    @Resolver
    class Mutation_ExampleMutationSelections : MutationResolvers.ExampleMutationSelections() {
        override suspend fun resolve(ctx: Context): Int? {
            val size = ctx.arguments.triangleSize
            return when (size) {
                1 -> 1
                else -> {
                    val mutation = ctx.mutation("exampleMutationSelections(triangleSize: ${size - 1})")
                    size + mutation.getExampleMutationSelections()!!
                }
            }
        }
    }
}
