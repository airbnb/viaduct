package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeBatchResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.validation.Validator

class ExecutorValidatorTest {
    private val moduleBootstrap = MockTenantModuleBootstrapper(
        fieldResolverExecutors = listOf(
            "Foo" to "field" to MockFieldUnbatchedResolverExecutor(
                RequiredSelectionSet(SelectionsParser.parse("Foo", "y"), emptyList(), false),
                resolverId = "Foo.field",
            )
        ),
        nodeResolverExecutors = listOf(
            "Foo" to MockNodeBatchResolverExecutor("Foo")
        ),
        schema = MockSchema.minimal,
    )

    private fun test(
        bootstrappers: List<TenantModuleBootstrapper> = listOf(moduleBootstrap),
        checkerExecutorFactory: CheckerExecutorFactory = MockCheckerExecutorFactory(),
        nodeResolverValidator: Validator<NodeResolverExecutorValidationCtx> = Validator.Unvalidated,
        resolverExecutorValidator: Validator<FieldResolverExecutorValidationCtx> = Validator.Unvalidated,
        requiredSelectionSetValidator: Validator<RequiredSelectionsValidationCtx> = Validator.Unvalidated,
        checkerExecutorValidator: Validator<CheckerExecutorValidationCtx> = Validator.Unvalidated
    ) {
        val validator = ExecutorValidator(nodeResolverValidator, resolverExecutorValidator, requiredSelectionSetValidator, checkerExecutorValidator)
        DispatcherRegistryFactory(
            MockTenantAPIBootstrapper(bootstrappers),
            validator,
            checkerExecutorFactory
        ).create(MockSchema.mk("type Foo { field: Int }"))
    }

    @Test
    fun `passes on validator success`() {
        assertDoesNotThrow {
            test()
        }
    }

    @Test
    fun `fails on node resolver validator failure`() {
        assertThrows<IllegalArgumentException> {
            test(nodeResolverValidator = Validator.Invalid)
        }
    }

    @Test
    fun `fails on resolverExecutor validator failure`() {
        assertThrows<IllegalArgumentException> {
            test(resolverExecutorValidator = Validator.Invalid)
        }
    }

    @Test
    fun `fails on requiredSelectionSet validator failure for resolver`() {
        assertThrows<IllegalArgumentException> {
            test(requiredSelectionSetValidator = Validator.Invalid)
        }
    }

    @Test
    fun `fails on requiredSelectionSet validator failure for field checker`() {
        val validator = ExecutorValidator(Validator.Unvalidated, Validator.Invalid, Validator.Unvalidated, Validator.Unvalidated)
        DispatcherRegistryFactory(
            MockTenantAPIBootstrapper(),
            validator,
            MockCheckerExecutorFactory(
                mapOf(
                    "Foo" to "field" to MockCheckerExecutor(
                        mapOf("rss" to RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), true))
                    )
                )
            )
        ).create(MockSchema.minimal)
    }

    @Test
    fun `fails on requiredSelectionSet validator failure for type checker`() {
        val validator = ExecutorValidator(Validator.Unvalidated, Validator.Invalid, Validator.Unvalidated, Validator.Unvalidated)
        DispatcherRegistryFactory(
            MockTenantAPIBootstrapper(),
            validator,
            MockCheckerExecutorFactory(
                null,
                mapOf(
                    "Foo" to MockCheckerExecutor(
                        mapOf("rss" to RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), true))
                    )
                )
            )
        ).create(MockSchema.minimal)
    }

    @Test
    fun `fails on field checkerExecutor validator failure`() {
        assertThrows<IllegalArgumentException> {
            test(
                checkerExecutorFactory = MockCheckerExecutorFactory(
                    mapOf("Foo" to "field" to MockCheckerExecutor())
                ),
                checkerExecutorValidator = Validator.Invalid
            )
        }
    }

    @Test
    fun `fails on type checkerExecutor validator failure`() {
        assertThrows<IllegalArgumentException> {
            test(
                checkerExecutorFactory = MockCheckerExecutorFactory(
                    mapOf(),
                    mapOf("Foo" to MockCheckerExecutor())
                ),
                checkerExecutorValidator = Validator.Invalid
            )
        }
    }
}
