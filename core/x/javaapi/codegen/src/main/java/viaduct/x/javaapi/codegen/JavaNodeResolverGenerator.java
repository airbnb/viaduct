package viaduct.x.javaapi.codegen;

import java.io.File;
import java.io.IOException;
import java.util.List;
import viaduct.codegen.st.STContents;
import viaduct.codegen.st.STUtilsKt;

/**
 * Generator for Java node resolver base classes from GraphQL Node types with @resolver directive.
 *
 * <p>Generates a {@code NodeResolvers.java} file containing an abstract base class per Node type.
 * Each base class is annotated with {@code @NodeResolverFor} and contains a {@code Context} inner
 * class delegating to {@code NodeExecutionContext} (or {@code SelectiveNodeExecutionContext} when
 * the resolver is selective).
 */
public final class JavaNodeResolverGenerator {

  private JavaNodeResolverGenerator() {}

  /** File-level model passed to the StringTemplate as {@code mdl}. */
  public record FileModel(
      String tenantPackage, String grtPackage, List<NodeResolverModel> nodeResolvers) {
    public String getTenantPackage() {
      return tenantPackage;
    }

    public String getGrtPackage() {
      return grtPackage;
    }

    public List<NodeResolverModel> getNodeResolvers() {
      return nodeResolvers;
    }

    public boolean getHasBatchingResolvers() {
      return nodeResolvers.stream().anyMatch(NodeResolverModel::isBatching);
    }

    public boolean getHasUnbatchedResolvers() {
      return nodeResolvers.stream().anyMatch(resolver -> !resolver.isBatching());
    }
  }

  private static final String MAIN_TEMPLATE =
      STUtilsKt.stTemplate(
          """
          package <mdl.tenantPackage>.resolverbases;

          <if(mdl.hasBatchingResolvers)>
          import java.util.ArrayList;
          import java.util.LinkedHashMap;
          <endif>
          import java.util.List;
          import java.util.Map;
          import java.util.concurrent.CompletableFuture;
          import viaduct.engine.api.ViaductSchema;
          import viaduct.java.api.annotations.NodeResolverFor;
          import viaduct.java.api.context.NodeExecutionContext;
          import viaduct.java.api.context.SelectiveNodeExecutionContext;
          import viaduct.java.api.documents.MutationFromAnnotation;
          import viaduct.java.api.documents.QueryFromAnnotation;
          import viaduct.java.api.globalid.GlobalID;
          import viaduct.java.api.internal.InternalContext;
          <if(mdl.hasBatchingResolvers)>
          import viaduct.java.api.internal.BaseBatchedNodeResolver;
          <endif>
          <if(mdl.hasUnbatchedResolvers)>
          import viaduct.java.api.internal.BaseUnbatchedNodeResolver;
          <endif>
          import viaduct.java.api.internal.ResolverClassFinder;
          import viaduct.java.api.reflect.RootObjectField;
          import viaduct.java.api.reflect.Type;
          import viaduct.java.api.resolvers.FieldValue;
          import viaduct.java.api.resolvers.NodeResolverBase;
          import viaduct.java.api.types.Arguments;
          import viaduct.java.api.types.GraphQLObject;
          import viaduct.java.api.types.NodeCompositeOutput;
          import viaduct.java.api.types.NodeObject;
          import viaduct.service.api.spi.GlobalIDCodec;
          import <mdl.grtPackage>.*;

          /**
           * Generated node resolver base classes.
           */
          public final class NodeResolvers {

              private NodeResolvers() {}

              <mdl.nodeResolvers:{nr |
              @NodeResolverFor(typeName = "<nr.typeName>", isBatching = <nr.batchingLiteral>, isSelective = <nr.selectiveLiteral>)
              public abstract static class <nr.typeName> implements NodeResolverBase\\<<nr.grtType>\\><if(nr.isBatching)>, BaseBatchedNodeResolver\\<<nr.grtType>\\><else>, BaseUnbatchedNodeResolver<endif> {

                  /**
                   * Context for <nr.typeName> node resolver.
                   */
                  public static final class Context implements <nr.ctxInterface>\\<<nr.grtType>\\>, NodeResolverBase.Context\\<<nr.grtType>\\>, InternalContext {

                      private final <nr.ctxInterface>\\<<nr.grtType>\\> inner;

                      @SuppressWarnings("unchecked")
                      public Context(<nr.ctxInterface>\\<?> inner) {
                          this.inner = (<nr.ctxInterface>\\<<nr.grtType>\\>) inner;
                      \\}

                      @Override
                      public GlobalID\\<<nr.grtType>\\> getId() {
                          return inner.getId();
                      \\}

                      @Override
                      public Object getRequestContext() {
                          return inner.getRequestContext();
                      \\}

                      @Override
                      public \\<T extends NodeCompositeOutput> GlobalID\\<T> globalIDFor(Type\\<T> type, String internalID) {
                          return inner.globalIDFor(type, internalID);
                      \\}

                      @Override
                      public \\<T extends NodeCompositeOutput> String serialize(GlobalID\\<T> globalID) {
                          return inner.serialize(globalID);
                      \\}

                      @Override
                      public \\<T extends NodeObject> String globalIDStringFor(Type\\<T> type, String internalID) {
                          return inner.globalIDStringFor(type, internalID);
                      \\}

                      @Override
                      public \\<T extends NodeCompositeOutput> T nodeRef(GlobalID\\<T> id) {
                          return inner.nodeRef(id);
                      \\}

                      @Override
                      public \\<A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                              RootObjectField\\<?, T, A> field, A arguments) {
                          return inner.rootFieldRef(field, arguments);
                      \\}

                      @Override
                      public \\<T> CompletableFuture\\<T> query(String selections, Map\\<String, Object> variables, Class\\<T> targetClass) {
                          return inner.query(selections, variables, targetClass);
                      \\}

                      @Override
                      public \\<T> CompletableFuture\\<T> mutation(String selections, Map\\<String, Object> variables, Class\\<T> targetClass) {
                          return inner.mutation(selections, variables, targetClass);
                      \\}

                      @Override
                      public \\<T> CompletableFuture\\<T> query(QueryFromAnnotation operation, Map\\<String, Object> variables, Class\\<T> targetClass) {
                          return inner.query(operation, variables, targetClass);
                      \\}

                      @Override
                      public \\<T> CompletableFuture\\<T> mutation(MutationFromAnnotation operation, Map\\<String, Object> variables, Class\\<T> targetClass) {
                          return inner.mutation(operation, variables, targetClass);
                      \\}

                      @Override
                      public ViaductSchema getSchema() {
                          return InternalContext.from(inner).getSchema();
                      \\}

                      @Override
                      public GlobalIDCodec getGlobalIDCodec() {
                          return InternalContext.from(inner).getGlobalIDCodec();
                      \\}

                      @Override
                      public ResolverClassFinder getClassFinder() {
                          return InternalContext.from(inner).getClassFinder();
                      \\}

                      @Override
                      public \\<T extends NodeCompositeOutput> GlobalID\\<T> deserializeGlobalID(String serialized) {
                          return InternalContext.from(inner).deserializeGlobalID(serialized);
                      \\}
                      <if(nr.isSelective)>

                      @Override
                      public Object selections() {
                          return inner.selections();
                      \\}
                      <endif>
                  \\}

                  <if(!nr.isBatching)>
                  public abstract CompletableFuture\\<<nr.grtType>\\> resolve(Context ctx);

                  @Override
                  public final CompletableFuture\\<?> invokeNodeResolver(
                      NodeExecutionContext\\<?> context) {
                      return resolve(new Context((<nr.ctxInterface>\\<?>) context));
                  \\}
                  <endif>
                  <if(nr.isBatching)>
                  public abstract <nr.batchResolveFutureType> batchResolve(List\\<Context> contexts);

                  @Override
                  public final <nr.batchInvokerFutureType> invokeNodeBatchResolver(
                      <nr.batchInvokerContextListType> contexts) {
                      List\\<Context> wrappedContexts = new ArrayList\\<>(contexts.size());
                      for (NodeExecutionContext\\<?> context : contexts) {
                          wrappedContexts.add(new Context((<nr.ctxInterface>\\<?>) context));
                      \\}
                      return batchResolve(wrappedContexts).thenApply(results -> {
                          Map\\<NodeExecutionContext\\<?>, FieldValue\\<<nr.grtType>\\>> unwrappedResults =
                              new LinkedHashMap\\<>();
                          results.forEach(
                              (resultContext, value) ->
                                  unwrappedResults.put(resultContext.inner, value));
                          return unwrappedResults;
                      \\});
                  \\}
                  <endif>
              \\}
              }; separator="\\n">
          }
          """);

  /**
   * Generates the Java NodeResolvers source as a string.
   *
   * @param model the file model containing tenant/grt packages and node resolver models
   * @return the generated Java source code
   */
  public static String generate(FileModel model) {
    return new STContents(MAIN_TEMPLATE, model).toString();
  }

  /**
   * Generates the Java NodeResolvers source code and writes it to a file.
   *
   * @param model the file model
   * @param resolverGeneratedDir the output directory
   * @return the file that was written, or null if there are no node resolvers
   * @throws IOException if there's an error writing the file
   */
  public static File generateToFile(FileModel model, File resolverGeneratedDir) throws IOException {
    if (model.nodeResolvers().isEmpty()) return null;
    STContents contents = new STContents(MAIN_TEMPLATE, model);
    String fullPackage = model.tenantPackage() + ".resolverbases";
    String packagePath = fullPackage.replace('.', File.separatorChar);
    File packageDir = new File(resolverGeneratedDir, packagePath);
    if (!packageDir.exists() && !packageDir.mkdirs()) {
      throw new IOException("Failed to create directory: " + packageDir);
    }
    File outputFile = new File(packageDir, "NodeResolvers.java");
    contents.write(outputFile);
    return outputFile;
  }
}
