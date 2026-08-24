@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.submutations

import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.submutations.resolverbases.MutationResolvers

@GraphQLOperation("mutation(\$triangleSize: Int!) { exampleMutationSelections(triangleSize: \$triangleSize) }")
object ExampleMutationSelectionsMutation : MutationFromAnnotation()

class KotlinRecursiveSubmutationContractTest : RecursiveSubmutationContractTest() {
    @Resolver
    class Mutation_ExampleMutationSelections : MutationResolvers.ExampleMutationSelections() {
        override suspend fun resolve(ctx: Context): Int? {
            val size = ctx.arguments.triangleSize
            return when (size) {
                1 -> 1
                else -> {
                    val mutation = ctx.mutation(ExampleMutationSelectionsMutation, mapOf("triangleSize" to size - 1))
                    size + mutation.getExampleMutationSelectionsOrThrow()!!
                }
            }
        }
    }
}
