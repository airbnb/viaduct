@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.submutations

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.submutations.resolverbases.MutationResolvers

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
