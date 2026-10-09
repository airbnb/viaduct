package viaduct.tenant.runtime.execution.nestedlists

import org.assertj.core.api.Assertions.assertThat
import viaduct.api.context.ExecutionContext
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.globalid.GlobalID
import viaduct.api.resolver.Resolver
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.execution.nestedlists.resolverbases.QueryResolvers

@GraphQLOperation(
    "query(\$input: NestedInput, \$ids2: [[ID]], \$ids3: [[[ID]]]) {" + NestedListsContractTest.ECHO_SELECTION + "}"
)
object EchoQuery : QueryFromAnnotation()

class KotlinNestedListsContractTest : NestedListsContractTest() {
    companion object {
        private fun input(
            ctx: ExecutionContext,
            id: GlobalID<Person>
        ): NestedInput {
            val child = Child.Builder(ctx).value(9).build()
            return NestedInput.Builder(ctx)
                .children2(matrix(child)).children3(cube(child))
                .statuses2(matrix(Status.ACTIVE)).statuses3(cube(Status.ACTIVE))
                .ids2(matrix(id)).ids3(cube(id))
                .dateTimes2(matrix(DATE_TIME)).dateTimes3(cube(DATE_TIME))
                .dates2(matrix(DATE)).dates3(cube(DATE))
                .times2(matrix(TIME)).times3(cube(TIME))
                .ints2(matrix(9)).ints3(cube(9))
                .strict(listOf(listOf(child))).build()
        }

        private fun output(
            ctx: ExecutionContext,
            id: GlobalID<Person>
        ): NestedOutput {
            val person = Person.Builder(ctx).id(id).value(9).build()
            return NestedOutput.Builder(ctx)
                .children2(matrix(person)).children3(cube(person))
                .interfaces2(matrix(person)).interfaces3(cube(person))
                .unions2(matrix(person)).unions3(cube(person))
                .statuses2(matrix(Status.ACTIVE)).statuses3(cube(Status.ACTIVE))
                .ids2(matrix(id)).ids3(cube(id))
                .dateTimes2(matrix(DATE_TIME)).dateTimes3(cube(DATE_TIME))
                .dates2(matrix(DATE)).dates3(cube(DATE))
                .times2(matrix(TIME)).times3(cube(TIME))
                .ints2(matrix(9)).ints3(cube(9))
                .strict(listOf(listOf(person))).build()
        }

        private fun assertInput(
            input: NestedInput,
            id: GlobalID<Person>
        ) {
            assertSpecializedLeaves(
                mapOf(
                    "statuses2" to input.statuses2,
                    "statuses3" to input.statuses3,
                    "ids2" to input.ids2,
                    "ids3" to input.ids3,
                    "dateTimes2" to input.dateTimes2,
                    "dateTimes3" to input.dateTimes3,
                    "dates2" to input.dates2,
                    "dates3" to input.dates3,
                    "times2" to input.times2,
                    "times3" to input.times3,
                    "ints2" to input.ints2,
                    "ints3" to input.ints3,
                ),
                Status.ACTIVE,
                id
            )
            assertThat(input.children2!![0]!![0]!!.value).isEqualTo(9)
            assertThat(input.children3!![0]!![0]!![0]!!.value).isEqualTo(9)
            assertThat(input.children2!![0]!![0]!!.fallback).isEqualTo(17)
            assertThat(input.children3!![0]!![0]!![0]!!.fallback).isEqualTo(17)
            assertThat(input.children2!![1]).isNull()
            assertThat(input.children3!![0]!![0]!![1]).isNull()
            assertThat(input.strict[0][0].value).isEqualTo(9)
        }

        private fun assertOutput(
            output: NestedOutput,
            id: GlobalID<Person>,
            aliased: Boolean
        ) {
            val interfaces = output.getInterfaces2(if (aliased) "aliasedInterfaces" else null)
            val unions = output.getUnions3(if (aliased) "aliasedUnions" else null)
            assertThat(output.getChildren2()!![0]!![0]!!.getValue()).isEqualTo(9)
            assertThat(output.getChildren3()!![0]!![0]!![0]!!.getValue()).isEqualTo(9)
            assertThat(output.getChildren2()!![1]).isNull()
            assertThat(output.getChildren3()!![0]!![0]!![1]).isNull()
            assertThat(interfaces!![0]!![0]!!.getValue()).isEqualTo(9)
            assertThat(output.getInterfaces3()!![0]!![0]!![0]!!.getValue()).isEqualTo(9)
            assertThat(output.getUnions2()!![0]!![0]).isInstanceOf(Person::class.java)
            assertThat((unions!![0]!![0]!![0] as Person).getValue()).isEqualTo(9)
            assertThat(unions[1]).isNull()
            assertSpecializedLeaves(
                mapOf(
                    "statuses2" to output.getStatuses2(),
                    "statuses3" to output.getStatuses3(if (aliased) "aliasedStatuses" else null),
                    "ids2" to output.getIds2(if (aliased) "aliasedIDs" else null),
                    "ids3" to output.getIds3(),
                    "dateTimes2" to output.getDateTimes2(),
                    "dateTimes3" to output.getDateTimes3(if (aliased) "aliasedDateTimes" else null),
                    "dates2" to output.getDates2(),
                    "dates3" to output.getDates3(),
                    "times2" to output.getTimes2(),
                    "times3" to output.getTimes3(),
                    "ints2" to output.getInts2(),
                    "ints3" to output.getInts3(),
                ),
                Status.ACTIVE,
                id
            )
            assertThat(output.getStrictOrThrow()[0][0].getValue()).isEqualTo(9)
            assertThat(output.getChildren2()).isSameAs(output.getChildren2())
            assertThat(output.getUnions3(if (aliased) "aliasedUnions" else null)).isSameAs(unions)
            val view: NestedFields = output
            assertThat(view.getChildren3OrThrow()).isSameAs(output.getChildren3())
            assertThat(view.getIds2OrThrow(if (aliased) "aliasedIDs" else null)).isEqualTo(matrix(id))
        }
    }

    @Resolver
    class VerifyResolver : QueryResolvers.Verify() {
        override suspend fun resolve(ctx: Context): String {
            val id = ctx.globalIDFor(Person.Reflection, "1")
            val input = input(ctx, id)
            assertInput(input, id)
            assertInput(input.toBuilder().build(), id)
            val built = output(ctx, id)
            assertOutput(built, id, false)
            val nulls = built.toBuilder().children2(null).interfaces3(null).unions2(null).statuses2(null)
                .ids3(null).dateTimes2(null).dates3(null).times2(null).build()
            assertThat(
                listOf(
                    nulls.getChildren2(),
                    nulls.getInterfaces3(),
                    nulls.getUnions2(),
                    nulls.getStatuses2(),
                    nulls.getIds3(),
                    nulls.getDateTimes2(),
                    nulls.getDates3(),
                    nulls.getTimes2()
                )
            ).containsOnlyNulls()
            val defaults = NestedInput.Builder(ctx).strict(emptyList()).build()
            assertThat(defaults.children2!![0]!![0]!!.value).isEqualTo(7)
            assertThat(defaults.children2!![0]!![1]).isNull()
            assertThat(defaults.children2!![1]).isNull()
            assertThat(defaults.toBuilder().build().defaultStatuses)
                .isEqualTo(listOf(listOf(listOf(Status.ACTIVE, null), null), null))
            val explicitNull = defaults.toBuilder().children2(null).defaultStatuses(null).build()
            assertThat(explicitNull.children2).isNull()
            assertThat(explicitNull.defaultStatuses).isNull()
            val args = Query_Echo_Arguments.Builder(ctx).input(input).ids2(matrix(id)).ids3(cube(id)).build()
            assertThat(args.ids2).isEqualTo(matrix(id))
            assertThat(args.ids3).isEqualTo(cube(id))
            assertThat(args.inputData["ids2"]).isEqualTo(matrix(GlobalIDCodecDefault.serialize("Person", "1")))
            assertThat(args.inputData["ids3"]).isEqualTo(cube(GlobalIDCodecDefault.serialize("Person", "1")))
            val query = ctx.query(EchoQuery, mapOf("input" to input, "ids2" to matrix(id), "ids3" to cube(id)))
            assertOutput(query.getEchoOrThrow()!!, id, true)
            return "ok"
        }
    }

    @Resolver
    class EchoResolver : QueryResolvers.Echo() {
        override suspend fun resolve(ctx: Context): NestedOutput {
            val id = ctx.globalIDFor(Person.Reflection, "1")
            assertInput(ctx.arguments.input!!, id)
            assertThat(ctx.arguments.ids2).isEqualTo(matrix(id))
            assertThat(ctx.arguments.ids3).isEqualTo(cube(id))
            return output(ctx, id)
        }
    }

    @Resolver
    class ConnectionResolver : QueryResolvers.Connection() {
        override suspend fun resolve(ctx: Context): PersonConnection {
            val id = ctx.globalIDFor(Person.Reflection, "1")
            val connection = PersonConnection.Builder(ctx).ids2(matrix(id)).ids3(cube(id))
                .strictIDs(listOf(listOf(id))).fromEdges(emptyList()).build()
            assertThat(connection.getIds2()).isEqualTo(matrix(id))
            assertThat(connection.getIds3()).isEqualTo(cube(id))
            val copy = connection.toBuilder().build()
            assertThat(copy.getIds2()).isEqualTo(matrix(id))
            assertThat(copy.getIds3()).isEqualTo(cube(id))
            val nulls = connection.toBuilder().ids2(null).ids3(null).build()
            assertThat(nulls.getIds2()).isNull()
            assertThat(nulls.getIds3()).isNull()
            return copy
        }
    }
}
