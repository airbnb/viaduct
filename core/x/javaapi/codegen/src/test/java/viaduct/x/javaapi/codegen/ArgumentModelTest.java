package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgumentModelTest {

  @Test
  void recordAccessorsReturnConstructorValues() {
    List<FieldModel> fields =
        List.of(
            FieldModel.simple("id", "String", false), FieldModel.simple("count", "Integer", true));

    ArgumentModel model = new ArgumentModel("com.example", "MyArgs", "Query", "users", fields);

    assertEquals("com.example", model.packageName());
    assertEquals("MyArgs", model.className());
    assertEquals("Query", model.containingTypeName());
    assertEquals("users", model.fieldName());
    assertEquals(fields, model.fields());
  }

  @Test
  void gettersReturnSameValuesAsRecordAccessors() {
    List<FieldModel> fields = List.of(FieldModel.simple("name", "String", false));

    ArgumentModel model =
        new ArgumentModel("com.airbnb.types", "SearchArgs", "Query", "search", fields);

    assertEquals(model.packageName(), model.getPackageName());
    assertEquals(model.className(), model.getClassName());
    assertEquals(model.containingTypeName(), model.getContainingTypeName());
    assertEquals(model.fieldName(), model.getFieldName());
    assertEquals(model.fields(), model.getFields());
    assertEquals(model.fields(), model.getReflectedFields());
    assertTrue(model.getSynthesizedConnectionFields().isEmpty());
  }

  @Test
  void emptyFieldsList() {
    ArgumentModel model =
        new ArgumentModel("com.example", "EmptyArgs", "Query", "empty", List.of());

    assertTrue(model.getFields().isEmpty());
    assertEquals("com.example", model.getPackageName());
    assertEquals("EmptyArgs", model.getClassName());
  }

  @Test
  void generatorAddsTypeSafeFieldPresenceMethod() {
    ArgumentModel model =
        new ArgumentModel(
            "com.example",
            "Query_User_Arguments",
            "Query",
            "user",
            List.of(FieldModel.simple("limit", "Integer", true)));

    String generated = JavaGRTGenerator.ArgumentGenerator.generate(model);

    assertTrue(
        generated.contains(
            "Returns whether this input contains a value for {@code field} after GraphQL"));
    assertTrue(generated.contains("public boolean isPresent(Field<Query_User_Arguments> field)"));
    assertTrue(generated.contains("return isFieldPresent(field)"));
    assertTrue(generated.contains("__context.getArgumentsInputType("));
    assertTrue(generated.contains("\"Query_User_Arguments\""));
    assertTrue(generated.contains("\"Query\""));
    assertTrue(generated.contains("\"user\""));
    assertTrue(generated.contains("new Query_User_Arguments("));
    assertTrue(generated.contains("new LinkedHashMap<>(data), graphQLInputObjectType"));
  }
}
