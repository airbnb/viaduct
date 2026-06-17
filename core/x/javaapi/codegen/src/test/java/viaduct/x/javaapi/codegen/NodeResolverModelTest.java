package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class NodeResolverModelTest {

  @Test
  void getters_returnConstructorValues() {
    NodeResolverModel model =
        new NodeResolverModel("com.example.tenant", "com.example.types", "User", false, false);

    assertEquals("com.example.tenant", model.getTenantPackage());
    assertEquals("com.example.types", model.getGrtPackage());
    assertEquals("User", model.getTypeName());
    assertFalse(model.getIsBatching());
    assertFalse(model.getIsSelective());
  }

  @Test
  void getBatchingLiteral_returnsTrueString_whenBatching() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", true, false);

    assertEquals("true", model.getBatchingLiteral());
  }

  @Test
  void getBatchingLiteral_returnsFalseString_whenNotBatching() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", false, false);

    assertEquals("false", model.getBatchingLiteral());
  }

  @Test
  void getSelectiveLiteral_returnsTrueString_whenSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", false, true);

    assertEquals("true", model.getSelectiveLiteral());
  }

  @Test
  void getSelectiveLiteral_returnsFalseString_whenNotSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "Booking", false, false);

    assertEquals("false", model.getSelectiveLiteral());
  }

  @Test
  void getGrtType_returnsFullyQualifiedClassName_fromGrtPackage() {
    NodeResolverModel model =
        new NodeResolverModel("com.example.tenant", "com.example.types", "User", false, false);

    // GRT type uses grtPackage, not tenantPackage
    assertEquals("com.example.types.User", model.getGrtType());
  }

  @Test
  void getBatchResolveFutureType_isListOfFieldValue() {
    NodeResolverModel model =
        new NodeResolverModel("com.example.tenant", "com.example.types", "User", true, false);

    assertEquals(
        "CompletableFuture<List<FieldValue<com.example.types.User>>>",
        model.getBatchResolveFutureType());
  }

  @Test
  void getCtxInterface_isNodeExecutionContext_whenNotSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "User", false, false);

    assertEquals("NodeExecutionContext", model.getCtxInterface());
  }

  @Test
  void getCtxInterface_isSelectiveNodeExecutionContext_whenSelective() {
    NodeResolverModel model =
        new NodeResolverModel("com.example", "com.example", "User", false, true);

    assertEquals("SelectiveNodeExecutionContext", model.getCtxInterface());
  }
}
