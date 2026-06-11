@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.filebased

import com.google.inject.AbstractModule
import com.google.inject.Module
import javax.inject.Singleton
import viaduct.api.context.VariablesProviderContext
import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variable
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.filebased.resolverbases.ItemResolvers
import viaduct.tenant.runtime.execution.filebased.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.filebased.resolverbases.QueryResolvers

class FileBasedBootstrapFeatureAppTest : FileBasedBootstrapContractTest() {
    override val validateResolverCompleteness = false

    @Resolver
    class ItemNodeResolver : NodeResolvers.Item() {
        override suspend fun resolve(ctx: Context): Item {
            return Item.Builder(ctx).id(ctx.id).name(ctx.id.internalID).build()
        }
    }

    @Resolver("id")
    class Item_LabelResolver : ItemResolvers.Label() {
        override suspend fun resolve(ctx: Context): String {
            return "label:${ctx.getObjectValue().getId().internalID}"
        }
    }

    @Resolver(
        queryValueFragment = "fragment _ on Query { echoWithTag(tag: \$theTag) }",
        variables = [Variable(name = "theTag", fromArgument = "tag")]
    )
    class Item_EchoTagResolver : ItemResolvers.EchoTag() {
        override suspend fun resolve(ctx: Context): String {
            return ctx.getQueryValue().getEchoWithTag()
        }
    }

    @Resolver
    class Query_ItemResolver : QueryResolvers.Item() {
        override suspend fun resolve(ctx: Context) = ctx.nodeRef(ctx.globalIDFor(Item.Reflection, ctx.arguments.id))
    }

    @Resolver
    class Query_EchoWithTagResolver : QueryResolvers.EchoWithTag() {
        override suspend fun resolve(ctx: Context): String {
            return ctx.arguments.tag
        }
    }

    @Resolver(
        queryValueFragment = "fragment _ on Query { echoWithTag(tag: \$myTag) }"
    )
    class Query_TaggedLabelResolver : QueryResolvers.TaggedLabel() {
        override suspend fun resolve(ctx: Context): String {
            return ctx.getQueryValue().getEchoWithTag()
        }

        @Variables("myTag: String!")
        class TagProvider : VariablesProvider<Arguments.NoArguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments.NoArguments>): Map<String, Any?> {
                return mapOf("myTag" to "from-provider")
            }
        }
    }

    override fun guiceModules(): List<Module> =
        listOf(
            object : AbstractModule() {
                override fun configure() {
                    bind(ItemNodeResolver::class.java).`in`(Singleton::class.java)
                    bind(Item_LabelResolver::class.java).`in`(Singleton::class.java)
                    bind(Item_EchoTagResolver::class.java).`in`(Singleton::class.java)
                    bind(Query_ItemResolver::class.java).`in`(Singleton::class.java)
                    bind(Query_EchoWithTagResolver::class.java).`in`(Singleton::class.java)
                    bind(Query_TaggedLabelResolver::class.java).`in`(Singleton::class.java)
                    bind(Query_TaggedLabelResolver.TagProvider::class.java).`in`(Singleton::class.java)
                }
            }
        )
}
