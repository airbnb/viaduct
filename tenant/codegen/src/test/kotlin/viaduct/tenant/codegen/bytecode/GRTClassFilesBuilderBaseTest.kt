package viaduct.tenant.codegen.bytecode

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.mkSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.utils.timer.Timer

/**
 * Tests for GRTClassFilesBuilderBase methods that check root types.
 *
 * Note: mkSchema from viaduct.graphql.schema.test.Utils prepends a MIN_SCHEMA that includes:
 * - schema { query: Query, mutation: Mutation }
 * - type Query { nop: Int }
 * - type Mutation { nop: Int }
 * - scalar Long
 * - scalar Short
 *
 * So tests should not redefine Query/Mutation and can use the existing ones.
 */
class GRTClassFilesBuilderBaseTest {
    private fun createBuilder(schema: ViaductSchema): GRTClassFilesBuilder {
        val args = CodeGenArgs(
            moduleName = null,
            pkgForGeneratedClasses = "test.pkg",
            includeIneligibleTypesForTestingOnly = false,
            excludeCrossModuleFields = false,
            javaTargetVersion = null,
            workerNumber = 0,
            workerCount = 1,
            timer = Timer(),
            baseTypeMapper = ViaductBaseTypeMapper(schema),
        )
        return GRTClassFilesBuilder(args)
    }

    @Test
    fun `isQueryType returns true for query root type`() {
        // mkSchema already includes type Query { nop: Int }
        val schema = mkSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertTrue(queryType.isQueryType())
        }
    }

    @Test
    fun `isQueryType returns false for non-query type`() {
        val schema = mkSchema("type User { name: String }")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val userType = schema.types["User"] as ViaductSchema.Object
        with(builder) {
            assertFalse(userType.isQueryType())
        }
    }

    @Test
    fun `isMutationType returns true for mutation root type`() {
        // mkSchema already includes type Mutation { nop: Int }
        val schema = mkSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val mutationType = schema.types["Mutation"] as ViaductSchema.Object
        with(builder) {
            assertTrue(mutationType.isMutationType())
        }
    }

    @Test
    fun `isMutationType returns false for non-mutation type`() {
        val schema = mkSchema("type User { name: String }")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val userType = schema.types["User"] as ViaductSchema.Object
        with(builder) {
            assertFalse(userType.isMutationType())
        }
    }

    @Test
    fun `isSubscriptionType returns false when no subscription defined`() {
        // mkSchema includes Query and Mutation but no Subscription
        val schema = mkSchema("type User { name: String }")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertFalse(queryType.isSubscriptionType())
        }
    }

    @Test
    fun `isRootType returns true for query type`() {
        val schema = mkSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertTrue(queryType.isRootType())
        }
    }

    @Test
    fun `isRootType returns true for mutation type`() {
        val schema = mkSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val mutationType = schema.types["Mutation"] as ViaductSchema.Object
        with(builder) {
            assertTrue(mutationType.isRootType())
        }
    }

    @Test
    fun `isRootType returns false for non-root types`() {
        val schema = mkSchema("type User { name: String }")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val userType = schema.types["User"] as ViaductSchema.Object
        with(builder) {
            assertFalse(userType.isRootType())
        }
    }

    @Test
    fun `initSchemaForTest sets schema correctly`() {
        val schema = mkSchema("")
        val builder = createBuilder(schema)

        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertTrue(queryType.isQueryType())
        }
    }

    @Test
    fun `Query is not mutation or subscription type`() {
        val schema = mkSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertTrue(queryType.isQueryType())
            assertFalse(queryType.isMutationType())
            assertFalse(queryType.isSubscriptionType())
        }
    }

    @Test
    fun `Mutation is not query or subscription type`() {
        val schema = mkSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val mutationType = schema.types["Mutation"] as ViaductSchema.Object
        with(builder) {
            assertFalse(mutationType.isQueryType())
            assertTrue(mutationType.isMutationType())
            assertFalse(mutationType.isSubscriptionType())
        }
    }
}
