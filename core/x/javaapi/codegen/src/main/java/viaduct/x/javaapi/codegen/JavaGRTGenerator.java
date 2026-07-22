package viaduct.x.javaapi.codegen;

import java.io.File;
import java.io.IOException;
import viaduct.codegen.st.STContents;
import viaduct.codegen.st.STUtilsKt;

/**
 * Combined generator for all Java GRT (GraphQL Representational Types) source files. Contains all
 * templates and generation logic in one place.
 *
 * <p>Each GraphQL type has a corresponding inner generator class:
 *
 * <ul>
 *   <li>{@link EnumGenerator} - generates Java enums from GraphQL enums
 *   <li>{@link ObjectGenerator} - generates Java classes from GraphQL object types
 *   <li>{@link InputGenerator} - generates Java classes from GraphQL input types
 *   <li>{@link InterfaceGenerator} - generates Java interfaces from GraphQL interface types
 *   <li>{@link UnionGenerator} - generates Java marker interfaces from GraphQL union types
 * </ul>
 */
public final class JavaGRTGenerator {

  private JavaGRTGenerator() {
    // Static utility class
  }

  /**
   * Writes generated content to a file in the appropriate package directory.
   *
   * @param content the STContents to write
   * @param packageName the Java package name
   * @param className the class/interface name
   * @param outputDir the base output directory
   * @return the file that was written
   * @throws IOException if there's an error writing the file
   */
  private static File writeToFile(
      STContents content, String packageName, String className, File outputDir) throws IOException {
    String packagePath = packageName.replace('.', File.separatorChar);
    File packageDir = new File(outputDir, packagePath);
    if (!packageDir.exists() && !packageDir.mkdirs()) {
      throw new IOException("Failed to create directory: " + packageDir);
    }

    File outputFile = new File(packageDir, className + ".java");
    content.write(outputFile);
    return outputFile;
  }

  /** Generator for Java enums from GraphQL enum types. */
  public static final class EnumGenerator {

    private static final String TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import viaduct.java.api.types.GraphQLEnum;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public enum <mdl.className> implements GraphQLEnum {
                <mdl.valueNames: {valueName | <valueName>}; separator=",
            ">
            }
            """);

    private EnumGenerator() {}

    /**
     * Generates the Java enum source code as a string.
     *
     * @param model the enum model
     * @return the generated Java source code
     */
    public static String generate(EnumModel model) {
      return new STContents(TEMPLATE, model).toString();
    }

    /**
     * Generates the Java enum source code and writes it to a file.
     *
     * @param model the enum model
     * @param outputDir the output directory
     * @return the file that was written
     * @throws IOException if there's an error writing the file
     */
    public static File generateToFile(EnumModel model, File outputDir) throws IOException {
      STContents contents = new STContents(TEMPLATE, model);
      return writeToFile(contents, model.packageName(), model.className(), outputDir);
    }
  }

  /** Generator for Java classes from GraphQL object types. */
  public static final class ObjectGenerator {

    private static final String TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import viaduct.engine.api.EngineObjectData;
            import viaduct.engine.api.NodeReference;
            import viaduct.java.api.context.ExecutionContext;
            import viaduct.java.api.globalid.GlobalID;
            import viaduct.java.api.internal.InternalContext;
            import viaduct.java.api.internal.NodeObjectBase;
            import viaduct.java.api.internal.ObjectBase;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.OffsetTime;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public class <mdl.className> extends <if(mdl.isNodeType)>NodeObjectBase<else>ObjectBase<endif><if(mdl.hasImplementsClause)> implements <mdl.implementsClause><endif> {

                public <mdl.className>(InternalContext context, EngineObjectData.Sync data) {
                    super(context, data);
                }

                private <mdl.className>(InternalContext context, Map\\<String, Object> data) {
                    super(context, data);
                }
                <if(mdl.isNodeType)>

                public <mdl.className>(InternalContext context, NodeReference nodeReference) {
                    super(context, nodeReference);
                \\}
                <endif>

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    <if(f.globalIDList)>return fetchGlobalIDList("<f.name>");<elseif(f.globalIDType)>return fetchGlobalID("<f.name>");<elseif(f.abstractList)>return fetchAbstractObjectList("<f.name>", <f.baseTypeName>.class);<elseif(f.abstractType)>return fetchAbstractObject("<f.name>", <f.baseTypeName>.class);<elseif(f.compositeList)>return fetchObjectList("<f.name>", <f.baseTypeName>::new);<elseif(f.compositeType)>return fetchObject("<f.name>", <f.baseTypeName>::new);<elseif(f.enumList)>return fetchEnumList("<f.name>", <f.baseTypeName>.class);<elseif(f.enumType)>return fetchEnum("<f.name>", <f.baseTypeName>.class);<elseif(f.temporalScalarList)>return fetchScalarList("<f.name>", "<f.scalarCoercionHint>");<elseif(f.temporalScalar)>return fetchScalar("<f.name>", "<f.scalarCoercionHint>");<elseif(f.scalarList)>return fetchScalarList("<f.name>");<else>return fetchScalar("<f.name>");<endif>
                \\}
                }; separator="
            ">

                public static Builder builder(ExecutionContext context) {
                    return new Builder(InternalContext.from(context));
                }

                public static class Builder {
                    private final InternalContext __context;
                    private final Map\\<String, Object> data = new LinkedHashMap\\<>();

                    private Builder(InternalContext __context) {
                        this.__context = __context;
                    }

                    <mdl.fields: {f |
                    public Builder <f.safeName>(<f.builderType> <f.safeName>) {
                        <if(f.globalIDBuilderSerialize)>data.put("<f.name>", <f.safeName> == null ? null : __context.getGlobalIDCodec().serialize(<f.safeName>.getType().getName(), <f.safeName>.getInternalID()));
                        <elseif(f.globalIDListBuilderSerialize)>data.put("<f.name>", <f.safeName> == null ? null : <f.safeName>.stream().map(__id -> __context.getGlobalIDCodec().serialize(__id.getType().getName(), __id.getInternalID())).collect(java.util.stream.Collectors.toList()));
                        <else>data.put("<f.name>", <f.safeName>);
                        <endif>return this;
                    \\}
                    }; separator="
            ">

                    public <mdl.className> build() {
                        return new <mdl.className>(__context, new LinkedHashMap\\<>(data));
                    }
                }
            }
            """);

    private ObjectGenerator() {}

    /**
     * Generates the Java class source code as a string.
     *
     * @param model the object model
     * @return the generated Java source code
     */
    public static String generate(ObjectModel model) {
      return new STContents(TEMPLATE, model).toString();
    }

    /**
     * Generates the Java class source code and writes it to a file.
     *
     * @param model the object model
     * @param outputDir the output directory
     * @return the file that was written
     * @throws IOException if there's an error writing the file
     */
    public static File generateToFile(ObjectModel model, File outputDir) throws IOException {
      STContents contents = new STContents(TEMPLATE, model);
      return writeToFile(contents, model.packageName(), model.className(), outputDir);
    }
  }

  /** Generator for Java classes from GraphQL input types. */
  public static final class InputGenerator {

    private static final String TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import graphql.schema.GraphQLInputObjectType;
            import viaduct.java.api.context.ExecutionContext;
            import viaduct.java.api.globalid.GlobalID;
            import viaduct.java.api.internal.InputBase;
            import viaduct.java.api.internal.InternalContext;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.OffsetTime;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public class <mdl.className> extends InputBase {

                // Package-private: input GRTs are constructed only through the validating Builder or
                // by sibling GRTs in this package (nested-input wrapping). Tenants cannot construct
                // one directly, so a @oneOf input cannot bypass the builder's fail-fast validation.
                <mdl.className>(InternalContext context, Map\\<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
                    super(context, data, graphQLInputObjectType);
                }

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    <if(f.globalIDList)>return getGlobalIDList("<f.name>");<elseif(f.globalIDType)>return getGlobalID("<f.name>");<elseif(f.compositeList)>return getInputList("<f.name>", <f.baseTypeName>::new);<elseif(f.compositeType)>return getInput("<f.name>", <f.baseTypeName>::new);<elseif(f.enumList)>return getEnumList("<f.name>", <f.baseTypeName>.class);<elseif(f.enumType)>return getEnum("<f.name>", <f.baseTypeName>.class);<elseif(f.temporalScalarList)>return getScalarList("<f.name>", "<f.scalarCoercionHint>");<elseif(f.temporalScalar)>return get("<f.name>", "<f.scalarCoercionHint>");<elseif(f.scalarList)>return getScalarList("<f.name>");<else>return get("<f.name>");<endif>
                \\}
                }; separator="\\n">

                public static Builder builder(ExecutionContext context) {
                    return new Builder(InternalContext.from(context));
                }

                public static class Builder {
                    private final InternalContext __context;
                    private final Map\\<String, Object> data = new LinkedHashMap\\<>();

                    private Builder(InternalContext __context) {
                        this.__context = __context;
                    }

                    <mdl.fields: {f |
                    public Builder <f.safeName>(<f.builderType> <f.safeName>) {
                        <if(f.globalIDBuilderSerialize)>data.put("<f.name>", <f.safeName> == null ? null : __context.getGlobalIDCodec().serialize(<f.safeName>.getType().getName(), <f.safeName>.getInternalID()));
                        <elseif(f.globalIDListBuilderSerialize)>data.put("<f.name>", <f.safeName> == null ? null : <f.safeName>.stream().map(__id -> __context.getGlobalIDCodec().serialize(__id.getType().getName(), __id.getInternalID())).collect(java.util.stream.Collectors.toList()));
                        <else>data.put("<f.name>", <f.safeName>);
                        <endif>return this;
                    \\}
                    }; separator="\\n">

                    public <mdl.className> build() {
                        <if(mdl.isOneOf)>
                        InputBase.validateOneOf("<mdl.className>", data);
                        <endif>
                        return new <mdl.className>(__context, new LinkedHashMap\\<>(data), null);
                    }
                }
            }
            """);

    private InputGenerator() {}

    /**
     * Generates the Java class source code as a string.
     *
     * @param model the input model
     * @return the generated Java source code
     */
    public static String generate(InputModel model) {
      return new STContents(TEMPLATE, model).toString();
    }

    /**
     * Generates the Java class source code and writes it to a file.
     *
     * @param model the input model
     * @param outputDir the output directory
     * @return the file that was written
     * @throws IOException if there's an error writing the file
     */
    public static File generateToFile(InputModel model, File outputDir) throws IOException {
      STContents contents = new STContents(TEMPLATE, model);
      return writeToFile(contents, model.packageName(), model.className(), outputDir);
    }
  }

  /** Generator for Java interfaces from GraphQL interface types. */
  public static final class InterfaceGenerator {

    private static final String TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import viaduct.java.api.globalid.GlobalID;
            import viaduct.java.api.types.GraphQLInterface;
            import viaduct.java.api.types.NodeCompositeOutput;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.OffsetTime;
            import java.util.List;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public interface <mdl.className> extends <mdl.extendsClause> {

                <mdl.fields: {f |
                <f.javaType> <f.getterName>();
                }; separator="\\n">
            }
            """);

    private InterfaceGenerator() {}

    /**
     * Generates the Java interface source code as a string.
     *
     * @param model the interface model
     * @return the generated Java source code
     */
    public static String generate(InterfaceModel model) {
      return new STContents(TEMPLATE, model).toString();
    }

    /**
     * Generates the Java interface source code and writes it to a file.
     *
     * @param model the interface model
     * @param outputDir the output directory
     * @return the file that was written
     * @throws IOException if there's an error writing the file
     */
    public static File generateToFile(InterfaceModel model, File outputDir) throws IOException {
      STContents contents = new STContents(TEMPLATE, model);
      return writeToFile(contents, model.packageName(), model.className(), outputDir);
    }
  }

  /** Generator for Java arguments classes from GraphQL resolver field arguments. */
  public static final class ArgumentGenerator {

    private static final String TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import graphql.schema.GraphQLInputObjectType;
            import viaduct.java.api.globalid.GlobalID;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.OffsetTime;
            import java.util.List;
            import java.util.Map;
            import viaduct.apiannotations.InternalApi;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.internal.InputBase;
            import viaduct.java.api.internal.InternalContext;

            /** Generated arguments class for resolver field. */
            public class <mdl.className> extends InputBase implements Arguments {

                // Public because the framework constructs arguments reflectively across packages
                // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
                // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
                @InternalApi
                public <mdl.className>(InternalContext context, Map\\<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
                    super(context, data, graphQLInputObjectType);
                }

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    <if(f.globalIDList)>return getGlobalIDList("<f.name>");<elseif(f.globalIDType)>return getGlobalID("<f.name>");<elseif(f.compositeList)>return getInputList("<f.name>", <f.baseTypeName>::new);<elseif(f.compositeType)>return getInput("<f.name>", <f.baseTypeName>::new);<elseif(f.enumList)>return getEnumList("<f.name>", <f.baseTypeName>.class);<elseif(f.enumType)>return getEnum("<f.name>", <f.baseTypeName>.class);<elseif(f.temporalScalarList)>return getScalarList("<f.name>", "<f.scalarCoercionHint>");<elseif(f.temporalScalar)>return get("<f.name>", "<f.scalarCoercionHint>");<elseif(f.scalarList)>return getScalarList("<f.name>");<else>return get("<f.name>");<endif>
                \\}
                }; separator="\\n">
            }
            """);

    private ArgumentGenerator() {}

    /**
     * Generates the Java arguments class source code as a string.
     *
     * @param model the argument model
     * @return the generated Java source code
     */
    public static String generate(ArgumentModel model) {
      return new STContents(TEMPLATE, model).toString();
    }

    /**
     * Generates the Java arguments class source code and writes it to a file.
     *
     * @param model the argument model
     * @param outputDir the output directory
     * @return the file that was written
     * @throws IOException if there's an error writing the file
     */
    public static File generateToFile(ArgumentModel model, File outputDir) throws IOException {
      STContents contents = new STContents(TEMPLATE, model);
      return writeToFile(contents, model.packageName(), model.className(), outputDir);
    }
  }

  /** Generator for Java union interfaces from GraphQL union types. */
  public static final class UnionGenerator {

    private static final String TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import viaduct.java.api.types.GraphQLUnion;

            /**
            <if(mdl.hasDescription)>
             * <mdl.description>
             *
            <endif>
             * Possible types: <mdl.memberTypes; separator=", ">
             */
            public interface <mdl.className> extends GraphQLUnion {
            }
            """);

    private UnionGenerator() {}

    /**
     * Generates the Java union interface source code as a string.
     *
     * @param model the union model
     * @return the generated Java source code
     */
    public static String generate(UnionModel model) {
      return new STContents(TEMPLATE, model).toString();
    }

    /**
     * Generates the Java union interface source code and writes it to a file.
     *
     * @param model the union model
     * @param outputDir the output directory
     * @return the file that was written
     * @throws IOException if there's an error writing the file
     */
    public static File generateToFile(UnionModel model, File outputDir) throws IOException {
      STContents contents = new STContents(TEMPLATE, model);
      return writeToFile(contents, model.packageName(), model.className(), outputDir);
    }
  }
}
