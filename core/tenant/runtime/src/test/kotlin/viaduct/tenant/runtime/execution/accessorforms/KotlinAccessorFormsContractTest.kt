package viaduct.tenant.runtime.execution.accessorforms

import graphql.schema.GraphQLObjectType
import kotlin.coroutines.cancellation.CancellationException
import viaduct.api.internal.InternalContext
import viaduct.api.resolver.Resolver
import viaduct.engine.api.EngineObjectData
import viaduct.errors.ErroneousFieldException
import viaduct.errors.FieldError
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.tenant.runtime.execution.accessorforms.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.accessorforms.resolverbases.WidgetResolvers

private fun joinReads(vararg reads: String?) = reads.joinToString("|") { it ?: "null" }

private class ContractEngineData(
    override val type: GraphQLObjectType,
    private val values: Map<String, Any?>,
) : EngineObjectData.Sync {
    override fun get(selection: String): Any? = values.getValue(selection).also { if (it is Exception) throw it }

    override fun getOrNull(selection: String): Any? = get(selection)

    override fun isPresent(selection: String): Boolean = values.containsKey(selection)

    override fun getSelections(): Iterable<String> = values.keys

    override suspend fun fetch(selection: String): Any? = get(selection)

    override suspend fun fetchOrNull(selection: String): Any? = get(selection)

    override suspend fun fetchSelections(): Iterable<String> = values.keys
}

private fun classify(block: () -> Any?): String =
    try {
        if (block() == null) "null" else "value"
    } catch (e: Exception) {
        e::class.simpleName ?: e.javaClass.simpleName
    }

private fun classify(
    label: String,
    vararg blocks: () -> Any?
): String = "$label=${blocks.joinToString(",") { classify(it) }}"

class KotlinAccessorFormsContractTest : AccessorFormsContractTest() {
    @Resolver
    class Query_WidgetResolver : QueryResolvers.Widget() {
        override suspend fun resolve(ctx: Context) = Widget.Builder(ctx).build()
    }

    @Resolver
    class Widget_NameResolver : WidgetResolvers.Name() {
        override suspend fun resolve(ctx: Context) = "widget"
    }

    @Resolver
    class Widget_NicknameResolver : WidgetResolvers.Nickname() {
        override suspend fun resolve(ctx: Context): String? = null
    }

    @Resolver
    class Widget_BrokenResolver : WidgetResolvers.Broken() {
        override suspend fun resolve(ctx: Context): String = throw IllegalStateException("boom")
    }

    @Resolver("nickname")
    class Widget_NullReadsResolver : WidgetResolvers.NullReads() {
        override suspend fun resolve(ctx: Context): String {
            val widget = ctx.getObjectValue()
            return joinReads(widget.getNicknameOrThrow(), widget.getNickname(), widget.getNicknameOrNull())
        }
    }

    @Resolver("name")
    class Widget_UnselectedStrictReadResolver : WidgetResolvers.UnselectedStrictRead() {
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().getNicknameOrThrow()
    }

    @Resolver("name")
    class Widget_UnselectedSoftReadResolver : WidgetResolvers.UnselectedSoftRead() {
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().getNicknameOrNull()
    }

    @Resolver("alias: name")
    class Widget_AliasedReadsResolver : WidgetResolvers.AliasedReads() {
        override suspend fun resolve(ctx: Context): String {
            val widget = ctx.getObjectValue()
            return joinReads(widget.getNameOrThrow("alias"), widget.getName("alias"), widget.getNameOrNull("alias"))
        }
    }

    @Resolver("alias: name")
    class Widget_UnaliasedReadResolver : WidgetResolvers.UnaliasedRead() {
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().getNameOrThrow()
    }

    @Resolver
    class Widget_BuilderUnsetReadResolver : WidgetResolvers.BuilderUnsetRead() {
        override suspend fun resolve(ctx: Context) = Widget.Builder(ctx).name("built").build().getNicknameOrNull()
    }

    @Resolver("broken")
    class Widget_FailureReadsResolver : WidgetResolvers.FailureReads() {
        override suspend fun resolve(ctx: Context): String {
            fun widget(value: Exception) = rawWidget(ctx, "name", value)

            val resolver = ctx.getObjectValue()
            val stored = widget(ErroneousFieldException(listOf(FieldError("boom"))))
            val wrapped = widget(
                FrameworkException("boom", TenantResolverException(IllegalStateException(), "Widget.name"))
            )
            val framework = widget(FrameworkException("boom"))
            val cancellation = widget(CancellationException("boom"))
            val wrappedCancellation = widget(
                FrameworkException(
                    "boom",
                    TenantResolverException(CancellationException("boom"), "Widget.name"),
                )
            )
            return listOf(
                classify("resolver", resolver::getBrokenOrThrow, resolver::getBroken, resolver::getBrokenOrNull),
                classify("stored", stored::getNameOrThrow, stored::getName, stored::getNameOrNull),
                classify("wrapped", wrapped::getNameOrThrow, wrapped::getName, wrapped::getNameOrNull),
                classify("framework", framework::getNameOrThrow, framework::getName, framework::getNameOrNull),
                classify(
                    "cancellation",
                    cancellation::getNameOrThrow,
                    cancellation::getName,
                    cancellation::getNameOrNull,
                ),
                classify(
                    "wrappedCancellation",
                    wrappedCancellation::getNameOrThrow,
                    wrappedCancellation::getName,
                    wrappedCancellation::getNameOrNull,
                ),
            ).joinToString(";")
        }
    }

    @Resolver
    class Widget_InvalidValueReadsResolver : WidgetResolvers.InvalidValueReads() {
        override suspend fun resolve(ctx: Context): String {
            val nonNull = rawWidget(ctx, "requiredName", null)
            val listElement = rawWidget(ctx, "strictTags", listOf(null))
            val list = rawWidget(ctx, "tags", "not-a-list")
            val objectValue = rawWidget(ctx, "child", "not-an-object")
            val otherType = ctx.schema.schema.getObjectType("Other")
            val other = ContractEngineData(otherType, mapOf("value" to "other"))
            val concreteType = rawWidget(ctx, "child", other)
            val interfaceType = rawWidget(ctx, "abstractChild", other)
            val objectListElement = rawWidget(ctx, "children", listOf(other))
            return listOf(
                classify(
                    "nonNull",
                    nonNull::getRequiredNameOrThrow,
                    nonNull::getRequiredName,
                    nonNull::getRequiredNameOrNull,
                ),
                classify(
                    "listElement",
                    listElement::getStrictTagsOrThrow,
                    listElement::getStrictTags,
                    listElement::getStrictTagsOrNull,
                ),
                classify("list", list::getTagsOrThrow, list::getTags, list::getTagsOrNull),
                classify(
                    "object",
                    objectValue::getChildOrThrow,
                    objectValue::getChild,
                    objectValue::getChildOrNull,
                ),
                classify(
                    "concreteType",
                    concreteType::getChildOrThrow,
                    concreteType::getChild,
                    concreteType::getChildOrNull,
                ),
                classify(
                    "interfaceType",
                    interfaceType::getAbstractChildOrThrow,
                    interfaceType::getAbstractChild,
                    interfaceType::getAbstractChildOrNull,
                ),
                classify(
                    "objectListElement",
                    objectListElement::getChildrenOrThrow,
                    objectListElement::getChildren,
                    objectListElement::getChildrenOrNull,
                ),
            ).joinToString(";")
        }
    }

    companion object {
        private fun rawWidget(
            context: InternalContext,
            field: String,
            value: Any?,
        ): Widget {
            val type = context.schema.schema.getObjectType("Widget")
            return Widget(context, ContractEngineData(type, mapOf(field to value)))
        }
    }
}
