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

    ArgumentModel model = new ArgumentModel("com.example", "MyArgs", fields);

    assertEquals("com.example", model.packageName());
    assertEquals("MyArgs", model.className());
    assertEquals(fields, model.fields());
  }

  @Test
  void gettersReturnSameValuesAsRecordAccessors() {
    List<FieldModel> fields = List.of(FieldModel.simple("name", "String", false));

    ArgumentModel model = new ArgumentModel("com.airbnb.types", "SearchArgs", fields);

    assertEquals(model.packageName(), model.getPackageName());
    assertEquals(model.className(), model.getClassName());
    assertEquals(model.fields(), model.getFields());
  }

  @Test
  void emptyFieldsList() {
    ArgumentModel model = new ArgumentModel("com.example", "EmptyArgs", List.of());

    assertTrue(model.getFields().isEmpty());
    assertEquals("com.example", model.getPackageName());
    assertEquals("EmptyArgs", model.getClassName());
  }
}
