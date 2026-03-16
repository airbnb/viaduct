package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgumentModelTest {

  @Test
  void recordAccessorsReturnConstructorValues() {
    List<FieldModel> fields =
        List.of(new FieldModel("id", "String", false), new FieldModel("count", "Integer", true));

    ArgumentModel model = new ArgumentModel("com.example", "MyArgs", fields);

    assertThat(model.packageName()).isEqualTo("com.example");
    assertThat(model.className()).isEqualTo("MyArgs");
    assertThat(model.fields()).isEqualTo(fields);
  }

  @Test
  void gettersReturnSameValuesAsRecordAccessors() {
    List<FieldModel> fields = List.of(new FieldModel("name", "String", false));

    ArgumentModel model = new ArgumentModel("com.airbnb.types", "SearchArgs", fields);

    assertThat(model.getPackageName()).isEqualTo(model.packageName());
    assertThat(model.getClassName()).isEqualTo(model.className());
    assertThat(model.getFields()).isEqualTo(model.fields());
  }

  @Test
  void emptyFieldsList() {
    ArgumentModel model = new ArgumentModel("com.example", "EmptyArgs", List.of());

    assertThat(model.getFields()).isEmpty();
    assertThat(model.getPackageName()).isEqualTo("com.example");
    assertThat(model.getClassName()).isEqualTo("EmptyArgs");
  }
}
