package viaduct.tenant.runtime.execution.idof;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import kotlin.Pair;
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper;
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.runtime.bridge.DefaultResolverClassFinder;
import viaduct.java.runtime.bridge.ModuleBootstrapper;
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder;
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault;
import viaduct.tenant.runtime.execution.idof.resolverbases.NodeResolvers;
import viaduct.tenant.runtime.execution.idof.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.idof.resolverbases.UserResolvers;

public class JavaIdOfContractTest extends IdOfContractTest {

  private final DefaultResolverClassFinder classFinder =
      new DefaultResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  private final ModuleBootstrapper bootstrapper =
      new ModuleBootstrapper(classFinder, CodeInjector.Companion.getNaive());

  @Override
  protected TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper>
      createBootstrapperBuilder() {
    return MockTenantAPIBootstrapperBuilder.INSTANCE.invoke(
        new MockTenantAPIBootstrapper(List.of(bootstrapper)));
  }

  // The Java GRT codegen exposes @idOf-annotated values as plain Strings rather than typed
  // GlobalID<T>, so the engine does not validate the encoding for us. Decode here to recover
  // the underlying type-name (for polymorphic dispatch) and surface invalid encodings as a
  // TenantUsageException, mirroring the behaviour of the typed Kotlin path.
  /**
   * Sneaky-throw helper: Kotlin's {@link TenantUsageException} extends {@code Exception} (checked
   * in Java), but the abstract {@code resolve(Context)} signature does not declare it. Using a
   * generic-erasure trick lets us throw the exception so the framework's tenant-error normalization
   * sees its real type (and the test assertion regex matches the qualified name).
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

  private static String typeOfOrThrow(String globalId) {
    try {
      Pair<String, String> decoded = GlobalIDCodecDefault.INSTANCE.deserialize(globalId);
      return decoded.getFirst();
    } catch (RuntimeException e) {
      sneakyThrow(new TenantUsageException("Invalid GlobalID: " + globalId, e));
      throw new AssertionError("unreachable");
    }
  }

  private static String internalIdOf(String globalId) {
    return GlobalIDCodecDefault.INSTANCE.deserialize(globalId).getSecond();
  }

  // --- Resolvers ---

  @Resolver
  public static class UserNodeResolver extends NodeResolvers.User {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      String internal = ctx.getId().getInternalID();
      String aliceId = ctx.globalIDStringFor(Type.ofClass(User.class), "alice@yahoo.com");
      String bobId = ctx.globalIDStringFor(Type.ofClass(User.class), "bob@hotmail.com");
      User result;
      if ("alice@yahoo.com".equals(internal)) {
        result = User.builder().id(aliceId).name("Alice").cohostID(bobId).build();
      } else if ("bob@hotmail.com".equals(internal)) {
        result = User.builder().id(bobId).name("Bob").cohostID(aliceId).build();
      } else {
        throw new IllegalArgumentException("No User with id=" + internal);
      }
      return CompletableFuture.completedFuture(result);
    }
  }

  /** User.cohost: tests consumption from object field (cohostID is a @idOf scalar). */
  @Resolver(objectValueFragment = "cohostID")
  public static class UserCohostResolver extends UserResolvers.Cohost {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      String cohostId = ctx.getObjectValue().getCohostID();
      GlobalID<User> id = ctx.globalIDFor(Type.ofClass(User.class), internalIdOf(cohostId));
      return CompletableFuture.completedFuture(ctx.nodeRef(id));
    }
  }

  /** Query.userFromInput: tests consumption from input field. */
  @Resolver
  public static class QueryUserFromInputResolver extends QueryResolvers.UserFromInput {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      String inputId = ctx.getArguments().getId().getId();
      GlobalID<User> id = ctx.globalIDFor(Type.ofClass(User.class), internalIdOf(inputId));
      return CompletableFuture.completedFuture(ctx.nodeRef(id));
    }
  }

  /** Query.userFromArgument: tests consumption from field argument. */
  @Resolver
  public static class QueryUserFromArgumentResolver extends QueryResolvers.UserFromArgument {
    @Override
    public CompletableFuture<User> resolve(Context ctx) {
      String id = ctx.getArguments().getId();
      // Validate the encoding (engine does not, since the Java codegen treats @idOf as String).
      typeOfOrThrow(id);
      return CompletableFuture.completedFuture(User.builder().id(id).name("Alice").build());
    }
  }

  /** Query.entityFromID: tests polymorphic aspects of ids. */
  @Resolver
  public static class QueryEntityFromIDResolver extends QueryResolvers.EntityFromID {
    @Override
    public CompletableFuture<Entity> resolve(Context ctx) {
      String id = ctx.getArguments().getId();
      String typeName = typeOfOrThrow(id);
      // Mirror Kotlin: BadType implements Node but not Entity; BadEntityType implements Entity.
      if (!"User".equals(typeName) && !"BadEntityType".equals(typeName)) {
        throw new IllegalArgumentException("Non-entity ID (" + id + ")");
      }
      if (!"User".equals(typeName)) {
        throw new IllegalArgumentException("Can only handle user entities (" + id + ")");
      }
      GlobalID<User> userId = ctx.globalIDFor(Type.ofClass(User.class), internalIdOf(id));
      return CompletableFuture.completedFuture(ctx.nodeRef(userId));
    }
  }
}
