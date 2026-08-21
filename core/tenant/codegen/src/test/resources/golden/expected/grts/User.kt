@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class User(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.grts.Node,viaduct.api.grts.SearchHit,viaduct.api.types.NodeObject
{
          final override fun getIdOrThrow(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.User> = TODO()
          final override fun getIdOrThrow(): viaduct.api.globalid.GlobalID<viaduct.api.grts.User> = TODO()
          final override fun getId(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.User> = TODO()
          final override fun getId(): viaduct.api.globalid.GlobalID<viaduct.api.grts.User> = TODO()
          final override fun getIdOrNull(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.User>? = TODO()
          final override fun getIdOrNull(): viaduct.api.globalid.GlobalID<viaduct.api.grts.User>? = TODO()

     fun getNameOrThrow(alias: String?): kotlin.String = TODO()
     fun getNameOrThrow(): kotlin.String = TODO()
     fun getName(alias: String?): kotlin.String = TODO()
     fun getName(): kotlin.String = TODO()
     fun getNameOrNull(alias: String?): kotlin.String? = TODO()
     fun getNameOrNull(): kotlin.String? = TODO()

     fun getNicknameOrThrow(alias: String?): kotlin.String? = TODO()
     fun getNicknameOrThrow(): kotlin.String? = TODO()
     fun getNickname(alias: String?): kotlin.String? = TODO()
     fun getNickname(): kotlin.String? = TODO()
     fun getNicknameOrNull(alias: String?): kotlin.String? = TODO()
     fun getNicknameOrNull(): kotlin.String? = TODO()

     fun getAgeOrThrow(alias: String?): kotlin.Int? = TODO()
     fun getAgeOrThrow(): kotlin.Int? = TODO()
     fun getAge(alias: String?): kotlin.Int? = TODO()
     fun getAge(): kotlin.Int? = TODO()
     fun getAgeOrNull(alias: String?): kotlin.Int? = TODO()
     fun getAgeOrNull(): kotlin.Int? = TODO()

     fun getActiveOrThrow(alias: String?): kotlin.Boolean = TODO()
     fun getActiveOrThrow(): kotlin.Boolean = TODO()
     fun getActive(alias: String?): kotlin.Boolean = TODO()
     fun getActive(): kotlin.Boolean = TODO()
     fun getActiveOrNull(alias: String?): kotlin.Boolean? = TODO()
     fun getActiveOrNull(): kotlin.Boolean? = TODO()

     fun getFavoriteColorOrThrow(alias: String?): viaduct.api.grts.Color? = TODO()
     fun getFavoriteColorOrThrow(): viaduct.api.grts.Color? = TODO()
     fun getFavoriteColor(alias: String?): viaduct.api.grts.Color? = TODO()
     fun getFavoriteColor(): viaduct.api.grts.Color? = TODO()
     fun getFavoriteColorOrNull(alias: String?): viaduct.api.grts.Color? = TODO()
     fun getFavoriteColorOrNull(): viaduct.api.grts.Color? = TODO()

     fun getScoresOrThrow(alias: String?): kotlin.collections.List<kotlin.Int> = TODO()
     fun getScoresOrThrow(): kotlin.collections.List<kotlin.Int> = TODO()
     fun getScores(alias: String?): kotlin.collections.List<kotlin.Int> = TODO()
     fun getScores(): kotlin.collections.List<kotlin.Int> = TODO()
     fun getScoresOrNull(alias: String?): kotlin.collections.List<kotlin.Int>? = TODO()
     fun getScoresOrNull(): kotlin.collections.List<kotlin.Int>? = TODO()

     fun getLastOrderOrThrow(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()
     fun getLastOrderOrThrow(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()
     fun getLastOrder(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()
     fun getLastOrder(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()
     fun getLastOrderOrNull(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()
     fun getLastOrderOrNull(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()

     fun getInternalStateOrThrow(alias: String?): kotlin.Any? = TODO()
     fun getInternalStateOrThrow(): kotlin.Any? = TODO()
     fun getInternalState(alias: String?): kotlin.Any? = TODO()
     fun getInternalState(): kotlin.Any? = TODO()
     fun getInternalStateOrNull(alias: String?): kotlin.Any? = TODO()
     fun getInternalStateOrNull(): kotlin.Any? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): User =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<User> {
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

                  fun id(value: viaduct.api.globalid.GlobalID<viaduct.api.grts.User>): Builder = TODO()

                  fun name(value: kotlin.String): Builder = TODO()

                  fun nickname(value: kotlin.String?): Builder = TODO()

                  fun age(value: kotlin.Int?): Builder = TODO()

                  fun active(value: kotlin.Boolean): Builder = TODO()

                  fun favoriteColor(value: viaduct.api.grts.Color?): Builder = TODO()

                  fun scores(value: kotlin.collections.List<kotlin.Int>): Builder = TODO()

                  fun lastOrder(value: viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>?): Builder = TODO()

                  fun internalState(value: kotlin.Any?): Builder = TODO()


        final override fun build(): User = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.User> {
        override final val name = "User"
        override final val kcls = viaduct.api.grts.User::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.User> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.User.Reflection)

            final val id: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("id", viaduct.api.grts.User.Reflection)

            final val name: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("name", viaduct.api.grts.User.Reflection)

            final val nickname: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("nickname", viaduct.api.grts.User.Reflection)

            final val age: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("age", viaduct.api.grts.User.Reflection)

            final val active: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("active", viaduct.api.grts.User.Reflection)

            final val favoriteColor: viaduct.api.reflect.CompositeField<viaduct.api.grts.User, viaduct.api.grts.Color> =
                viaduct.api.internal.CompositeFieldImpl("favoriteColor", viaduct.api.grts.User.Reflection, viaduct.api.grts.Color.Reflection)

            final val scores: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("scores", viaduct.api.grts.User.Reflection)

            final val lastOrder: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("lastOrder", viaduct.api.grts.User.Reflection)

            final val internalState: viaduct.api.reflect.Field<viaduct.api.grts.User> =
                viaduct.api.internal.FieldImpl("internalState", viaduct.api.grts.User.Reflection)

    }

}