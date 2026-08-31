package viaduct.tenant.codegen.bytecode

import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.ExecutionContext
import viaduct.api.context.RootFieldCall
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockResolverExecutionContext
import viaduct.api.reflect.RootObjectField
import viaduct.api.types.Arguments
import viaduct.api.types.Object as ViaductObject
import viaduct.api.types.Query
import viaduct.engine.api.mocks.createSchema as createEngineSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.createSchema
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
    private val rootFieldDirectives = """
        directive @namespaceType on OBJECT
        directive @resolver on FIELD_DEFINITION
    """.trimIndent()

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

    private class CapturedRootFieldRef(
        val field: RootObjectField<*, *, *>,
        val arguments: Arguments,
    ) : RuntimeException()

    private inner class RootFieldFixture(sdl: String) {
        private val codegenSchema = createSchema("$rootFieldDirectives\n$sdl")
        val classLoader = createBuilder(codegenSchema).addAll(codegenSchema).buildClassLoader()
        private val factoryClass = classLoader.loadClass("test.pkg.ProductFactory")
        private val companion = factoryClass.getField("Companion").get(null)
        val context = object : MockResolverExecutionContext<Query>(
            internalContext = MockInternalContext.create(
                schema = createEngineSchema(sdl),
                grtPackage = "test.pkg",
                classLoader = classLoader,
            ),
        ) {
            override fun <A : Arguments, T : ViaductObject> rootFieldRef(
                field: RootObjectField<*, T, A>,
                arguments: A,
            ): T {
                throw CapturedRootFieldRef(field, arguments)
            }
        }

        fun call(
            fieldName: String,
            configure: ((Any) -> Unit)? = null,
        ): RootFieldCall<*> {
            val method = companion.javaClass.declaredMethods.single { it.name == fieldName }
            val result = if (configure == null) {
                method.invoke(companion)
            } else {
                method.invoke(companion, configure)
            }
            return result as RootFieldCall<*>
        }

        fun capture(call: RootFieldCall<*>): CapturedRootFieldRef =
            assertThrows<CapturedRootFieldRef> {
                call.resolve(context)
            }

        fun capture(
            fieldName: String,
            configure: ((Any) -> Unit)? = null,
        ): CapturedRootFieldRef = capture(call(fieldName, configure))

        fun buildInput(
            typeName: String,
            configure: (Any) -> Unit,
        ): Any {
            val builderClass = classLoader.loadClass("test.pkg.$typeName\$Builder")
            val builder = builderClass.getConstructor(ExecutionContext::class.java).newInstance(context)
            configure(builder)
            return builderClass.getMethod("build").invoke(builder)
        }

        fun enumConstant(
            typeName: String,
            value: String,
        ): Any = classLoader.loadClass("test.pkg.$typeName").enumConstants.single { (it as Enum<*>).name == value }
    }

    private fun Any.property(name: String): Any? = javaClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}").invoke(this)

    @Test
    fun `isQueryType returns true for query root type`() {
        // mkSchema already includes type Query { nop: Int }
        val schema = createSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertTrue(queryType.isQueryType())
        }
    }

    @Test
    fun `isQueryType returns false for non-query type`() {
        val schema = createSchema("type User { name: String }")
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
        val schema = createSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val mutationType = schema.types["Mutation"] as ViaductSchema.Object
        with(builder) {
            assertTrue(mutationType.isMutationType())
        }
    }

    @Test
    fun `isMutationType returns false for non-mutation type`() {
        val schema = createSchema("type User { name: String }")
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
        val schema = createSchema("type User { name: String }")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertFalse(queryType.isSubscriptionType())
        }
    }

    @Test
    fun `initSchemaForTest sets schema correctly`() {
        val schema = createSchema("")
        val builder = createBuilder(schema)

        builder.initSchemaForTest(schema)

        val queryType = schema.types["Query"] as ViaductSchema.Object
        with(builder) {
            assertTrue(queryType.isQueryType())
        }
    }

    @Test
    fun `Query is not mutation or subscription type`() {
        val schema = createSchema("")
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
        val schema = createSchema("")
        val builder = createBuilder(schema)
        builder.initSchemaForTest(schema)

        val mutationType = schema.types["Mutation"] as ViaductSchema.Object
        with(builder) {
            assertFalse(mutationType.isQueryType())
            assertTrue(mutationType.isMutationType())
            assertFalse(mutationType.isSubscriptionType())
        }
    }

    @Test
    fun `root field references expose all arguments through lambda receiver`() {
        val schema = createSchema(
            """
            directive @namespaceType on OBJECT
            directive @resolver on FIELD_DEFINITION
            extend type Query { products: ProductFactory }
            type Product { name: String }
            type ProductFactory @namespaceType {
                create(required: String!, optional: String, enabled: Boolean! = false): Product @resolver
            }
            """.trimIndent()
        )

        val classLoader = createBuilder(schema).addAll(schema).buildClassLoader()
        val factoryClass = classLoader.loadClass("test.pkg.ProductFactory")
        val fieldsClass = classLoader.loadClass("test.pkg.ProductFactory\$Fields")
        val argumentsClass = classLoader.loadClass("test.pkg.ProductFactory\$CreateArguments")
        val callClass = classLoader.loadClass("test.pkg.ProductFactory\$CreateRootFieldCall")
        val queryClass = classLoader.loadClass("test.pkg.Query")

        assertTrue(ObjectBase::class.java.isAssignableFrom(factoryClass))
        assertTrue(Query::class.java.isAssignableFrom(queryClass))
        assertTrue(fieldsClass.declaredMethods.any { RootObjectField::class.java.isAssignableFrom(it.returnType) })
        val companion = factoryClass.getField("Companion").get(null)
        assertNotNull(companion)
        val createMethods = companion.javaClass.declaredMethods.filter { it.name == "create" }
        assertEquals(1, createMethods.size)
        assertEquals("kotlin.jvm.functions.Function1", createMethods.single().parameterTypes.single().name)
        assertTrue(createMethods.all { it.returnType.name == "viaduct.api.context.RootFieldCall" })
        assertTrue(RootFieldCall::class.java.isAssignableFrom(callClass))
        assertEquals(
            setOf("required", "optional", "enabled"),
            argumentsClass.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet()
        )
    }

    @Test
    fun `root field references expose required primitive arguments through lambda receiver`() {
        val schema = createSchema(
            """
            directive @namespaceType on OBJECT
            directive @resolver on FIELD_DEFINITION
            extend type Query { products: ProductFactory }
            type Product { name: String }
            type ProductFactory @namespaceType {
                create(count: Int!): Product @resolver
            }
            """.trimIndent()
        )

        val classLoader = createBuilder(schema).addAll(schema).buildClassLoader()
        val factoryClass = classLoader.loadClass("test.pkg.ProductFactory")
        val argumentsClass = classLoader.loadClass("test.pkg.ProductFactory\$CreateArguments")
        val companion = factoryClass.getField("Companion").get(null)
        val createMethod = companion.javaClass.declaredMethods.single { it.name == "create" }
        val countMethod = argumentsClass.declaredMethods.single { it.name == "count" }

        assertEquals("kotlin.jvm.functions.Function1", createMethod.parameterTypes.single().name)
        assertEquals(listOf(Int::class.javaPrimitiveType), countMethod.parameterTypes.toList())
    }

    @Test
    fun `root field references without arguments reuse one call instance`() {
        val fixture = RootFieldFixture(
            """
            extend type Query { products: ProductFactory }
            type Product { name: String }
            type ProductFactory @namespaceType {
                create: Product @resolver
            }
            """.trimIndent()
        )

        val callClass = fixture.classLoader.loadClass("test.pkg.ProductFactory\$CreateRootFieldCall")
        val firstCall = fixture.call("create")
        val secondCall = fixture.call("create")
        val captured = fixture.capture(firstCall)

        assertSame(callClass.getField("INSTANCE").get(null), firstCall)
        assertSame(firstCall, secondCall)
        assertEquals("create", captured.field.name)
        assertSame(Arguments.NoArguments, captured.arguments)
    }

    @Test
    fun `root field reference forwards required optional and defaulted arguments`() {
        val fixture = RootFieldFixture(
            """
            extend type Query { products: ProductFactory }
            type Product { name: String }
            type ProductFactory @namespaceType {
                create(required: String!, optional: String, enabled: Boolean! = false): Product @resolver
            }
            """.trimIndent()
        )

        val captured = fixture.capture("create") { arguments ->
            arguments.javaClass.getMethod("required", String::class.java).invoke(arguments, "required-value")
            arguments.javaClass.getMethod("optional", String::class.java).invoke(arguments, "optional-value")
        }

        assertEquals("create", captured.field.name)
        assertEquals("required-value", captured.arguments.property("required"))
        assertEquals("optional-value", captured.arguments.property("optional"))
        assertEquals(false, captured.arguments.property("enabled"))
    }

    @Test
    fun `root field reference forwards list and input object arguments`() {
        val fixture = RootFieldFixture(
            """
            extend type Query { products: ProductFactory }
            enum ProductKind { PHYSICAL DIGITAL }
            input ProductInput { name: String! }
            type Product { name: String }
            type ProductFactory @namespaceType {
                create(kinds: [ProductKind!]!, product: ProductInput!): Product @resolver
            }
            """.trimIndent()
        )
        val kind = fixture.enumConstant("ProductKind", "PHYSICAL")
        val product = fixture.buildInput("ProductInput") { builder ->
            builder.javaClass.getMethod("name", String::class.java).invoke(builder, "Desk")
        }

        val captured = fixture.capture("create") { arguments ->
            arguments.javaClass.getMethod("kinds", List::class.java).invoke(arguments, listOf(kind))
            arguments.javaClass.getMethod("product", product.javaClass).invoke(arguments, product)
        }

        val kinds = captured.arguments.property("kinds") as List<*>
        val capturedProduct = captured.arguments.property("product")!!
        assertSame(kind, kinds.single())
        assertEquals("Desk", capturedProduct.property("name"))
    }

    @Test
    fun `root field reference preserves case-sensitive and keyword field names`() {
        val fixture = RootFieldFixture(
            """
            extend type Query { products: ProductFactory }
            type Product { name: String }
            type ProductFactory @namespaceType {
                URL: Product @resolver
                when: Product @resolver
            }
            """.trimIndent()
        )

        assertEquals("URL", fixture.capture("URL").field.name)
        assertEquals("when", fixture.capture("when").field.name)
    }
}
