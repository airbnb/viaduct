package viaduct.tenant.runtime.fixtures

/**
 * Contract test for the @scope directive.
 *
 * Defines the SDL for scope-filtered schemas. Concrete subclasses must provide:
 * - @Resolver inner classes for scope1Value and scope2Value
 * - @Nested JUnit5 inner classes with @BeforeEach that call withSchemaConfiguration()
 *   to register specific SchemaId/scope combinations, and @Test methods that execute
 *   queries against those scoped schemas
 *
 * Note: This contract test is Kotlin-only because @scope and withSchemaConfiguration()
 * are not available in the Java Tenant API.
 *
 * The @Nested test classes cannot be defined here because JUnit5 requires @Nested
 * classes to be actual inner classes of the concrete test class.
 *
 * Extend this class and provide resolver and nested test implementations to verify
 * that a given runtime correctly supports these patterns.
 */
abstract class ScopesContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            |   type TestScope1Object @scope(to: ["SCOPE1"]) {
            |       strValue: String!
            |   }
            |   type TestScope2Object @scope(to: ["SCOPE2"]) {
            |     strValue: String!
            |   }
            |
            |   extend type Query @scope(to: ["SCOPE1"]) {
            |     "Return TestScope1Object with a strValue string"
            |     scope1Value: TestScope1Object @resolver
            |   }
            |
            |   extend type Query @scope(to: ["SCOPE2"]) {
            |     "Return TestScope2Object with a strValue string"
            |     scope2Value: TestScope2Object @resolver
            |   }
            | #END_SCHEMA
        """.trimMargin()
    }
}
