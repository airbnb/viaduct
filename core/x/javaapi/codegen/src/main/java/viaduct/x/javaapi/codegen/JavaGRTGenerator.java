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

  private static final String INPUT_LIKE_FIELD_ACCESSORS_TEMPLATE =
      """
          <mdl.fields: {f |
          public <f.javaType> <f.getterName>() {
              <if(f.globalIDList)>return getGlobalIDList("<f.name>");<elseif(f.globalIDType)>return getGlobalID("<f.name>");<elseif(f.compositeList)>return getInputList("<f.name>", <f.baseTypeName>::new);<elseif(f.compositeType)>return getInput("<f.name>", <f.baseTypeName>::new);<elseif(f.enumList)>return getEnumList("<f.name>", <f.baseTypeName>.class);<elseif(f.enumType)>return getEnum("<f.name>", <f.baseTypeName>.class);<elseif(f.temporalScalarList)>return getScalarList("<f.name>", "<f.scalarCoercionHint>");<elseif(f.temporalScalar)>return get("<f.name>", "<f.scalarCoercionHint>");<elseif(f.scalarList)>return getScalarList("<f.name>");<else>return get("<f.name>");<endif>
          \\}
          }; separator="\\n">
      """;

  private static final String INPUT_LIKE_BUILDER_SETTERS_TEMPLATE =
      """
              <mdl.fields: {f |
              public Builder <f.safeName>(<f.builderType> <f.safeName>) {
                  <if(f.globalIDBuilderSerialize)>data.put("<f.name>", <f.safeName> == null ? null : __context.getGlobalIDCodec().serialize(<f.safeName>.getType().getName(), <f.safeName>.getInternalID()));
                  <elseif(f.globalIDListBuilderSerialize)>data.put("<f.name>", <f.safeName> == null ? null : <f.safeName>.stream().map(__id -> __id == null ? null : __context.getGlobalIDCodec().serialize(__id.getType().getName(), __id.getInternalID())).collect(java.util.stream.Collectors.toList()));
                  <else>data.put("<f.name>", <f.safeName>);
                  <endif>return this;
              \\}
              }; separator="\\n">
      """;

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

            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.types.GraphQLEnum;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public enum <mdl.className> implements GraphQLEnum {
                <mdl.valueNames: {valueName | <valueName>}; separator=",
            ">;

                public static final Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);
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
            import viaduct.engine.api.RootFieldReference;
            import viaduct.java.api.context.ExecutionContext;
            import viaduct.java.api.globalid.GlobalID;
            import viaduct.java.api.internal.InternalContext;
            import viaduct.java.api.internal.NodeObjectBase;
            import viaduct.java.api.internal.ObjectBase;
            import viaduct.java.api.internal.OutputBuilderTypeChecker;
            import viaduct.java.api.reflect.CompositeField;
            import viaduct.java.api.reflect.Field;
            import viaduct.java.api.reflect.RootObjectField;
            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.reflect.TypeFields;
            import viaduct.java.api.types.Arguments;
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
            <if(mdl.hasImplementsClause)>@SuppressWarnings("MissingOverride")
            <endif>
            public class <mdl.className> extends <if(mdl.isNodeType)>NodeObjectBase<else>ObjectBase<endif><if(mdl.hasImplementsClause)> implements <mdl.implementsClause><endif> {

                public static final Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);

                public static final class Fields implements TypeFields\\<<mdl.className>\\> {
                    private Fields() {}

                    public static final Field\\<<mdl.className>\\> __typename =
                            Field.of("__typename", Reflection);
                    <mdl.reflectedFields: {f |
                    <if(f.rootObjectField)>public static final RootObjectField\\<<mdl.className>, <f.reflectedTypeName>, <f.argumentsTypeName>\\> <f.safeName> =
                            RootObjectField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection, List.of(<f.pathFromQueryRoot: {segment | "<segment>"}; separator=", ">));
                    <elseif(f.hasReflectedType)>public static final CompositeField\\<<mdl.className>, <f.reflectedTypeName>\\> <f.safeName> =
                            CompositeField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection);
                    <else>public static final Field\\<<mdl.className>\\> <f.safeName> =
                            Field.of("<f.name>", Reflection);
                    <endif>
                    }; separator="\\n">
                }

                public <mdl.className>(InternalContext context, EngineObjectData.Sync data) {
                    super(context, data);
                }

                private <mdl.className>(InternalContext context, Map\\<String, Object> data) {
                    super(context, data);
                }

                public <mdl.className>(InternalContext context, RootFieldReference rootFieldReference) {
                    super(context, rootFieldReference);
                }
                <if(mdl.isNodeType)>

                public <mdl.className>(InternalContext context, NodeReference nodeReference) {
                    super(context, nodeReference);
                \\}
                <endif>

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    return <f.getterExpression>;
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
                        <f.safeName> = OutputBuilderTypeChecker.checkField(
                                __context,
                                "<mdl.className>",
                                "<f.name>",
                                <if(f.hasGeneratedType)><f.baseTypeName>.class<else>null<endif>,
                                <f.safeName>);
                        <if(f.globalIDBuilderSerialize)>data.put("<f.name>", <f.builderValueExpression>);
                        <elseif(f.globalIDListBuilderSerialize)>data.put("<f.name>", <f.builderValueExpression>);
                        <else>data.put("<f.name>", <f.builderValueExpression>);
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

    /**
     * Template for {@code @connection} object types. Kept separate from {@link #TEMPLATE} because
     * the connection builder extends {@link viaduct.java.api.internal.ConnectionBuilder} with a
     * fixed shape (no per-field setters), which avoids weaving StringTemplate conditionals through
     * the generic-object template's brace-escaping.
     */
    private static final String CONNECTION_TEMPLATE =
        STUtilsKt.stTemplate(
            """
            package <mdl.packageName>;

            import viaduct.engine.api.EngineObjectData;
            import viaduct.engine.api.RootFieldReference;
            import viaduct.java.api.context.ExecutionContext;
            import viaduct.java.api.globalid.GlobalID;
            import viaduct.java.api.internal.ConnectionBuilder;
            import viaduct.java.api.internal.InternalContext;
            import viaduct.java.api.internal.ObjectBase;
            import viaduct.java.api.reflect.CompositeField;
            import viaduct.java.api.reflect.Field;
            import viaduct.java.api.reflect.RootObjectField;
            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.reflect.TypeFields;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.types.ConnectionArguments;
            import viaduct.java.api.types.OffsetLimit;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.OffsetTime;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            @SuppressWarnings("MissingOverride")
            public class <mdl.className> extends ObjectBase implements <mdl.implementsClause> {

                public static final Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);

                public static final class Fields implements TypeFields\\<<mdl.className>\\> {
                    private Fields() {}

                    public static final Field\\<<mdl.className>\\> __typename =
                            Field.of("__typename", Reflection);
                    <mdl.reflectedFields: {f |
                    <if(f.rootObjectField)>public static final RootObjectField\\<<mdl.className>, <f.reflectedTypeName>, <f.argumentsTypeName>\\> <f.safeName> =
                            RootObjectField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection, List.of(<f.pathFromQueryRoot: {segment | "<segment>"}; separator=", ">));
                    <elseif(f.hasReflectedType)>public static final CompositeField\\<<mdl.className>, <f.reflectedTypeName>\\> <f.safeName> =
                            CompositeField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection);
                    <else>public static final Field\\<<mdl.className>\\> <f.safeName> =
                            Field.of("<f.name>", Reflection);
                    <endif>
                    }; separator="\\n">
                }

                public <mdl.className>(InternalContext context, EngineObjectData.Sync data) {
                    super(context, data);
                }

                @SuppressWarnings("UnusedMethod")
                private <mdl.className>(InternalContext context, Map\\<String, Object> data) {
                    super(context, data);
                }

                public <mdl.className>(InternalContext context, RootFieldReference rootFieldReference) {
                    super(context, rootFieldReference);
                }

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    return <f.getterExpression>;
                \\}
                }; separator="
            ">

                public static Builder builder(ExecutionContext context) {
                    return new Builder(context);
                }

                /**
                 * Pagination-aware builder. Extends {@link ConnectionBuilder} with {@code fromEdges},
                 * {@code fromSlice}, and {@code fromList}; the base builds the <mdl.edgeTypeName>,
                 * PageInfo, and <mdl.className> GRTs from the field's context and these Class handles.
                 * The generated per-field setters populate additional connection fields (e.g.
                 * {@code totalCount}) alongside the pagination-produced {@code edges}/{@code pageInfo};
                 * a pagination method and any setters can be combined in any order before {@code build()}.
                 */
                public static class Builder extends <mdl.connectionBuilderSupertype> {
                    private Builder(ExecutionContext context) {
                        super(context, <mdl.className>.class, <mdl.edgeTypeName>.class);
                    }

                    @Override
                    public Builder fromEdges(List\\<<mdl.edgeTypeName>\\> edges) {
                        super.fromEdges(edges);
                        return this;
                    }

                    @Override
                    public Builder fromEdges(
                            List\\<<mdl.edgeTypeName>\\> edges,
                            boolean hasNextPage,
                            boolean hasPreviousPage) {
                        super.fromEdges(edges, hasNextPage, hasPreviousPage);
                        return this;
                    }

                    @Override
                    public \\<I> Builder fromSlice(
                            List\\<I> items,
                            boolean hasNextPage,
                            Function\\<I, <mdl.nodeTypeName>\\> buildNode) {
                        super.fromSlice(items, hasNextPage, buildNode);
                        return this;
                    }

                    @Override
                    public \\<I> Builder fromSlice(
                            List\\<I> items,
                            OffsetLimit offsetLimit,
                            boolean hasNextPage,
                            Function\\<I, <mdl.nodeTypeName>\\> buildNode) {
                        super.fromSlice(items, offsetLimit, hasNextPage, buildNode);
                        return this;
                    }

                    @Override
                    public \\<I> Builder fromList(
                            List\\<I> items,
                            Function\\<I, <mdl.nodeTypeName>\\> buildNode) {
                        super.fromList(items, buildNode);
                        return this;
                    }

                    <mdl.fields: {f |
                    public Builder <f.safeName>(<f.builderType> <f.safeName>) {
                        <if(f.globalIDBuilderSerialize)>putGlobalIDField("<f.name>", <f.safeName>);
                        <elseif(f.globalIDListBuilderSerialize)>putGlobalIDListField("<f.name>", <f.safeName>);
                        <else>putField(
                                "<f.name>",
                                <f.safeName>,
                                <if(f.hasGeneratedType)><f.baseTypeName>.class<else>null<endif>);
                        <endif>return this;
                    \\}
                    }; separator="
            ">
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
      return new STContents(templateFor(model), model).toString();
    }

    /** Connection object types use the pagination-aware {@link #CONNECTION_TEMPLATE}. */
    private static String templateFor(ObjectModel model) {
      return model.getIsConnection() ? CONNECTION_TEMPLATE : TEMPLATE;
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
      STContents contents = new STContents(templateFor(model), model);
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
            import viaduct.java.api.reflect.CompositeField;
            import viaduct.java.api.reflect.Field;
            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.reflect.TypeFields;
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

                public static final Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);

                public static final class Fields implements TypeFields\\<<mdl.className>\\> {
                    private Fields() {}

                    public static final Field\\<<mdl.className>\\> __typename =
                            Field.of("__typename", Reflection);
                    <mdl.reflectedFields: {f |
                    <if(f.hasReflectedType)>public static final CompositeField\\<<mdl.className>, <f.reflectedTypeName>\\> <f.safeName> =
                            CompositeField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection);
                    <else>public static final Field\\<<mdl.className>\\> <f.safeName> =
                            Field.of("<f.name>", Reflection);
                    <endif>
                    }; separator="\\n">
                }

                // Package-private: input GRTs are constructed only through the validating Builder or
                // by sibling GRTs in this package (nested-input wrapping). Tenants cannot construct
                // one directly, so a @oneOf input cannot bypass the builder's fail-fast validation.
                <mdl.className>(InternalContext context, Map\\<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
                    super(context, data, graphQLInputObjectType);
                }

                /**
                 * Returns whether this input contains a value for {@code field} after GraphQL
                 * defaults are applied. Explicit {@code null} counts as present. An omitted field
                 * with a schema default is present; an omitted field without a default is absent.
                 */
                public boolean isPresent(Field\\<<mdl.className>\\> field) {
                    return isFieldPresent(field);
                }

            """
                + INPUT_LIKE_FIELD_ACCESSORS_TEMPLATE
                + """

                    public static Builder builder(ExecutionContext context) {
                        return new Builder(InternalContext.from(context));
                    }

                    public static class Builder {
                        private final InternalContext __context;
                        private final GraphQLInputObjectType graphQLInputObjectType;
                        private final Map\\<String, Object> data = new LinkedHashMap\\<>();

                        private Builder(InternalContext __context) {
                            this.__context = __context;
                            this.graphQLInputObjectType =
                                    (GraphQLInputObjectType) __context.getSchema().getSchema().getType("<mdl.className>");
                        }

                """
                + INPUT_LIKE_BUILDER_SETTERS_TEMPLATE
                + """

                        public <mdl.className> build() {
                            <if(mdl.isOneOf)>
                            InputBase.validateOneOf("<mdl.className>", data);
                            <endif>
                            return new <mdl.className>(__context, new LinkedHashMap\\<>(data), graphQLInputObjectType);
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
            import viaduct.java.api.reflect.CompositeField;
            import viaduct.java.api.reflect.Field;
            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.reflect.TypeFields;
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
            <if(mdl.hasExtendedInterfaces)>@SuppressWarnings("MissingOverride")
            <endif>
            public interface <mdl.className> extends <mdl.extendsClause> {

                Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);

                final class Fields implements TypeFields\\<<mdl.className>\\> {
                    private Fields() {}

                    public static final Field\\<<mdl.className>\\> __typename =
                            Field.of("__typename", Reflection);
                    <mdl.reflectedFields: {f |
                    <if(f.hasReflectedType)>public static final CompositeField\\<<mdl.className>, <f.reflectedTypeName>\\> <f.safeName> =
                            CompositeField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection);
                    <else>public static final Field\\<<mdl.className>\\> <f.safeName> =
                            Field.of("<f.name>", Reflection);
                    <endif>
                    }; separator="\\n">
                }

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
            import viaduct.java.api.context.ExecutionContext;
            import viaduct.java.api.globalid.GlobalID;
            import viaduct.java.api.reflect.CompositeField;
            import viaduct.java.api.reflect.Field;
            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.reflect.TypeFields;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.OffsetTime;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;
            import viaduct.apiannotations.InternalApi;
            import viaduct.java.api.types.Arguments;
            import viaduct.java.api.internal.InputBase;
            import viaduct.java.api.internal.InternalContext;

            /** Generated arguments class for resolver field. */
            <if(mdl.isConnectionArguments)>@SuppressWarnings("MissingOverride")
            <endif>
            public class <mdl.className> extends InputBase implements Arguments<if(mdl.isConnectionArguments)>, <mdl.connectionArgumentsClause><endif> {

                public static final Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);

                public static final class Fields implements TypeFields\\<<mdl.className>\\> {
                    private Fields() {}

                    public static final Field\\<<mdl.className>\\> __typename =
                            Field.of("__typename", Reflection);
                    <mdl.reflectedFields: {f |
                    <if(f.hasReflectedType)>public static final CompositeField\\<<mdl.className>, <f.reflectedTypeName>\\> <f.safeName> =
                            CompositeField.of("<f.name>", Reflection, <f.reflectedTypeName>.Reflection);
                    <else>public static final Field\\<<mdl.className>\\> <f.safeName> =
                            Field.of("<f.name>", Reflection);
                    <endif>
                    }; separator="\\n">
                }

                // Public because the framework constructs arguments reflectively across packages
                // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
                // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
                @InternalApi
                public <mdl.className>(InternalContext context, Map\\<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
                    super(context, data, graphQLInputObjectType);
                }

                /**
                 * Returns whether this input contains a value for {@code field} after GraphQL
                 * defaults are applied. Explicit {@code null} counts as present. An omitted field
                 * with a schema default is present; an omitted field without a default is absent.
                 */
                public boolean isPresent(Field\\<<mdl.className>\\> field) {
                    return isFieldPresent(field);
                }

            """
                + INPUT_LIKE_FIELD_ACCESSORS_TEMPLATE
                + """
                    <mdl.synthesizedConnectionFields: {f |
                    public <f.javaType> <f.getterName>() {
                        return null;
                    \\}
                    }; separator="\\n">

                    public static Builder builder(ExecutionContext context) {
                        return new Builder(InternalContext.from(context));
                    }

                    public static class Builder {
                        private final InternalContext __context;
                        private final GraphQLInputObjectType graphQLInputObjectType;
                        private final Map\\<String, Object> data = new LinkedHashMap\\<>();

                        private Builder(InternalContext __context) {
                            this.__context = __context;
                            this.graphQLInputObjectType =
                                    __context.getArgumentsInputType(
                                            "<mdl.className>",
                                            "<mdl.containingTypeName>",
                                            "<mdl.fieldName>");
                        }

                """
                + INPUT_LIKE_BUILDER_SETTERS_TEMPLATE
                + """

                        public <mdl.className> build() {
                            return new <mdl.className>(
                                    __context, new LinkedHashMap\\<>(data), graphQLInputObjectType);
                        }
                    }
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

            import viaduct.java.api.reflect.Field;
            import viaduct.java.api.reflect.Type;
            import viaduct.java.api.reflect.TypeFields;
            import viaduct.java.api.types.GraphQLUnion;

            /**
            <if(mdl.hasDescription)>
             * <mdl.description>
             *
            <endif>
             * Possible types: <mdl.memberTypes; separator=", ">
             */
            public interface <mdl.className> extends GraphQLUnion {
                Type\\<<mdl.className>\\> Reflection = Type.ofClass(<mdl.className>.class);

                final class Fields implements TypeFields\\<<mdl.className>\\> {
                    private Fields() {}

                    public static final Field\\<<mdl.className>\\> __typename =
                            Field.of("__typename", Reflection);
                }
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
