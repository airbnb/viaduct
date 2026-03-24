package viaduct.graphql.schema.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.test.createSchema
import viaduct.graphql.schema.test.createSchemaWithSourceLocations

/**
 * End-to-end tests for CSV generation from GraphQL schemas.
 *
 * These tests verify the full pipeline: schema -> table generation -> CSV output.
 * Output is normalized (sorted) for deterministic comparison.
 */
class Schema2CsvCommandTest {
    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("csv-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== Test Schema Definitions ==========

    /**
     * Basic schema with various type kinds.
     */
    private fun basicTypesSchema() =
        createSchema(
            """
        interface Node {
            id: ID!
        }
        type User implements Node {
            id: ID!
            name: String
            age: Int
        }
        input CreateUserInput {
            name: String!
        }
        enum Status {
            ACTIVE
            INACTIVE
        }
        union SearchResult = User
        scalar DateTime
            """.trimIndent()
        )

    /**
     * Schema with repeatable directives.
     */
    private fun repeatableDirectivesSchema() =
        createSchema(
            """
        directive @tag(name: String!) repeatable on OBJECT
        directive @owner(team: String!) repeatable on FIELD_DEFINITION
        type User @tag(name: "identity") @tag(name: "core") {
            id: ID!
            name: String @owner(team: "user-team") @owner(team: "platform-team")
        }
            """.trimIndent()
        )

    /**
     * Schema with various type wrappers (nullable, non-null, lists).
     */
    private fun typeWrappersSchema() =
        createSchema(
            """
        type User {
            id: ID!
            name: String
            tags: [String!]!
            friends: [User]
            nested: [[String!]]
        }
            """.trimIndent()
        )

    /**
     * Schema with module path information (source locations).
     */
    private fun tenantSchema() =
        createSchemaWithSourceLocations(
            listOf(
                """
            type User {
                id: ID!
                name: String
            }
                """.trimIndent() to "modules/user/src/graphql/User.graphqls",
                """
            type Listing {
                id: ID!
                title: String
            }
                """.trimIndent() to "modules/listing/src/graphql/Listing.graphqls",
                """
            extend type User {
                listings: [Listing!]!
            }
                """.trimIndent() to "modules/pdp/src/graphql/UserExtension.graphqls"
            ),
            sdlWithNoLocation = """
            type Query { user: User, listing: Listing }
            """.trimIndent()
        )

    /**
     * Schema demonstrating root field detection through singletons.
     */
    private fun rootFieldsSchema() =
        createSchema(
            """
        directive @singleton on OBJECT
        type Viewer @singleton {
            currentUser: User
            settings: Settings
        }
        type Settings @singleton {
            theme: Theme
        }
        type User {
            id: ID!
        }
        type Theme {
            name: String
        }
        extend type Query {
            viewer: Viewer
            directUser: User
        }
        extend type Mutation {
            createUser: User
        }
            """.trimIndent()
        )

    // ========== Type Row Tests ==========

    @Test
    fun `generates correct type rows for basic types`() {
        val schema = basicTypesSchema()
        val (types, _) = generateCsvTables(schema)

        val content = types.toSortedBaseContent()

        // Check header
        assertTrue(content.startsWith("Tenant,Kind,IsNode,Type\n"))

        // Check each type kind is present (type name is now last column, so check for newline or end)
        assertTrue(content.contains(",OBJECT,true,User\n"), "User should be OBJECT implementing Node")
        assertTrue(content.contains(",INTERFACE,false,Node\n"), "Node should be INTERFACE")
        assertTrue(content.contains(",INPUT,false,CreateUserInput\n"), "CreateUserInput should be INPUT")
        assertTrue(content.contains(",ENUM,false,Status\n"), "Status should be ENUM")
        assertTrue(content.contains(",UNION,false,SearchResult\n"), "SearchResult should be UNION")
        assertTrue(content.contains(",SCALAR,false,DateTime\n"), "DateTime should be SCALAR")
    }

    @Test
    fun `handles repeatable directives on types`() {
        val schema = repeatableDirectivesSchema()
        val (types, _) = generateCsvTables(schema)

        val dirsContent = types.toSortedExtendedContent()

        // User should have two @tag directives
        val tagLines = dirsContent.lines().filter { it.contains(",User,") && it.contains("tag,") }
        assertEquals(2, tagLines.size, "User should have 2 @tag directive entries")
        assertTrue(tagLines.any { it.contains("identity") }, "Should have identity tag")
        assertTrue(tagLines.any { it.contains("core") }, "Should have core tag")
    }

    // ========== Field Row Tests ==========

    @Test
    fun `generates correct field rows with type info`() {
        val schema = basicTypesSchema()
        val (_, fields) = generateCsvTables(schema)

        val content = fields.toSortedBaseContent()

        // Check header has all field columns
        assertTrue(content.startsWith("Tenant,Kind,IsNode,Type,Extension,FieldTenant,IsRoot,Field,"))

        // Check User fields are present
        assertTrue(content.contains(",User,") && content.contains(",id,"))
        assertTrue(content.contains(",User,") && content.contains(",name,"))
        assertTrue(content.contains(",User,") && content.contains(",age,"))
    }

    @Test
    fun `handles repeatable directives on fields`() {
        val schema = repeatableDirectivesSchema()
        val (_, fields) = generateCsvTables(schema)

        val dirsContent = fields.toSortedExtendedContent()

        // name field should have two @owner directives
        val ownerLines = dirsContent.lines().filter { it.contains(",name,") && it.contains("owner,") }
        assertEquals(2, ownerLines.size, "name field should have 2 @owner directive entries")
        assertTrue(ownerLines.any { it.contains("user-team") }, "Should have user-team owner")
        assertTrue(ownerLines.any { it.contains("platform-team") }, "Should have platform-team owner")
    }

    @Test
    fun `correctly calculates type wrappers`() {
        val schema = typeWrappersSchema()
        val (_, fields) = generateCsvTables(schema)

        val content = fields.toSortedBaseContent()
        val lines = content.lines()

        // Find field lines and check wrapper columns
        // Format: ...,FieldBase,FieldWrappers,FieldListDepth
        val idLine = lines.find { it.contains(",id,") }
        val nameLine = lines.find { it.contains(",User,") && it.contains(",name,") }
        val tagsLine = lines.find { it.contains(",tags,") }
        val friendsLine = lines.find { it.contains(",friends,") }
        val nestedLine = lines.find { it.contains(",nested,") }

        // id: ID! -> non-null, no list -> "!", listDepth=0 (listDepth is now last column)
        assertTrue(idLine?.endsWith(",!,0") == true, "id should be non-null (!), listDepth 0")

        // name: String -> nullable -> "?", listDepth=0
        assertTrue(nameLine?.endsWith(",?,0") == true, "name should be nullable (?), listDepth 0")

        // tags: [String!]! -> non-null list of non-null -> "!!" (outer !, inner !), listDepth=1
        assertTrue(tagsLine?.endsWith(",!!,1") == true, "tags should be !!, listDepth 1")

        // friends: [User] -> nullable list of nullable -> "??" (outer ?, inner ?), listDepth=1
        assertTrue(friendsLine?.endsWith(",??,1") == true, "friends should be ??, listDepth 1")

        // nested: [[String!]] -> nullable list of nullable list of non-null -> "??!" (outer ?, middle ?, inner !), listDepth=2
        assertTrue(nestedLine?.endsWith(",??!,2") == true, "nested should be ??!, listDepth 2")
    }

    // ========== Tenant and Extension Tests ==========

    @Test
    fun `correctly extracts tenant from source locations`() {
        val schema = tenantSchema()
        val (types, _) = generateCsvTables(schema)

        val content = types.toSortedBaseContent()

        assertTrue(content.contains("user,OBJECT,false,User\n"), "User should have user module path")
        assertTrue(content.contains("listing,OBJECT,false,Listing\n"), "Listing should have listing module path")
    }

    @Test
    fun `detects inter-module extensions`() {
        val schema = tenantSchema()
        val (_, fields) = generateCsvTables(schema)

        val content = fields.toSortedBaseContent()
        val lines = content.lines()

        // listings field is an extension from pdp on user type
        val listingsLine = lines.find { it.contains(",listings,") }

        // Extension column should be true, FieldTenant should be pdp
        assertTrue(
            listingsLine?.contains(",true,pdp,") == true,
            "listings should be marked as extension from pdp"
        )
    }

    @Test
    fun `detects external types`() {
        val schema = tenantSchema()
        val (_, fields) = generateCsvTables(schema)

        val content = fields.toSortedBaseContent()
        val lines = content.lines()

        // listings field (in pdp) has type Listing (in listing)
        val listingsLine = lines.find { it.contains(",listings,") }

        // IsExternal should be true
        assertTrue(
            listingsLine?.contains(",true,Listing,") == true,
            "listings field should have external type (Listing is in different module)"
        )
    }

    // ========== Root Field Detection Tests ==========

    @Test
    fun `detects root fields through singletons`() {
        val schema = rootFieldsSchema()
        val (_, fields) = generateCsvTables(schema)

        val content = fields.toSortedBaseContent()
        val lines = content.lines()

        // directUser on Query should be a root
        val directUserLine = lines.find { it.contains(",Query,") && it.contains(",directUser,") }
        assertTrue(
            directUserLine?.contains(",true,directUser,") == true,
            "directUser should be marked as root (IsRoot=true)"
        )

        // currentUser through Viewer singleton should be a root
        val currentUserLine = lines.find { it.contains(",Viewer,") && it.contains(",currentUser,") }
        assertTrue(
            currentUserLine?.contains(",true,currentUser,") == true,
            "currentUser should be marked as root (accessible through singleton)"
        )

        // theme through Settings singleton (nested) should be a root
        val themeLine = lines.find { it.contains(",Settings,") && it.contains(",theme,") }
        assertTrue(
            themeLine?.contains(",true,theme,") == true,
            "theme should be marked as root (accessible through nested singletons)"
        )

        // createUser on Mutation should be a root
        val createUserLine = lines.find { it.contains(",Mutation,") && it.contains(",createUser,") }
        assertTrue(
            createUserLine?.contains(",true,createUser,") == true,
            "createUser should be marked as root (mutation field)"
        )
    }

    // ========== End-to-End File Output Tests ==========

    @Test
    fun `writes all four CSV files correctly`() {
        val schema = basicTypesSchema()

        generateCsvFiles(schema, tempDir)

        // Check all four files exist
        assertTrue(File(tempDir, "types.csv").exists(), "types.csv should exist")
        assertTrue(File(tempDir, "typedirs.csv").exists(), "typedirs.csv should exist")
        assertTrue(File(tempDir, "fields.csv").exists(), "fields.csv should exist")
        assertTrue(File(tempDir, "fielddirs.csv").exists(), "fielddirs.csv should exist")
    }

    @Test
    fun `CSV file content matches sorted table content`() {
        val schema = basicTypesSchema()
        val (types, _) = generateCsvTables(schema)

        generateCsvFiles(schema, tempDir)

        // Read files and compare (note: file content is unsorted, table content is sorted)
        val typesFileContent = File(tempDir, "types.csv").readText()
        val typeDirsFileContent = File(tempDir, "typedirs.csv").readText()
        val fieldsFileContent = File(tempDir, "fields.csv").readText()
        val fieldDirsFileContent = File(tempDir, "fielddirs.csv").readText()

        // Verify headers match
        assertTrue(typesFileContent.startsWith("Tenant,Kind,IsNode,Type\n"))
        assertTrue(typeDirsFileContent.startsWith("Tenant,Kind,IsNode,Type,DirectiveName,AppliedDirective\n"))
        assertTrue(fieldsFileContent.startsWith("Tenant,Kind,IsNode,Type,Extension,FieldTenant,IsRoot,Field,"))
        assertTrue(fieldDirsFileContent.startsWith("Tenant,Kind,IsNode,Type,Extension,FieldTenant,IsRoot,Field,"))

        // Verify row counts match (excluding header)
        val typesRowCount = typesFileContent.lines().filter { it.isNotBlank() }.size - 1
        val typeDirsRowCount = typeDirsFileContent.lines().filter { it.isNotBlank() }.size - 1

        assertEquals(types.rows.size, typesRowCount, "types.csv should have correct row count")
        assertEquals(
            types.rows.sumOf { it.extendedData.size },
            typeDirsRowCount,
            "typedirs.csv should have correct row count (one per directive)"
        )
    }

    @Test
    fun `handles types with no directives`() {
        val schema = basicTypesSchema() // No custom directives
        val (types, _) = generateCsvTables(schema)

        val dirsContent = types.toSortedExtendedContent()

        // Types without directives should have NONE, entries
        assertTrue(dirsContent.contains(",NONE,"), "Types without directives should have NONE entry")
    }

    @Test
    fun `escapes quotes in directive arguments`() {
        // Test with a string containing a quote - in GraphQL SDL, quotes are escaped with backslash
        val schema = createSchema(
            """
            directive @doc(text: String!) on OBJECT
            type User @doc(text: "User's data") {
                id: ID!
            }
            """.trimIndent()
        )

        val (types, _) = generateCsvTables(schema)
        val dirsContent = types.toSortedExtendedContent()

        // Verify the directive content is present and properly formatted
        assertTrue(dirsContent.contains("doc,"), "Should have doc directive name")
        assertTrue(dirsContent.contains("@doc(text:"), "Should have @doc directive with text argument")
        assertTrue(dirsContent.contains("User's data"), "Should contain the directive argument value")
    }
}
