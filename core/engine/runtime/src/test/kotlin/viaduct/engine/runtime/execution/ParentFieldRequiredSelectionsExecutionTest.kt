package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest

class ParentFieldRequiredSelectionsExecutionTest {
    @Test
    fun `type checker required selections resolve the returned object's parent`() {
        val checkedParentName = AtomicReference<String>()

        EngineTestModule(
            """
            extend type Query { organization: Organization }
            type Organization { name: String, company: Company }
            type Company { name: String, user: User }
            type User { id: ID, parent: Company @parent }
            """.trimIndent(),
        ) {
            field("Query" to "organization") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Organization"),
                            mapOf("name" to "Engineering")
                        )
                    }
                }
            }
            field("Organization" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("name" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("User"),
                            mapOf("id" to "user-1")
                        )
                    }
                }
            }
            type("User") {
                checker {
                    objectSelections("parentData", "parent { name }")
                    fn { _, objectDataMap ->
                        checkedParentName.set(
                            objectDataMap.getValue("parentData")
                                .fetchAs<EngineObjectData>("parent")
                                .fetchAs<String>("name")
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ organization { company { user { id } } } }")
                .assertJson("{data: {organization: {company: {user: {id: 'user-1'}}}}}")
        }

        assertEquals("Airbnb", checkedParentName.get())
    }

    @Test
    fun `execution resolves parent fields required by resolver selection sets`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, parentCompanyName: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { parentCompanyName } } }")
                .assertJson("{data: {company: {user: {parentCompanyName: 'Airbnb'}}}}")
        }
    }

    @Test
    fun `execution resolves nested parent fields required by resolver selection sets`() {
        EngineTestModule(
            """
            extend type Query { organization: Organization }
            type Organization { name: String, company: Company }
            type Company { parent: Organization @parent, user: User }
            type User { parent: Company @parent, parentOrganizationName: String }
            """.trimIndent(),
        ) {
            field("Query" to "organization") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Organization"),
                            mapOf("name" to "Engineering")
                        )
                    }
                }
            }
            field("Organization" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentOrganizationName") {
                resolver {
                    objectSelections("parent { parent { name } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent")
                            .fetchAs<EngineObjectData>("parent")
                            .fetchAs<String>("name")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ organization { company { user { parentOrganizationName } } } }")
                .assertJson("{data: {organization: {company: {user: {parentOrganizationName: 'Engineering'}}}}}")
        }
    }

    @Test
    fun `execution resolves parent fields for each object in list child fields`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, users: [User] }
            type User { parent: Company @parent, parentCompanyName: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "users") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(
                            createEngineObjectData(schema.schema.getObjectType("User"), emptyMap()),
                            createEngineObjectData(schema.schema.getObjectType("User"), emptyMap()),
                        )
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { users { parentCompanyName } } }")
                .assertJson("{data: {company: {users: [{parentCompanyName: 'Airbnb'}, {parentCompanyName: 'Airbnb'}]}}}")
        }
    }

    @Test
    fun `execution resolves interface typed parent fields`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            interface ParentCompany { companyName: String }
            type Company implements ParentCompany { companyName: String, user: User }
            type User { parent: ParentCompany @parent, parentCompanyName: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { parentCompanyName } } }")
                .assertJson("{data: {company: {user: {parentCompanyName: 'Airbnb'}}}}")
        }
    }

    @Test
    fun `execution resolves union typed parent fields`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            union ParentCompany = Company
            type Company { companyName: String, user: User }
            type User { parent: ParentCompany @parent, parentCompanyName: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { ... on Company { companyName } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { parentCompanyName } } }")
                .assertJson("{data: {company: {user: {parentCompanyName: 'Airbnb'}}}}")
        }
    }

    @Test
    fun `execution traverses parent after normal child traversal in resolver selection sets`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, profile: Profile, profileCompanyName: String }
            type Profile { parent: User @parent }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "profile") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Profile"), emptyMap())
                    }
                }
            }
            field("User" to "profileCompanyName") {
                resolver {
                    objectSelections("profile { parent { parent { companyName } } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("profile")
                            .fetchAs<EngineObjectData>("parent")
                            .fetchAs<EngineObjectData>("parent")
                            .fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { profileCompanyName } } }")
                .assertJson("{data: {company: {user: {profileCompanyName: 'Airbnb'}}}}")
        }
    }

    @Test
    fun `execution resolves nested object selections on parent fields`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { address: Address, user: User }
            type Address { city: String }
            type User { parent: Company @parent, parentCity: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "address") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Address"),
                            mapOf("city" to "San Francisco")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCity") {
                resolver {
                    objectSelections("parent { address { city } }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent")
                            .fetchAs<EngineObjectData>("address")
                            .fetchAs<String>("city")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { parentCity } } }")
                .assertJson("{data: {company: {user: {parentCity: 'San Francisco'}}}}")
        }
    }

    @Test
    fun `execution resolves repeated aliased parent selections`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, parentCompanyNames: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Company"),
                            mapOf("companyName" to "Airbnb")
                        )
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCompanyNames") {
                resolver {
                    objectSelections("firstParent: parent { firstName: companyName } secondParent: parent { secondName: companyName }")
                    fn { _, obj, _, _, _ ->
                        val firstName = obj.fetchAs<EngineObjectData>("firstParent").fetchAs<String>("firstName")
                        val secondName = obj.fetchAs<EngineObjectData>("secondParent").fetchAs<String>("secondName")
                        "$firstName,$secondName"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { parentCompanyNames } } }")
                .assertJson("{data: {company: {user: {parentCompanyNames: 'Airbnb,Airbnb'}}}}")
        }
    }

    @Test
    fun `parent field checker denies restricted data like equivalent normal field`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { secret: String, user: User }
            type User { company: Company, parent: Company @parent }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "secret") {
                value("classified")
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
                checker {
                    fn { _, _ -> error("company access denied") }
                }
            }
            field("User" to "parent") {
                checker {
                    fn { _, _ -> error("company access denied") }
                }
            }
        }.runFeatureTest {
            val result = runQuery(
                """
                {
                  company {
                    user {
                      normalAccess: company { secret }
                      parentAccess: parent { secret }
                    }
                  }
                }
                """.trimIndent()
            )

            assertEquals(
                mapOf(
                    "company" to mapOf(
                        "user" to mapOf(
                            "normalAccess" to null,
                            "parentAccess" to null,
                        )
                    )
                ),
                result.getData()
            )
            assertEquals(
                setOf(
                    listOf("company", "user", "normalAccess"),
                    listOf("company", "user", "parentAccess"),
                ),
                result.errors.map { it.path }.toSet(),
            )
            assertTrue(result.errors.all { it.message.contains("company access denied") })
        }
    }

    @Test
    fun `execution runs checker but skips field instrumentation for parent field itself`() {
        val parentCheckerCount = AtomicInteger()
        val nestedFieldCheckerCount = AtomicInteger()
        val recordingInstrumentation = RecordingInstrumentation()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, parentCompanyName: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "companyName") {
                resolver {
                    fn { _, _, _, _, _ -> "Airbnb" }
                }
                checker {
                    fn { _, _ ->
                        nestedFieldCheckerCount.incrementAndGet()
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parent") {
                checker {
                    fn { _, _ ->
                        parentCheckerCount.incrementAndGet()
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest(
            engineConfig = EngineConfiguration.featureTestDefault.copy(
                additionalInstrumentation = ViaductModernInstrumentation.asGJInstrumentation(recordingInstrumentation),
            )
        ) {
            runQuery("{ company { user { parentCompanyName } } }")
                .assertJson("{data: {company: {user: {parentCompanyName: 'Airbnb'}}}}")
        }

        assertEquals(1, parentCheckerCount.get())
        assertEquals(1, nestedFieldCheckerCount.get())

        val fieldExecutionCoordinates = recordingInstrumentation.fieldExecutionContexts
            .map {
                val parameters = it.parameters as InstrumentationFieldParameters
                parameters.executionStepInfo.objectType.name to parameters.executionStepInfo.field.name
            }
            .toSet()
        val fieldFetchingCoordinates = recordingInstrumentation.fieldFetchingContexts
            .map {
                val parameters = it.parameters as InstrumentationFieldFetchParameters
                parameters.executionStepInfo.objectType.name to parameters.executionStepInfo.field.name
            }
            .toSet()

        assertFalse("User" to "parent" in fieldExecutionCoordinates)
        assertFalse("User" to "parent" in fieldFetchingCoordinates)
        assertTrue("Company" to "companyName" in fieldExecutionCoordinates)
        assertTrue("Company" to "companyName" in fieldFetchingCoordinates)
    }

    @Test
    fun `execution resolves checker required selection sets for parent field itself`() {
        val parentCheckerCount = AtomicInteger()
        val parentCheckerRequiredSelectionCount = AtomicInteger()
        val nestedParentFieldCount = AtomicInteger()

        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User {
              parent: Company @parent
              parentCompanyName: String
              parentCheckerRequiredSelection: String
            }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "companyName") {
                resolver {
                    fn { _, _, _, _, _ ->
                        nestedParentFieldCount.incrementAndGet()
                        "Airbnb"
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parent") {
                checker {
                    objectSelections("parentFieldCheckerRss", "parentCheckerRequiredSelection")
                    fn { _, objectDataMap ->
                        assertEquals(
                            "checker data",
                            objectDataMap.getValue("parentFieldCheckerRss")
                                .fetchAs<String>("parentCheckerRequiredSelection")
                        )
                        parentCheckerCount.incrementAndGet()
                    }
                }
            }
            field("User" to "parentCheckerRequiredSelection") {
                resolver {
                    fn { _, _, _, _, _ ->
                        parentCheckerRequiredSelectionCount.incrementAndGet()
                        "checker data"
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ company { user { parentCompanyName } } }")
                .assertJson("{data: {company: {user: {parentCompanyName: 'Airbnb'}}}}")
        }

        assertEquals(1, parentCheckerCount.get())
        assertEquals(1, parentCheckerRequiredSelectionCount.get())
        assertEquals(1, nestedParentFieldCount.get())
    }

    @Test
    fun `execution propagates checker failures from real fields selected under parent`() {
        EngineTestModule(
            """
            extend type Query { company: Company }
            type Company { companyName: String, user: User }
            type User { parent: Company @parent, parentCompanyName: String }
            """.trimIndent(),
        ) {
            field("Query" to "company") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Company"), emptyMap())
                    }
                }
            }
            field("Company" to "companyName") {
                resolver {
                    fn { _, _, _, _, _ -> "Airbnb" }
                }
                checker {
                    fn { _, _ ->
                        error("companyName checker failed")
                    }
                }
            }
            field("Company" to "user") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("User"), emptyMap())
                    }
                }
            }
            field("User" to "parentCompanyName") {
                resolver {
                    objectSelections("parent { companyName }")
                    fn { _, obj, _, _, _ ->
                        obj.fetchAs<EngineObjectData>("parent").fetchAs<String>("companyName")
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ company { user { parentCompanyName } } }")

            assertEquals(
                mapOf(
                    "company" to mapOf(
                        "user" to mapOf("parentCompanyName" to null)
                    )
                ),
                result.getData()
            )
            assertEquals(1, result.errors.size)
            assertNotNull(result.errors.first().message)
            assertTrue(result.errors.first().message.contains("companyName checker failed"))
        }
    }
}
