package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeResolverModelTest {

  @Test
  void getters_returnConstructorValues() {
    NodeResolverModel model =
        new NodeResolverModel("com.example.tenant", "com.example.types", "User", false, false);

    assertThat(model.getTenantPackage()).isEqualTo("com.example.tenant");
    assertThat(model.getGrtPackage()).isEqualTo("com.example.types");
    assertThat(model.getTypeName()).isEqualTo("User");
    assertThat(model.getIsBatching()).isFalse();
    assertThat(model.getIsSelective()).isFalse();
  }

  @Test
  void getBatchingLiteral_returnsTrueString_whenBatching() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", true, false);

    assertThat(model.getBatchingLiteral()).isEqualTo("true");
  }

  @Test
  void getBatchingLiteral_returnsFalseString_whenNotBatching() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", false, false);

    assertThat(model.getBatchingLiteral()).isEqualTo("false");
  }

  @Test
  void getSelectiveLiteral_returnsTrueString_whenSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", false, true);

    assertThat(model.getSelectiveLiteral()).isEqualTo("true");
  }

  @Test
  void getSelectiveLiteral_returnsFalseString_whenNotSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", false, false);

    assertThat(model.getSelectiveLiteral()).isEqualTo("false");
  }

  @Test
  void getGrtType_returnsFullyQualifiedClassName_fromGrtPackage() {
    NodeResolverModel model =
        new NodeResolverModel("com.example.tenant", "com.example.types", "User", false, false);

    // GRT type uses grtPackage, not tenantPackage
    assertThat(model.getGrtType()).isEqualTo("com.example.types.User");
  }

  @Test
  void getBatchResolveFutureType_isListOfFieldValue() {
    NodeResolverModel model =
        new NodeResolverModel("com.example.tenant", "com.example.types", "User", true, false);

    assertThat(model.getBatchResolveFutureType())
        .isEqualTo("CompletableFuture<List<FieldValue<com.example.types.User>>>");
  }

  @Test
  void getCtxInterface_isNodeExecutionContext_whenNotSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "User", false, false);

    assertThat(model.getCtxInterface()).isEqualTo("NodeExecutionContext");
  }

  @Test
  void getCtxInterface_isSelectiveNodeExecutionContext_whenSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "User", false, true);

    assertThat(model.getCtxInterface()).isEqualTo("SelectiveNodeExecutionContext");
  }
}
