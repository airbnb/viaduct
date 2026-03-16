package viaduct.api.bootstrap.test

import viaduct.api.NodeResolverBase
import viaduct.api.Resolver
import viaduct.api.ResolverBase
import viaduct.api.TenantModule
import viaduct.api.Variable
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.bootstrap.test.grts.Query
import viaduct.api.bootstrap.test.grts.TestBatchNode
import viaduct.api.bootstrap.test.grts.TestNode
import viaduct.api.bootstrap.test.grts.TestType
import viaduct.api.bootstrap.test.grts.TestType_ParameterizedField_Arguments
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.InternalApi

class TestTenantModule : TenantModule {
    override val metadata = mapOf(
        "name" to "TestModule",
    )
}

@OptIn(InternalApi::class)
object TestTypeModernResolvers {
    @ResolverFor("TestType", "aField")
    abstract class AField : ResolverBase<String> {
        open suspend fun resolve(ctx: Context): String = TODO()

        class Context(
            private val innerCtx: FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
        ) : FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite> by innerCtx
    }

    @ResolverFor("TestType", "bIntField")
    abstract class BIntField : ResolverBase<Int> {
        open suspend fun resolve(ctx: Context): Int = TODO()

        class Context(
            private val innerCtx: FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
        ) : FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite> by innerCtx
    }

    @ResolverFor("TestType", "parameterizedField")
    abstract class ParameterizedField : ResolverBase<Boolean> {
        open suspend fun resolve(ctx: Context): Boolean = TODO()

        class Context(
            private val innerCtx: FieldExecutionContext<TestType, Query, TestType_ParameterizedField_Arguments, CompositeOutput.NotComposite>
        ) : FieldExecutionContext<TestType, Query, TestType_ParameterizedField_Arguments, CompositeOutput.NotComposite> by innerCtx
    }

    @ResolverFor("TestType", "dField")
    abstract class DField : ResolverBase<String> {
        open suspend fun resolve(ctx: Context): String = TODO()

        class Context(
            private val innerCtx: FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
        ) : FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite> by innerCtx
    }

    @ResolverFor("TestType", "whenMappingsTest")
    abstract class WhenMappingsTest : ResolverBase<String> {
        open suspend fun resolve(ctx: Context): String = TODO()

        class Context(
            private val innerCtx: FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
        ) : FieldExecutionContext<TestType, Query, Arguments.NoArguments, CompositeOutput.NotComposite> by innerCtx
    }
}

@Resolver
class AFieldResolver : TestTypeModernResolvers.AField() {
    override suspend fun resolve(ctx: Context): String {
        return "aField"
    }
}

@Resolver
class BIntFieldResolver : TestTypeModernResolvers.BIntField() {
    override suspend fun resolve(ctx: Context): Int {
        return 42
    }
}

@Resolver(
    """
        fragment _ on TestType {
            aField @include(if: ${'$'}experiment)
            bIntField
        }
    """,
    variables = [Variable("experiment", fromArgument = "experiment")]
)
class ParameterizedFieldResolver : TestTypeModernResolvers.ParameterizedField() {
    override suspend fun resolve(ctx: Context): Boolean {
        return ctx.arguments.experiment ?: false
    }
}

@Resolver(
    """
        fragment _ on TestType {
            aField @include(if: ${'$'}experiment)
            bIntField
        }
    """
)
class DFieldResolver : TestTypeModernResolvers.DField() {
    override suspend fun resolve(ctx: Context): String {
        return "dField"
    }

    @Variables("experiment: Boolean")
    class Vars : VariablesProvider<Arguments.NoArguments> {
        override suspend fun provide(context: VariablesProviderContext<Arguments.NoArguments>): Map<String, Any> {
            return mapOf(
                "experiment" to true
            )
        }
    }
}

private enum class TestEnum { A, B }

@Resolver
class WhenMappingsTestResolver : TestTypeModernResolvers.WhenMappingsTest() {
    override suspend fun resolve(ctx: Context): String = mkString(TestEnum.A)

    private fun mkString(e: TestEnum): String =
        when (e) {
            TestEnum.A -> "A"
            TestEnum.B -> "B"
        }
}

@OptIn(InternalApi::class)
@NodeResolverFor("TestNode")
abstract class TestNodeResolverBase : NodeResolverBase<TestNode> {
    open suspend fun resolve(ctx: Context): TestNode = TODO()

    class Context(
        private val inner: NodeExecutionContext<TestNode>
    ) : NodeExecutionContext<TestNode> by inner
}

class TestNodeResolver : TestNodeResolverBase() {
    override suspend fun resolve(ctx: Context): TestNode = TODO()
}

@OptIn(InternalApi::class)
@NodeResolverFor("TestBatchNode")
abstract class TestBatchNodeResolverBase : NodeResolverBase<TestBatchNode> {
    open suspend fun batchResolve(ctx: List<Context>): List<TestBatchNode> = TODO()

    class Context(
        private val inner: NodeExecutionContext<TestBatchNode>
    ) : NodeExecutionContext<TestBatchNode> by inner
}

class TestBatchNodeResolver : TestBatchNodeResolverBase() {
    override suspend fun batchResolve(ctx: List<Context>): List<TestBatchNode> = TODO()
}

@OptIn(InternalApi::class)
@NodeResolverFor("MissingNode")
abstract class TestMissingResolverBase : NodeResolverBase<TestNode> {
    open suspend fun resolve(ctx: Context): TestNode = TODO()

    class Context(
        private val inner: NodeExecutionContext<TestNode>
    ) : NodeExecutionContext<TestNode> by inner
}

class TestMissingResolver : TestMissingResolverBase() {
    override suspend fun resolve(ctx: Context): TestNode = TODO()
}
