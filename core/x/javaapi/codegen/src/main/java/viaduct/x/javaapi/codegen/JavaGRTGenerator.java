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

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public enum <mdl.className> {
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

            import viaduct.java.api.types.GraphQLObject;
            import java.util.List;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public class <mdl.className> implements <mdl.implementsClause> {

                <mdl.fields: {f |
                private <f.javaType> <f.name>;
                }; separator="
            ">

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    return this.<f.name>;
                \\}

                public void <f.setterName>(<f.javaType> <f.name>) {
                    this.<f.name> = <f.name>;
                \\}
                }; separator="
            ">

                public static Builder builder() {
                    return new Builder();
                }

                public static class Builder {
                    <mdl.fields: {f |
                    private <f.javaType> <f.name>;
                    }; separator="
            ">

                    <mdl.fields: {f |
                    public Builder <f.name>(<f.javaType> <f.name>) {
                        this.<f.name> = <f.name>;
                        return this;
                    \\}
                    }; separator="
            ">

                    public <mdl.className> build() {
                        <mdl.className> obj = new <mdl.className>();
                        <mdl.fields: {f |
                        obj.<f.name> = this.<f.name>;
                        }; separator="
            ">
                        return obj;
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

            import viaduct.java.api.types.GraphQLInput;
            import java.util.List;

            <if(mdl.hasDescription)>
            /**
             * <mdl.description>
             */
            <endif>
            public class <mdl.className> implements GraphQLInput {

                <mdl.fields: {f |
                private <f.javaType> <f.name>;
                }; separator="\\n">

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    return this.<f.name>;
                \\}

                public void <f.setterName>(<f.javaType> <f.name>) {
                    this.<f.name> = <f.name>;
                \\}
                }; separator="\\n">

                public static Builder builder() {
                    return new Builder();
                }

                public static class Builder {
                    <mdl.fields: {f |
                    private <f.javaType> <f.name>;
                    }; separator="\\n">

                    <mdl.fields: {f |
                    public Builder <f.name>(<f.javaType> <f.name>) {
                        this.<f.name> = <f.name>;
                        return this;
                    \\}
                    }; separator="\\n">

                    public <mdl.className> build() {
                        <mdl.className> obj = new <mdl.className>();
                        <mdl.fields: {f |
                        obj.<f.name> = this.<f.name>;
                        }; separator="\\n">
                        return obj;
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

            import viaduct.java.api.types.GraphQLInterface;
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

            import java.util.List;
            import viaduct.java.api.types.Arguments;

            /** Generated arguments class for resolver field. */
            public class <mdl.className> implements Arguments {

                <mdl.fields: {f |
                private <f.javaType> <f.name>;
                }; separator="\\n">

                <mdl.fields: {f |
                public <f.javaType> <f.getterName>() {
                    return this.<f.name>;
                \\}

                public void <f.setterName>(<f.javaType> <f.name>) {
                    this.<f.name> = <f.name>;
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
