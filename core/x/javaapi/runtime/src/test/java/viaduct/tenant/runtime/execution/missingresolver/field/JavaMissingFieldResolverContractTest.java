package viaduct.tenant.runtime.execution.missingresolver.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import viaduct.api.testing.featureapp.MissingResolverImplementationException;
import viaduct.java.api.annotations.NodeResolverFor;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.runtime.bridge.DefaultResolverClassFinder;
import viaduct.tenant.runtime.execution.missingresolver.field.resolverbases.QueryResolvers;

public class JavaMissingFieldResolverContractTest extends MissingFieldResolverContractTest {

  // Test-only class finder used by the Java-aware completeness validator below. Bootstrapping
  // itself is file-based (inherited from the contract base via the build-time registry).
  private final DefaultResolverClassFinder classFinder =
      new DefaultResolverClassFinder(getClass().getPackageName(), getClass().getPackageName());

  // The Kotlin contract base's onBeforeBuild() validator only inspects Kotlin @ResolverFor
  // bases, so it never finds Java's @Resolver impls. Run a Java-aware equivalent that throws
  // the same MissingResolverImplementationException the contract assertion expects.
  @Override
  protected void onBeforeBuild() {
    List<String> missing = new ArrayList<>();
    Set<Class<?>> fieldBases = classFinder.resolverClassesInPackage();
    for (Class<?> baseClass : fieldBases) {
      ResolverFor a = baseClass.getAnnotation(ResolverFor.class);
      if (a == null) continue;
      // Built-in Query.node / Query.nodes resolvers are wired by the runtime; skip.
      if ("Query".equals(a.typeName())
          && ("node".equals(a.fieldName()) || "nodes".equals(a.fieldName()))) {
        continue;
      }
      if (!hasResolverImpl(baseClass)) {
        missing.add(a.typeName() + "." + a.fieldName());
      }
    }
    Set<Class<?>> nodeBases = classFinder.nodeResolverForClassesInPackage();
    for (Class<?> baseClass : nodeBases) {
      NodeResolverFor a = baseClass.getAnnotation(NodeResolverFor.class);
      if (a == null) continue;
      if (classFinder.getSubTypesOf(baseClass).isEmpty()) {
        missing.add("Node(" + a.typeName() + ")");
      }
    }
    if (!missing.isEmpty()) {
      throw new MissingResolverImplementationException(missing);
    }
  }

  private boolean hasResolverImpl(Class<?> baseClass) {
    for (Class<?> sub : classFinder.getSubTypesOf(baseClass)) {
      if (sub.isAnnotationPresent(Resolver.class)) return true;
    }
    return false;
  }

  // --- Resolvers ---

  @Resolver
  public static class ImplementedResolver extends QueryResolvers.Implemented {
    @Override
    public CompletableFuture<String> resolve(Context ctx) {
      return CompletableFuture.completedFuture("present");
    }
  }
}
