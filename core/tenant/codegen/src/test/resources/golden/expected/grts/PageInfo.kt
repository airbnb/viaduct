@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class PageInfo(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object
{
     fun getHasNextPageOrThrow(alias: String?): kotlin.Boolean = TODO()
     fun getHasNextPageOrThrow(): kotlin.Boolean = TODO()
     fun getHasNextPage(alias: String?): kotlin.Boolean = TODO()
     fun getHasNextPage(): kotlin.Boolean = TODO()
     fun getHasNextPageOrNull(alias: String?): kotlin.Boolean? = TODO()
     fun getHasNextPageOrNull(): kotlin.Boolean? = TODO()

     fun getHasPreviousPageOrThrow(alias: String?): kotlin.Boolean = TODO()
     fun getHasPreviousPageOrThrow(): kotlin.Boolean = TODO()
     fun getHasPreviousPage(alias: String?): kotlin.Boolean = TODO()
     fun getHasPreviousPage(): kotlin.Boolean = TODO()
     fun getHasPreviousPageOrNull(alias: String?): kotlin.Boolean? = TODO()
     fun getHasPreviousPageOrNull(): kotlin.Boolean? = TODO()

     fun getStartCursorOrThrow(alias: String?): kotlin.String? = TODO()
     fun getStartCursorOrThrow(): kotlin.String? = TODO()
     fun getStartCursor(alias: String?): kotlin.String? = TODO()
     fun getStartCursor(): kotlin.String? = TODO()
     fun getStartCursorOrNull(alias: String?): kotlin.String? = TODO()
     fun getStartCursorOrNull(): kotlin.String? = TODO()

     fun getEndCursorOrThrow(alias: String?): kotlin.String? = TODO()
     fun getEndCursorOrThrow(): kotlin.String? = TODO()
     fun getEndCursor(alias: String?): kotlin.String? = TODO()
     fun getEndCursor(): kotlin.String? = TODO()
     fun getEndCursorOrNull(alias: String?): kotlin.String? = TODO()
     fun getEndCursorOrNull(): kotlin.String? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): PageInfo =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<PageInfo> {
        constructor(context: ExecutionContext)
            : super(
                context as InternalContext,
                TODO() as graphql.schema.GraphQLObjectType,
                null
            )

        internal constructor(
            context: InternalContext,
            type: graphql.schema.GraphQLObjectType,
            baseEngineObjectData: EngineObjectData.Sync
        ) : super(context, type, baseEngineObjectData)

                  fun hasNextPage(value: kotlin.Boolean): Builder = TODO()

                  fun hasPreviousPage(value: kotlin.Boolean): Builder = TODO()

                  fun startCursor(value: kotlin.String?): Builder = TODO()

                  fun endCursor(value: kotlin.String?): Builder = TODO()


        final override fun build(): PageInfo = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.PageInfo> {
        override final val name = "PageInfo"
        override final val kcls = viaduct.api.grts.PageInfo::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.PageInfo> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.PageInfo> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.PageInfo.Reflection)

            final val hasNextPage: viaduct.api.reflect.Field<viaduct.api.grts.PageInfo> =
                viaduct.api.internal.FieldImpl("hasNextPage", viaduct.api.grts.PageInfo.Reflection)

            final val hasPreviousPage: viaduct.api.reflect.Field<viaduct.api.grts.PageInfo> =
                viaduct.api.internal.FieldImpl("hasPreviousPage", viaduct.api.grts.PageInfo.Reflection)

            final val startCursor: viaduct.api.reflect.Field<viaduct.api.grts.PageInfo> =
                viaduct.api.internal.FieldImpl("startCursor", viaduct.api.grts.PageInfo.Reflection)

            final val endCursor: viaduct.api.reflect.Field<viaduct.api.grts.PageInfo> =
                viaduct.api.internal.FieldImpl("endCursor", viaduct.api.grts.PageInfo.Reflection)

    }

}