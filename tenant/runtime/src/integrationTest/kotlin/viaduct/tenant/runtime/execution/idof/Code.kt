package viaduct.tenant.runtime.execution.idof

import kotlin.reflect.full.isSubclassOf
import viaduct.api.Resolver
import viaduct.api.globalid.GlobalID
import viaduct.tenant.runtime.execution.idof.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.idof.resolverbases.UserResolvers

/**
 * The [GlobalID]<N> type is the GraphQL Representational Type (GRT)
 * for the GraphQL `ID` scalar type.  It is a structured,
 * strongly-typed representation of identifiers for GraphQL `Node`
 * types.  A key property of [GlobalID] is that it follows the
 * subtyping rules for the nodes they represent: if `I` is an
 * interface that implements `Node` and `J` is either a type or
 * another interface that implements `Node` and _also_ implements `I`,
 * then [GlobalID]<J> should be a subtype of [GlobalID]<I>.  (In
 * Viaduct, the Kotlin type [NodeCompositeOutput] is the supertype of
 * all interfaces that implement [Node], and [NodeObject] the
 * supertype of all `Object`s that implement `Node`.)
 *
 * We want to test that we are properly generating [GlobalID] type
 * expressions in all the places we generate them, and also that those
 * type expressions should interoperate.  I should be able to take a
 * `GlobalID` from a field-argument, for example, and pass it to
 * `nodeFor` and get the expected typing.  We also want to test that
 * subtyping is working the way we're expecting it to.
 *
 * The testing here is compile-time testing: this file contains a
 * bunch of code to ensure that the Kotlin type-checkers does what
 * we're hoping with these types.
 *
 * To build this test suite, we considered the following sources (O)
 * and sinks (I) of [GlobalID]s (IO = both source and sink):
 *
 * ExecutionContext functions:
 * * (O) NodeExecutionContext.id
 * * (O) ExecutionContext.globalIDFor
 * * (I) ResolverExecutionContext.nodeFor
 * * (I) ExecutionContext.query with idOf field arguments (future)
 *
 * **GRT fields (all IO)**
 * * Node.id
 * * object fields with @idOf
 * * input fields with @idOf
 * * field-arguments with @idOf
 *
 * Local code using [GlobalID]s explicitly
 * * (IO) local function arguments with @idOf
 * * (IO) GlobalID can be used as type params, e.g., to List and Map
 */
object Code {
    /**
     * Resolver for Query.user field, which we'll use to test
     * `NodeExecutionContext.id` used in various ways.
     *
     * This function also tests uses of
     * `ExecutionContext.globalIDFor`
     */
    @Resolver
    class UserNodeResolver : NodeResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val id = ctx.id

            val idList = mutableListOf<GlobalID<Node>>()
            val idMap = mutableMapOf<GlobalID<Entity>, Any?>()

            // Test consumption of this in various ways
            // Again, just checking that all of this compiles
            ctx.nodeFor(id)
            User.Builder(ctx).id(id) // Node.id
            User.Builder(ctx).cohostID(id) // output field
            Query_UserFromArgument_Arguments.Builder(ctx).id(id) // input field
            HostID.Builder(ctx).id(id) // field argument
            idList.add(id)
            idMap[id] = null
            localConsumption(ctx, id)
            localSupertypeConsumption(id, idList, idMap)
            localSupertypeConsumption(id, listOf<GlobalID<Node>>(id), mapOf<GlobalID<Entity>, Any?>(id to null))

            // Test consumption of ExecutionContext.globalIDFor
            val alice = ctx.globalIDFor(User.Reflection, "alice@yahoo.com")
            ctx.nodeFor(alice)
            User.Builder(ctx).id(alice) // Node.id
            User.Builder(ctx).cohostID(alice) // output field
            Query_UserFromArgument_Arguments.Builder(ctx).id(alice) // input field
            HostID.Builder(ctx).id(alice) // field argument
            idList.add(alice)
            idMap[alice] = null
            localConsumption(ctx, alice)
            localSupertypeConsumption(alice, idList, idMap)
            localSupertypeConsumption(alice, listOf<GlobalID<Node>>(alice), mapOf<GlobalID<Entity>, Any?>(alice to null))

            // For fun
            val bob = ctx.globalIDFor(User.Reflection, "bob@hotmail.com")
            return when (id.internalID) {
                "alice@yahoo.com" -> User.Builder(ctx).id(alice).name("Alice").cohostID(bob).build()
                "bob@hotmail.com" -> User.Builder(ctx).id(bob).name("Bob").cohostID(alice).build()
                else -> throw IllegalArgumentException("No User with id=$id")
            }
        }

        fun localConsumption(
            ctx: Context,
            id: GlobalID<User>,
        ) {
            User.Builder(ctx).id(id) // Node.id
            User.Builder(ctx).cohostID(id) // output field
            Query_UserFromArgument_Arguments.Builder(ctx).id(id) // input field
            HostID.Builder(ctx).id(id) // field argument
        }

        fun localSupertypeConsumption(
            id: GlobalID<Entity>,
            ids: List<GlobalID<Node>>,
            idMap: Map<GlobalID<Entity>, Any?>
        ): List<GlobalID<Node>> {
            return ids + listOf(id) + idMap.keys.map { id }
        }
    }

    /**
     * User.cohost: Tests consumption from object field
     */
    @Resolver(" cohostID ")
    class User_CohostResolver : UserResolvers.Cohost() {
        override suspend fun resolve(ctx: Context): User {
            return ctx.nodeFor(ctx.objectValue.getCohostID()!!)
        }
    }

    /**
     * Query.userFromInput: Tests consumption from input field
     */
    @Resolver
    class Query_UserFromInputResolver : QueryResolvers.UserFromInput() {
        override suspend fun resolve(ctx: Context): User {
            return ctx.nodeFor(ctx.arguments.id!!.id)
        }
    }

    /**
     * Query.userFromArgument: Tests consumption from field argument
     */
    @Resolver
    class Query_UserFromArgumnetResolver : QueryResolvers.UserFromArgument() {
        override suspend fun resolve(ctx: Context): User {
            return User.Builder(ctx)
                .id(ctx.arguments.id)
                .name("Alice")
                .build()
        }
    }

    /**
     * Query.userFromArgument: Tests polymorphic aspects of ids
     */
    @Resolver
    class Query_EntityFromIDResolver : QueryResolvers.EntityFromID() {
        override suspend fun resolve(ctx: Context): Entity {
            val id = ctx.arguments.id
            if (!id.type.kcls.isSubclassOf(Entity.Reflection.kcls)) throw IllegalArgumentException("Non-entity ID ($id)")
            if (id.type != User.Reflection) throw IllegalArgumentException("Can only handle user entities ($id)")
            @Suppress("UNCHECKED_CAST")
            // TODO: relax bounds of nodeFor (https://app.asana.com/1/150975571430/task/1211504668132957)
            return ctx.nodeFor(id as GlobalID<User>)
        }
    }
}
