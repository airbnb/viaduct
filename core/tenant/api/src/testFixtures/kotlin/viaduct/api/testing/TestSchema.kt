package viaduct.api.testing

import java.lang.annotation.Inherited

/**
 * Annotation that declares the GraphQL SDL schema for a contract test.
 *
 * The schema text is extracted at build time (from compiled bytecode) for code generation,
 * and at runtime by [viaduct.tenant.runtime.fixtures.FeatureAppTestBase] to configure the
 * Viaduct service under test.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Inherited
annotation class TestSchema(val value: String)
