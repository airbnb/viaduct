@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.syncaccess

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.BarResolvers
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.CompanyResolvers
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.ContainerResolvers
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.OrganizationResolvers
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.UserResolvers
import viaduct.tenant.runtime.execution.syncaccess.resolverbases.WidgetResolvers

class KotlinSyncObjectValueAccessContractTest : SyncObjectValueAccessContractTest() {
    @Resolver
    class Query_WidgetResolver : QueryResolvers.Widget() {
        override suspend fun resolve(ctx: Context): Widget = Widget.Builder(ctx).id(ctx.globalIDFor(Widget.Reflection, "w1")).x(42).build()
    }

    @Resolver
    class Query_CompanyResolver : QueryResolvers.Company() {
        override suspend fun resolve(ctx: Context): Company = Company.Builder(ctx).companyName("Airbnb").build()
    }

    @Resolver
    class Query_OrganizationResolver : QueryResolvers.Organization() {
        override suspend fun resolve(ctx: Context): Organization = Organization.Builder(ctx).name("Engineering").build()
    }

    @Resolver
    class Query_ConfigResolver : QueryResolvers.Config() {
        override suspend fun resolve(ctx: Context): String = "SyncConfig"
    }

    @Resolver
    class Query_MultiplierResolver : QueryResolvers.Multiplier() {
        override suspend fun resolve(ctx: Context): Int = 10
    }

    @Resolver
    class Query_ContainerResolver : QueryResolvers.Container() {
        override suspend fun resolve(ctx: Context): Container = Container.Builder(ctx).build()
    }

    @Resolver(objectValueFragment = "fragment _ on Widget { x }")
    class Widget_XLabelResolver : WidgetResolvers.XLabel() {
        override suspend fun resolve(ctx: Context): String {
            val x = ctx.getObjectValue().getX()
            return "Sync access: x=$x"
        }
    }

    @Resolver(queryValueFragment = "fragment _ on Query { config }")
    class Widget_ConfigLabelResolver : WidgetResolvers.ConfigLabel() {
        override suspend fun resolve(ctx: Context): String {
            val config = ctx.getQueryValue().getConfig()
            return "Sync query access: config=$config"
        }
    }

    @Resolver(
        objectValueFragment = "fragment _ on Widget { x }",
        queryValueFragment = "fragment _ on Query { multiplier }"
    )
    class Widget_CombinedResolver : WidgetResolvers.Combined() {
        override suspend fun resolve(ctx: Context): String {
            val x = ctx.getObjectValue().getX() ?: 0
            val multiplier = ctx.getQueryValue().getMultiplier() ?: 1
            return "Sync combined: ${x * multiplier}"
        }
    }

    @Resolver
    class Container_BarResolver : ContainerResolvers.Bar() {
        override suspend fun resolve(ctx: Context): Bar = Bar.Builder(ctx).value("NestedValue").build()
    }

    @Resolver(objectValueFragment = "fragment _ on Container { bar { value } }")
    class Container_LabelResolver : ContainerResolvers.Label() {
        override suspend fun resolve(ctx: Context): String {
            val barValue = ctx.getObjectValue().getBar()?.getValue()
            return "Sync nested: bar.value=$barValue"
        }
    }

    @Resolver
    class Bar_ValueResolver : BarResolvers.Value() {
        override suspend fun resolve(ctx: Context): String? = "NestedValue"
    }

    @Resolver
    class Company_UserResolver : CompanyResolvers.User() {
        override suspend fun resolve(ctx: Context): User = User.Builder(ctx).build()
    }

    @Resolver
    class Organization_CompanyResolver : OrganizationResolvers.Company() {
        override suspend fun resolve(ctx: Context): Company = Company.Builder(ctx).companyName("Airbnb").build()
    }

    @Resolver(
        objectValueFragment =
            """
            fragment _ on User {
              parent { companyName }
            }
            """
    )
    class User_ParentCompanyNameResolver : UserResolvers.ParentCompanyName() {
        override suspend fun resolve(ctx: Context): String = ctx.getObjectValue().getParent()!!.getCompanyName()
    }

    @Resolver(
        objectValueFragment =
            """
            fragment _ on User {
              parent { parent { name } }
            }
            """
    )
    class User_ParentOrganizationNameResolver : UserResolvers.ParentOrganizationName() {
        override suspend fun resolve(ctx: Context): String = ctx.getObjectValue().getParent()!!.getParent()!!.getName()
    }
}
