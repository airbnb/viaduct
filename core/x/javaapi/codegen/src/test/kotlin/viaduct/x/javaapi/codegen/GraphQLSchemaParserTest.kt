package viaduct.x.javaapi.codegen

import graphql.schema.idl.SchemaParser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.graphql.utils.Predicates
import viaduct.graphql.utils.toSDL

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphQLSchemaParserTest {
    private val parser = GraphQLSchemaParser()
    private val testSchema by lazy { parser.parse(getTestSchemaReader()) }

    @Test
    fun parsesSchemaFile() {
        val schema = testSchema

        assertNotNull(schema)
        assertFalse(schema.types.isEmpty())
    }

    @Test
    fun extractsEnumsFromSchema() {
        val schema = testSchema

        val enums = parser.extractEnums(schema, "com.example.types")

        enums shouldHaveSize 5

        // Basic enum
        val bookingStatus = enums.single { it.className() == "BookingStatus" }
        assertEquals("com.example.types", bookingStatus.packageName())
        bookingStatus.valueNames() shouldContainExactly listOf("PENDING", "CONFIRMED", "CANCELLED", "COMPLETED")
        // Note: description not extracted from ViaductSchema interface
        assertNull(bookingStatus.description())

        // Basic enum
        val listingType = enums.single { it.className() == "ListingType" }
        assertEquals("com.example.types", listingType.packageName())
        listingType.valueNames() shouldContainExactly listOf("ENTIRE_PLACE", "PRIVATE_ROOM", "SHARED_ROOM", "HOTEL_ROOM")

        // Extended enum - values from base + extensions merged
        val extendableStatus = enums.single { it.className() == "ExtendableStatus" }
        extendableStatus.valueNames() shouldContainExactly listOf("ORIGINAL_VALUE", "EXTENDED_VALUE_1", "EXTENDED_VALUE_2")

        // Enum with Java reserved keywords as values
        val javaReserved = enums.single { it.className() == "JavaReservedKeywords" }
        javaReserved.valueNames() shouldContainExactly listOf("CLASS", "PUBLIC", "PRIVATE", "STATIC", "FINAL", "VOID")

        // Enum with lowercase values
        val lowercase = enums.single { it.className() == "LowercaseEnum" }
        lowercase.valueNames() shouldContainExactly listOf("active", "inactive", "pending")
    }

    @Test
    fun extractsObjectsFromSchema() {
        val schema = testSchema

        val objects = parser.extractObjects(schema, "com.example.types")

        // User, Listing, Booking, PrimitiveListTest, Review, PageInfo, SearchContainer
        objects shouldHaveSize 7

        // User object (includes 5 base fields + 3 resolver fields from extend type)
        val user = objects.single { it.className() == "User" }
        assertEquals("com.example.types", user.packageName())
        user.fields() shouldHaveSize 8

        // Check User fields (order may vary with ViaductSchema)
        user.fields().map { it.name() } shouldContainExactlyInAnyOrder
            setOf("id", "name", "email", "age", "isActive", "profilePicture", "activeBookings", "totalSpent")

        // Listing object with references to other types (7 base + 2 resolver fields)
        val listing = objects.single { it.className() == "Listing" }
        listing.fields() shouldHaveSize 9

        // Check that host field references User type
        val hostField = listing.fields().single { it.name() == "host" }
        assertEquals("User", hostField.javaType())

        // Check that listingType field references enum
        val listingTypeField = listing.fields().single { it.name() == "listingType" }
        assertEquals("ListingType", listingTypeField.javaType())

        // Check list field
        val amenitiesField = listing.fields().single { it.name() == "amenities" }
        assertEquals("List<String>", amenitiesField.javaType())

        // Booking object - now has createdAt and updatedAt from implementing Timestamped
        val booking = objects.single { it.className() == "Booking" }
        booking.fields() shouldHaveSize 9 // 7 original + createdAt + updatedAt
    }

    @Test
    fun extractsAbstractTypedFields() {
        val schema = testSchema

        val objects = parser.extractObjects(schema, "com.example.types")

        val searchContainer = objects.single { it.className() == "SearchContainer" }
        searchContainer.fields() shouldHaveSize 3

        // Interface-typed field
        val topNode = searchContainer.fields().single { it.name() == "topNode" }
        assertTrue(topNode.abstractType())
        assertFalse(topNode.compositeType())
        assertEquals("Node", topNode.baseTypeName())

        // Union-typed field
        val topResult = searchContainer.fields().single { it.name() == "topResult" }
        assertTrue(topResult.abstractType())
        assertFalse(topResult.compositeType())
        assertEquals("SearchResult", topResult.baseTypeName())

        // Union-typed list field
        val allResults = searchContainer.fields().single { it.name() == "allResults" }
        assertTrue(allResults.abstractType())
        assertTrue(allResults.list())
        assertEquals("SearchResult", allResults.baseTypeName())
    }

    @Test
    fun extractsInputsFromSchema() {
        val schema = testSchema

        val inputs = parser.extractInputs(schema, "com.example.types")

        inputs shouldHaveSize 5

        // CreateUserInput
        val createUserInput = inputs.single { it.className() == "CreateUserInput" }
        assertEquals("com.example.types", createUserInput.packageName())
        createUserInput.fields() shouldHaveSize 3
        createUserInput.fields().map { it.name() } shouldContainExactlyInAnyOrder setOf("name", "email", "age")

        // CreateBookingInput
        val createBookingInput = inputs.single { it.className() == "CreateBookingInput" }
        createBookingInput.fields() shouldHaveSize 6

        // SearchFiltersInput - has enum reference and list field
        val searchFiltersInput = inputs.single { it.className() == "SearchFiltersInput" }
        val listingTypeField = searchFiltersInput.fields().single { it.name() == "listingType" }
        assertEquals("ListingType", listingTypeField.javaType())

        val amenitiesField = searchFiltersInput.fields().single { it.name() == "amenities" }
        assertEquals("List<String>", amenitiesField.javaType())

        // ExtendableInput - extended input
        val extendableInput = inputs.single { it.className() == "ExtendableInput" }
        extendableInput.fields() shouldHaveSize 2
        extendableInput.fields().map { it.name() } shouldContainExactlyInAnyOrder setOf("baseField", "extendedField")
    }

    @Test
    fun extractsInterfacesFromSchema() {
        val schema = testSchema

        val interfaces = parser.extractInterfaces(schema, "com.example.types")

        interfaces shouldHaveSize 4

        // Node interface - simple interface
        val node = interfaces.single { it.className() == "Node" }
        assertEquals("com.example.types", node.packageName())
        node.fields() shouldHaveSize 1
        node.extendedInterfaces().shouldBeEmpty()

        val idField = node.fields().single { it.name() == "id" }
        assertEquals("String", idField.javaType())

        // Timestamped interface
        val timestamped = interfaces.single { it.className() == "Timestamped" }
        timestamped.fields() shouldHaveSize 2
        timestamped.extendedInterfaces().shouldBeEmpty()

        // Auditable interface - extends both Node and Timestamped
        val auditable = interfaces.single { it.className() == "Auditable" }
        auditable.extendedInterfaces() shouldContainExactlyInAnyOrder setOf("Node", "Timestamped")
        auditable.fields() shouldHaveSize 4

        // ExtendableInterface - extended interface
        val extendableInterface = interfaces.single { it.className() == "ExtendableInterface" }
        extendableInterface.fields() shouldHaveSize 2
        extendableInterface.fields().map { it.name() } shouldContainExactlyInAnyOrder setOf("baseField", "extendedField")
    }

    @Test
    fun extractsObjectsWithImplementedInterfaces() {
        val schema = testSchema

        val objects = parser.extractObjects(schema, "com.example.types")

        // User implements Node + SearchResult, ExtendableUnion, NodeResult (union membership)
        val user = objects.single { it.className() == "User" }
        user.implementedInterfaces() shouldContainExactlyInAnyOrder
            setOf("Node", "SearchResult", "ExtendableUnion", "NodeResult")

        // Listing implements Node + SearchResult, ExtendableUnion, NodeResult (union membership)
        val listing = objects.single { it.className() == "Listing" }
        listing.implementedInterfaces() shouldContainExactlyInAnyOrder
            setOf("Node", "SearchResult", "ExtendableUnion", "NodeResult")

        // Booking implements Node & Timestamped + SearchResult, ExtendableUnion (union membership)
        val booking = objects.single { it.className() == "Booking" }
        booking.implementedInterfaces() shouldContainExactlyInAnyOrder
            setOf("Node", "Timestamped", "SearchResult", "ExtendableUnion")
    }

    @Test
    fun extractsUnionsFromSchema() {
        val schema = testSchema

        val unions = parser.extractUnions(schema, "com.example.types")

        unions shouldHaveSize 3

        // SearchResult union - basic union
        val searchResult = unions.single { it.className() == "SearchResult" }
        assertEquals("com.example.types", searchResult.packageName())
        searchResult.memberTypes() shouldContainExactlyInAnyOrder setOf("User", "Listing", "Booking")

        // ExtendableUnion - extended union
        val extendableUnion = unions.single { it.className() == "ExtendableUnion" }
        extendableUnion.memberTypes() shouldContainExactlyInAnyOrder setOf("User", "Listing", "Booking")

        // NodeResult - simple union without description in comments
        val nodeResult = unions.single { it.className() == "NodeResult" }
        nodeResult.memberTypes() shouldContainExactlyInAnyOrder setOf("User", "Listing")
    }

    @Test
    fun primitiveTypesInListsAreBoxed() {
        val schema = testSchema

        // Test object type with primitive lists
        val objects = parser.extractObjects(schema, "com.example.types")
        val primitiveListTest = objects.single { it.className() == "PrimitiveListTest" }

        // Verify [Int!]! maps to List<Integer>, not List<int>
        val scoresField = primitiveListTest.fields().single { it.name() == "scores" }
        assertEquals("List<Integer>", scoresField.javaType(), "[Int!]! should map to List<Integer>, not List<int>")

        // Verify [Float!]! maps to List<Double>, not List<double>
        val pricesField = primitiveListTest.fields().single { it.name() == "prices" }
        assertEquals("List<Double>", pricesField.javaType(), "[Float!]! should map to List<Double>, not List<double>")

        // Verify [Boolean!]! maps to List<Boolean>, not List<boolean>
        val flagsField = primitiveListTest.fields().single { it.name() == "flags" }
        assertEquals("List<Boolean>", flagsField.javaType(), "[Boolean!]! should map to List<Boolean>, not List<boolean>")

        // Verify [[Int!]!] maps to List<List<Integer>>
        val matrixField = primitiveListTest.fields().single { it.name() == "matrix" }
        assertEquals("List<List<Integer>>", matrixField.javaType(), "[[Int!]!] should map to List<List<Integer>>")

        // Verify non-null primitives remain primitives (not boxed)
        val countField = primitiveListTest.fields().single { it.name() == "count" }
        assertEquals("int", countField.javaType(), "Int! should map to int primitive, not Integer")

        val rateField = primitiveListTest.fields().single { it.name() == "rate" }
        assertEquals("double", rateField.javaType(), "Float! should map to double primitive, not Double")

        val enabledField = primitiveListTest.fields().single { it.name() == "enabled" }
        assertEquals("boolean", enabledField.javaType(), "Boolean! should map to boolean primitive, not Boolean")

        // Verify nullable primitives are boxed (primitives can't be null in Java)
        val nullableCountField = primitiveListTest.fields().single { it.name() == "nullableCount" }
        assertEquals("Integer", nullableCountField.javaType(), "Int (nullable) should map to Integer")

        val nullableRateField = primitiveListTest.fields().single { it.name() == "nullableRate" }
        assertEquals("Double", nullableRateField.javaType(), "Float (nullable) should map to Double")

        val nullableEnabledField = primitiveListTest.fields().single { it.name() == "nullableEnabled" }
        assertEquals("Boolean", nullableEnabledField.javaType(), "Boolean (nullable) should map to Boolean")

        // Test input type with primitive lists
        val inputs = parser.extractInputs(schema, "com.example.types")
        val primitiveListInput = inputs.single { it.className() == "PrimitiveListInput" }

        val valuesField = primitiveListInput.fields().single { it.name() == "values" }
        assertEquals("List<Integer>", valuesField.javaType(), "Input [Int!]! should map to List<Integer>")

        val ratiosField = primitiveListInput.fields().single { it.name() == "ratios" }
        assertEquals("List<Double>", ratiosField.javaType(), "Input [Float!] should map to List<Double>")

        val optionsField = primitiveListInput.fields().single { it.name() == "options" }
        assertEquals("List<Boolean>", optionsField.javaType(), "Input [Boolean!] should map to List<Boolean>")
    }

    @Test
    fun extractsResolversFromSchema() {
        val schema = testSchema

        val resolversByType = parser.extractResolvers(schema, "com.example.types", "Mutation")

        // Shared default schema contributes Query.node and Query.nodes.
        assertEquals(4, resolversByType.size)
        assertTrue(resolversByType.containsKey("Query"))
        assertTrue(resolversByType.containsKey("User"))
        assertTrue(resolversByType.containsKey("Listing"))
        assertTrue(resolversByType.containsKey("Mutation"))
        resolversByType["Query"]!!.map { it.gqlFieldName() } shouldContainExactlyInAnyOrder setOf("node", "nodes")
    }

    @Test
    fun extractsUserResolvers() {
        val schema = testSchema

        val resolversByType = parser.extractResolvers(schema, "com.example.types", "Mutation")

        val userResolvers = resolversByType["User"]!!
        userResolvers shouldHaveSize 3

        // profilePicture resolver - no arguments, scalar output
        val profilePicture = userResolvers.single { it.gqlFieldName() == "profilePicture" }
        assertEquals("User", profilePicture.gqlTypeName())
        assertEquals("ProfilePicture", profilePicture.resolverClassName())
        assertEquals("String", profilePicture.returnType())
        assertEquals("com.example.types.User", profilePicture.objectType())
        assertEquals("com.example.types.Query", profilePicture.queryType())
        assertEquals("Arguments.None", profilePicture.argumentsType())
        assertFalse(profilePicture.hasArguments())
        assertTrue(profilePicture.isSelective())
        assertFalse(profilePicture.isBatching())

        // activeBookings resolver - list return type
        val activeBookings = userResolvers.single { it.gqlFieldName() == "activeBookings" }
        assertEquals("List<com.example.types.Booking>", activeBookings.returnType())
        assertFalse(activeBookings.isSelective())
        assertFalse(activeBookings.isBatching())

        // totalSpent resolver - non-null Float (boxed for use in CompletableFuture<T>)
        val totalSpent = userResolvers.single { it.gqlFieldName() == "totalSpent" }
        assertEquals("Double", totalSpent.returnType())
    }

    @Test
    fun extractsSelectiveResolversWithLegacyDirectiveArg() {
        val schema =
            parser.parse(
                StringReader(
                    """
          directive @resolver(selective: Boolean! = false) on OBJECT | FIELD_DEFINITION

          type Query {
            user: User @resolver(selective: true)
          }

          type User {
            id: ID!
          }
          """
                )
            )

        val resolversByType = parser.extractResolvers(schema, "com.example.types", null)

        val userResolver = resolversByType["Query"]!![0]
        assertEquals("user", userResolver.gqlFieldName())
        assertTrue(userResolver.isSelective())
    }

    @Test
    fun extractsListingResolversWithArguments() {
        val schema = testSchema

        val resolversByType = parser.extractResolvers(schema, "com.example.types", "Mutation")

        val listingResolvers = resolversByType["Listing"]!!
        listingResolvers shouldHaveSize 2

        // availability resolver - has arguments
        val availability = listingResolvers.single { it.gqlFieldName() == "availability" }
        assertTrue(availability.hasArguments())
        assertEquals("com.example.types.Listing_Availability_Arguments", availability.argumentsType())
        assertEquals("List<String>", availability.returnType())

        // reviews resolver - no arguments
        val reviews = listingResolvers.single { it.gqlFieldName() == "reviews" }
        assertFalse(reviews.hasArguments())
        assertEquals("Arguments.None", reviews.argumentsType())
    }

    @Test
    fun excludesBatchResolveForMutationResolvers() {
        val schema = testSchema

        val resolversByType = parser.extractResolvers(schema, "com.example.types", "Mutation")

        val mutationResolvers = resolversByType["Mutation"]!!
        mutationResolvers shouldHaveSize 1

        val sendNotification = mutationResolvers.single { it.gqlFieldName() == "sendNotification" }
        assertEquals("Mutation", sendNotification.gqlTypeName())
        assertFalse(sendNotification.isBatching(), "Mutation resolvers should not use batching")
        assertTrue(sendNotification.hasArguments())
    }

    @Test
    fun resolverModelPreFormattedTypeStrings() {
        val schema = testSchema

        val resolversByType = parser.extractResolvers(schema, "com.example.types", "Mutation")

        val profilePicture = resolversByType["User"]!!.single { it.gqlFieldName() == "profilePicture" }

        // Test pre-formatted type strings used by the template
        assertEquals(
            "FieldResolverBase<String, com.example.types.User, com.example.types.Query," +
                " Arguments.None, CompositeOutput.None>",
            profilePicture.getFieldResolverBaseType()
        )
        assertEquals(
            "FieldResolverBase.Context<com.example.types.User, com.example.types.Query," +
                " Arguments.None, CompositeOutput.None>",
            profilePicture.getContextBaseType()
        )
        assertEquals(
            "FieldExecutionContext<com.example.types.User, com.example.types.Query," +
                " Arguments.None, CompositeOutput.None>",
            profilePicture.getFieldExecutionContextType()
        )
        assertEquals("CompletableFuture<String>", profilePicture.getResolveFutureType())
        assertEquals(
            "CompletableFuture<Map<Context, String>>",
            profilePicture.getBatchResolveFutureType()
        )
        assertEquals("List<Context>", profilePicture.getBatchResolveContextListType())
    }

    @Test
    fun returnsEmptyMapWhenNoResolversInSchema() {
        // Use a minimal schema with no resolver directives
        val minimalSchema =
            """
      type Query {
        hello: String
      }
      type User {
        id: ID!
        name: String!
      }
      """
        val schema = parser.parse(StringReader(minimalSchema))

        val resolversByType = parser.extractResolvers(schema, "com.example.types", null)

        assertTrue(resolversByType.isEmpty())
    }

    private fun getTestSchemaReader(): StringReader {
        val inputStream =
            checkNotNull(javaClass.classLoader.getResourceAsStream("test-schema.graphqls")) {
                "test-schema.graphqls not found on classpath"
            }
        val sdl =
            InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                withDefaults(SchemaParser().parse(reader.readText())).toSDL(
                    Predicates.alwaysTrue(),
                    Predicates.alwaysTrue()
                )
            }
        return StringReader(sdl)
    }

    private fun withDefaults(registry: graphql.schema.idl.TypeDefinitionRegistry): graphql.schema.idl.TypeDefinitionRegistry {
        DefaultSchemaFactory.addDefaults(
            registry,
            includeNodeDefinition = DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
            includeNodeQueries = DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        )
        return registry
    }
}
