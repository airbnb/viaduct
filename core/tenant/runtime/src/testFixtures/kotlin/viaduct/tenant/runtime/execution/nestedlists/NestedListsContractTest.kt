package viaduct.tenant.runtime.execution.nestedlists

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

@TestSchema(
    """
    enum Status { ACTIVE INACTIVE }
    input Child { value: Int fallback: Int = 17 }
    interface Named { value: Int }
    type Person implements Node & Named {
      id: ID!
      value: Int
    }
    union Choice = Person
    input NestedInput {
      children2: [[Child]] = [[{value: 7}, null], null, []]
      children3: [[[Child]]]
      statuses2: [[Status]]
      statuses3: [[[Status]]]
      ids2: [[ID]] @idOf(type: "Person")
      ids3: [[[ID]]] @idOf(type: "Person")
      dateTimes2: [[DateTime]]
      dateTimes3: [[[DateTime]]]
      dates2: [[Date]]
      dates3: [[[Date]]]
      times2: [[Time]]
      times3: [[[Time]]]
      ints2: [[Int]]
      ints3: [[[Int]]]
      strict: [[Child!]!]!
      defaultStatuses: [[[Status]]] = [[[ACTIVE, null], null], null]
    }
    interface NestedFields {
      children2: [[Person]]
      children3: [[[Person]]]
      statuses2: [[Status]]
      statuses3: [[[Status]]]
      ids2: [[ID]] @idOf(type: "Person")
      ids3: [[[ID]]] @idOf(type: "Person")
      dateTimes2: [[DateTime]]
      dateTimes3: [[[DateTime]]]
      dates2: [[Date]]
      dates3: [[[Date]]]
      times2: [[Time]]
      times3: [[[Time]]]
      ints2: [[Int]]
      ints3: [[[Int]]]
      interfaces2: [[Named]]
      interfaces3: [[[Named]]]
      unions2: [[Choice]]
      unions3: [[[Choice]]]
      strict: [[Person!]!]!
    }
    type NestedOutput implements NestedFields {
      children2: [[Person]]
      children3: [[[Person]]]
      statuses2: [[Status]]
      statuses3: [[[Status]]]
      ids2: [[ID]] @idOf(type: "Person")
      ids3: [[[ID]]] @idOf(type: "Person")
      dateTimes2: [[DateTime]]
      dateTimes3: [[[DateTime]]]
      dates2: [[Date]]
      dates3: [[[Date]]]
      times2: [[Time]]
      times3: [[[Time]]]
      ints2: [[Int]]
      ints3: [[[Int]]]
      interfaces2: [[Named]]
      interfaces3: [[[Named]]]
      unions2: [[Choice]]
      unions3: [[[Choice]]]
      strict: [[Person!]!]!
    }
    type PersonEdge @edge {
      node: Person
      cursor: String!
    }
    type PersonConnection @connection {
      edges: [PersonEdge!]!
      pageInfo: PageInfo!
      ids2: [[ID]] @idOf(type: "Person")
      ids3: [[[ID]]] @idOf(type: "Person")
      strictIDs: [[ID!]!] @idOf(type: "Person")
    }
    extend type Query {
      connection: PersonConnection @resolver
      echo(input: NestedInput, ids2: [[ID]] @idOf(type: "Person"), ids3: [[[ID]]] @idOf(type: "Person")): NestedOutput @resolver
      verify: String @resolver
    }
    """
)
abstract class NestedListsContractTest : KotlinFeatureAppTestContractBase() {
    companion object {
        const val ECHO_SELECTION = """
      echo(input: ${'$'}input, ids2: ${'$'}ids2, ids3: ${'$'}ids3) {
        children2 { value } children3 { value }
        aliasedInterfaces: interfaces2 { value } interfaces3 { value }
        unions2 { ... on Person { value } } aliasedUnions: unions3 { ... on Person { value } }
        statuses2 aliasedStatuses: statuses3
        aliasedIDs: ids2 ids3
        dateTimes2 aliasedDateTimes: dateTimes3 dates2 dates3 times2 times3 ints2 ints3
        strict { value }
      }
      """

        @JvmField val DATE_TIME: Instant = Instant.parse("2026-01-02T03:04:05Z")
        @JvmField val DATE: LocalDate = LocalDate.parse("2026-01-02")
        @JvmField val TIME: OffsetTime = OffsetTime.parse("03:04:05Z")

        @JvmStatic
        fun assertSpecializedLeaves(
            values: Map<String, Any?>,
            status: Any,
            id: Any
        ) {
            val leaves = mapOf("statuses" to status, "ids" to id, "dateTimes" to DATE_TIME, "dates" to DATE, "times" to TIME, "ints" to 9)
            val expected = leaves.flatMap { (field, leaf) -> listOf(field + "2" to matrix(leaf), field + "3" to cube(leaf)) }.toMap()
            assertThat(values).isEqualTo(expected)
        }

        @JvmStatic
        fun <T> matrix(leaf: T): List<List<T?>?> = listOf(listOf(leaf, null), null, emptyList())

        @JvmStatic
        fun <T> cube(leaf: T): List<List<List<T?>?>?> = listOf(matrix(leaf), null, emptyList())
    }

    @Test
    fun `generated builders accessors and subqueries preserve specialized leaves`() {
        val result = execute("{ verify }")
        assertThat(result.errors).isEmpty()
        assertThat(result.getData()).isEqualTo(mapOf("verify" to "ok"))
    }

    @Test
    fun `nested raw input variables preserve nullable shape through execution`() {
        val id = GlobalIDCodecDefault.serialize("Person", "1")
        val leaves = mapOf(
            "children" to mapOf("value" to 9),
            "statuses" to "ACTIVE",
            "ids" to id,
            "dateTimes" to "2026-01-02T03:04:05.000Z",
            "dates" to "2026-01-02",
            "times" to "03:04:05Z",
            "ints" to 9,
        )
        val input = leaves.flatMap { (field, leaf) -> listOf(field + "2" to matrix(leaf), field + "3" to cube(leaf)) }.toMap() +
            ("strict" to listOf(listOf(mapOf("value" to 9))))
        val expected = input + mapOf(
            "interfaces2" to matrix(mapOf("value" to 9)),
            "interfaces3" to cube(mapOf("value" to 9)),
            "unions2" to matrix(mapOf("value" to 9)),
            "unions3" to cube(mapOf("value" to 9)),
        )
        val result = execute(
            query = """
                query(${'$'}input: NestedInput, ${'$'}ids2: [[ID]], ${'$'}ids3: [[[ID]]]) {
                  echo(input: ${'$'}input, ids2: ${'$'}ids2, ids3: ${'$'}ids3) {
                    children2 { value } children3 { value }
                    interfaces2 { value } interfaces3 { value }
                    unions2 { ... on Person { value } } unions3 { ... on Person { value } }
                    statuses2 statuses3 ids2 ids3 dateTimes2 dateTimes3 dates2 dates3 times2 times3 ints2 ints3
                    strict { value }
                  }
                }
            """.trimIndent(),
            variables = mapOf("input" to input, "ids2" to matrix(id), "ids3" to cube(id)),
        )
        assertThat(result.errors).isEmpty()
        assertThat(result.getData()).isEqualTo(mapOf("echo" to expected))
    }

    @Test
    fun `connection builders preserve nested typed IDs including nullable containers and leaves`() {
        val result = execute("{ connection { alias: ids2 ids3 strictIDs edges { cursor } pageInfo { hasNextPage hasPreviousPage } } }")
        val id = GlobalIDCodecDefault.serialize("Person", "1")
        assertThat(result.errors).isEmpty()
        assertThat(result.getData()).isEqualTo(
            mapOf(
                "connection" to mapOf(
                    "alias" to matrix(id),
                    "ids3" to cube(id),
                    "strictIDs" to listOf(listOf(id)),
                    "edges" to emptyList<Any>(),
                    "pageInfo" to mapOf("hasNextPage" to false, "hasPreviousPage" to false),
                )
            )
        )
    }
}
