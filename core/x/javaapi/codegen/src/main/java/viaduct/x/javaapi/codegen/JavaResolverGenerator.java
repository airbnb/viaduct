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
          import java.util.Map;
          import java.util.concurrent.CompletableFuture;
          import viaduct.java.api.annotations.ResolverFor;
          import viaduct.java.api.context.FieldExecutionContext;
          import viaduct.java.api.globalid.GlobalID;
          import viaduct.java.api.reflect.Type;
          import viaduct.java.api.resolvers.FieldResolverBase;
          import viaduct.java.api.types.Arguments;
          import viaduct.java.api.types.CompositeOutput;
          import viaduct.java.api.types.NodeCompositeOutput;
          import <mdl.packageName>.*;

          /**
           * Generated resolver base classes for <mdl.typeName> type.
           */
          public final class <mdl.typeName>Resolvers {

              private <mdl.typeName>Resolvers() {
                  // Utility class
              }

              <mdl.resolvers:{r |
              @ResolverFor(typeName = "<r.gqlTypeName>", fieldName = "<r.gqlFieldName>")
              public abstract static class <r.resolverClassName>
                  implements <r.fieldResolverBaseType> {

                  /**
                   * Context for <r.gqlTypeName>.<r.gqlFieldName> resolver.
                   * Provides type-safe access to object value, query value, arguments, and selections.
                   */
                  public static class Context
                      implements <r.contextBaseType> {

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

                      @Override
                      public Object getSelections() {
                          return inner.getSelections();
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
                      public Object getRequestContext() {
                          return inner.getRequestContext();
                      \\}

                      @Override
                      public \\<T extends NodeCompositeOutput> T nodeFor(GlobalID\\<T> id) {
                          return inner.nodeFor(id);
                      \\}
                  \\}

                  /**
                   * Resolves the <r.gqlFieldName> field value for a single parent object.
                   * Override this method to implement single-item resolution.
                   *
                   * @param ctx the execution context
                   * @return a future that completes with the resolved value
                   */
                  public abstract <r.resolveFutureType> resolve(Context ctx);
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
