package viaduct.x.javaapi.codegen;

import java.io.File;
import java.io.IOException;
import viaduct.codegen.st.STContents;
import viaduct.codegen.st.STUtilsKt;

/**
 * Generator for Java resolver base classes from GraphQL fields with @resolver directive.
 *
 * <p>Generates abstract base classes that tenant developers extend to implement field resolvers.
 * Each resolver class is annotated with @ResolverFor and contains a Context inner class that
 * delegates to FieldExecutionContext.
 */
public final class JavaResolverGenerator {

  private JavaResolverGenerator() {
    // Static utility class
  }

  // Main template - uses pre-formatted type strings to avoid angle bracket escaping issues
  private static final String MAIN_TEMPLATE =
      STUtilsKt.stTemplate(
          """
          package <mdl.packageName>.resolverbases;

          import java.util.List;
          <if(mdl.hasBatchingResolvers)>
          import java.util.IdentityHashMap;
          <endif>
          import java.util.Map;
          import java.util.concurrent.CompletableFuture;
          import viaduct.engine.api.ViaductSchema;
          import viaduct.java.api.annotations.ResolverFor;
          import viaduct.java.api.context.FieldExecutionContext;
          import viaduct.java.api.context.SelectiveFieldExecutionContext;
          import viaduct.java.api.globalid.GlobalID;
          import viaduct.java.api.internal.InternalContext;
          <if(mdl.hasBatchingResolvers)>
          import viaduct.java.api.internal.BaseBatchedFieldResolver;
          <endif>
          <if(mdl.hasUnbatchedResolvers)>
          import viaduct.java.api.internal.BaseUnbatchedFieldResolver;
          <endif>
          import viaduct.java.api.internal.ResolverClassFinder;
          import viaduct.java.api.reflect.Type;
          import viaduct.java.api.resolvers.FieldResolverBase;
          import viaduct.java.api.types.Arguments;
          import viaduct.java.api.types.CompositeOutput;
          import viaduct.java.api.types.NodeCompositeOutput;
          import viaduct.java.api.types.NodeObject;
          import viaduct.service.api.spi.GlobalIDCodec;
          import <mdl.grtPackage>.*;

          /**
           * Generated resolver base classes for <mdl.typeName> type.
           */
          public final class <mdl.typeName>Resolvers {

              private <mdl.typeName>Resolvers() {
                  // Utility class
              }

              <mdl.resolvers:{r |
              @ResolverFor(typeName = "<r.gqlTypeName>", fieldName = "<r.gqlFieldName>", isSelective = <r.selectiveLiteral>, isBatching = <r.batchingLiteral>)
              public abstract static class <r.resolverClassName>
                  implements <r.fieldResolverBaseType><if(r.isBatching)>, BaseBatchedFieldResolver<else>, BaseUnbatchedFieldResolver<endif> {

                  /**
                   * Context for <r.gqlTypeName>.<r.gqlFieldName> resolver.
                   * Provides type-safe access to object value, query value, arguments, and selections.
                   */
                  public static final class Context
                      implements <r.contextBaseType><if(r.isSelective)>, <r.selectiveContextType><endif>, InternalContext {

                      private final <r.fieldExecutionContextType> inner;

                      public Context(<r.fieldExecutionContextType> inner) {
                          this.inner = inner;
                      \\}

                      @Override
                      public <r.objectType> getObjectValue() {
                          return inner.getObjectValue();
                      \\}

                      @Override
                      public <r.queryType> getQueryValue() {
                          return inner.getQueryValue();
                      \\}

                      @Override
                      public <r.argumentsType> getArguments() {
                          return inner.getArguments();
                      \\}

                      <if(r.isSelective)>
                      @Override
                      public Object getSelections() {
                          return ((<r.selectiveContextType>) inner).getSelections();
                      \\}
                      <endif>

                      @Override
                      public \\<T extends NodeCompositeOutput> GlobalID\\<T> globalIDFor(Type\\<T> type, String internalID) {
                          return inner.globalIDFor(type, internalID);
                      \\}

                      @Override
                      public \\<T extends NodeCompositeOutput> String serialize(GlobalID\\<T> globalID) {
                          return inner.serialize(globalID);
                      \\}

                      @Override
                      public Object getRequestContext() {
                          return inner.getRequestContext();
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
                      public \\<T> CompletableFuture\\<T> query(String selections, Map\\<String, Object> variables, Class\\<T> targetClass) {
                          return inner.query(selections, variables, targetClass);
                      \\}

                      @Override
                      public \\<T> CompletableFuture\\<T> mutation(String selections, Map\\<String, Object> variables, Class\\<T> targetClass) {
                          return inner.mutation(selections, variables, targetClass);
                      \\}

                      public CompletableFuture\\<<r.queryType>\\> query(String selections) {
                          return inner.query(selections, java.util.Map.of(), <r.queryType>.class);
                      \\}

                      public CompletableFuture\\<<r.queryType>\\> query(String selections, Map\\<String, Object> variables) {
                          return inner.query(selections, variables, <r.queryType>.class);
                      \\}
                      <if(r.hasMutationType)>
                      public CompletableFuture\\<<r.mutationType>\\> mutation(String selections) {
                          return inner.mutation(selections, java.util.Map.of(), <r.mutationType>.class);
                      \\}

                      public CompletableFuture\\<<r.mutationType>\\> mutation(String selections, Map\\<String, Object> variables) {
                          return inner.mutation(selections, variables, <r.mutationType>.class);
                      \\}
                      <endif>

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
                  \\}

                  <if(!r.isBatching)>
                  /**
                   * Resolves the <r.gqlFieldName> field value for a single parent object.
                   * Override this method to implement single-item resolution.
                   *
                   * @param ctx the execution context
                   * @return a future that completes with the resolved value
                   */
                  public abstract <r.resolveFutureType> resolve(Context ctx);

                  @Override
                  @SuppressWarnings("unchecked")
                  public final CompletableFuture\\<?> invokeFieldResolver(
                      FieldExecutionContext\\<?, ?, ?, ?> context) {
                      return resolve(new Context((<r.fieldExecutionContextType>) context));
                  \\}
                  <endif>
                  <if(r.isBatching)>
                  /**
                   * Resolves the <r.gqlFieldName> field value for a batch of parent objects.
                   * Override this method to implement batch resolution.
                   *
                   * @param contexts the list of execution contexts (one per parent object)
                   * @return a future that completes with a map from Context to resolved value
                   */
                  public abstract <r.batchResolveFutureType> batchResolve(<r.batchResolveContextListType> contexts);

                  @Override
                  @SuppressWarnings("unchecked")
                  public final <r.batchInvokerFutureType> invokeFieldBatchResolver(
                      <r.batchInvokerContextListType> contexts) {
                      <r.batchInvokerWrappedToOriginalMapType> wrappedToOriginal =
                          new IdentityHashMap\\<>();
                      List\\<Context> wrappedContexts =
                          contexts.stream()
                              .map(
                                  context -> {
                                      Context wrapped =
                                          new Context((<r.fieldExecutionContextType>) context);
                                      wrappedToOriginal.put(wrapped, context);
                                      return wrapped;
                                  \\})
                              .toList();

                      return batchResolve(wrappedContexts)
                          .thenCompose(
                              results -> {
                                  <r.batchInvokerResultMapType> translatedResults =
                                      new IdentityHashMap\\<>();
                                  for (var result : results.entrySet()) {
                                      Context wrappedContext = result.getKey();
                                      <r.batchInvokerContextType> originalContext =
                                          wrappedToOriginal.get(wrappedContext);
                                      if (originalContext == null) {
                                          return BaseBatchedFieldResolver.failedForUnknownContext(
                                              wrappedContext);
                                      \\}
                                      translatedResults.put(originalContext, result.getValue());
                                  \\}
                                  return CompletableFuture.completedFuture(translatedResults);
                              \\});
                  \\}
                  <endif>
              \\}
              }; separator="\\n">
          }
          """);

  /**
   * Generates the Java resolvers source code as a string.
   *
   * @param model the resolvers file model
   * @return the generated Java source code
   */
  public static String generate(ResolversFileModel model) {
    return new STContents(MAIN_TEMPLATE, model).toString();
  }

  /**
   * Generates the Java resolvers source code and writes it to a file.
   *
   * <p>Resolver files are written to package subdirectories under the output directory. The package
   * is {tenantPackage}.resolverbases, so the file path will be:
   * {resolverGeneratedDir}/{tenantPackage/path}/resolverbases/{TypeName}Resolvers.java
   *
   * @param model the resolvers file model
   * @param resolverGeneratedDir the output directory
   * @return the file that was written
   * @throws IOException if there's an error writing the file
   */
  public static File generateToFile(ResolversFileModel model, File resolverGeneratedDir)
      throws IOException {
    STContents contents = new STContents(MAIN_TEMPLATE, model);
    // Create package directory: {packageName}.resolverbases
    String fullPackage = model.packageName() + ".resolverbases";
    String packagePath = fullPackage.replace('.', File.separatorChar);
    File packageDir = new File(resolverGeneratedDir, packagePath);
    if (!packageDir.exists() && !packageDir.mkdirs()) {
      throw new IOException("Failed to create directory: " + packageDir);
    }
    File outputFile = new File(packageDir, model.typeName() + "Resolvers.java");
    contents.write(outputFile);
    return outputFile;
  }
}
