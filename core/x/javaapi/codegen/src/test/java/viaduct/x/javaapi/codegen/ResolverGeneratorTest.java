package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResolverGeneratorTest {

  @Test
  void generatesSimpleResolverWithNoArguments() {
    ResolverModel resolverModel =
        new ResolverModel(
            "User",
            "profile",
            "Profile",
            "Profile",
            "com.example.types.User",
            "com.example.types.Query",
            "Arguments.None",
            "com.example.types.Profile",
            false,
            true,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel("com.example.tenant", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertThat(generated)
        .contains("package com.example.tenant.resolverbases;")
        .contains("public final class UserResolvers")
        .contains("@ResolverFor(typeName = \"User\", fieldName = \"profile\")")
        .contains("public abstract static class Profile")
        .contains(
            "implements FieldResolverBase<Profile, com.example.types.User, com.example.types.Query,"
                + " Arguments.None, com.example.types.Profile>")
        .contains("public static class Context")
        .contains("public abstract CompletableFuture<Profile> resolve(Context ctx)");
  }

  @Test
  void generatesResolverWithArguments() {
    ResolverModel resolverModel =
        new ResolverModel(
            "Query",
            "user",
            "User",
            "User",
            "com.example.types.Query",
            "com.example.types.Query",
            "com.example.types.Query_User_Arguments",
            "com.example.types.User",
            true,
            true,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel("com.example.tenant", "Query", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertThat(generated)
        .contains("@ResolverFor(typeName = \"Query\", fieldName = \"user\")")
        .contains("com.example.types.Query_User_Arguments");
  }

  @Test
  void excludesBatchResolveForMutationFields() {
    ResolverModel resolverModel =
        new ResolverModel(
            "Mutation",
            "createUser",
            "CreateUser",
            "User",
            "com.example.types.Mutation",
            "com.example.types.Query",
            "com.example.types.Mutation_CreateUser_Arguments",
            "com.example.types.User",
            true,
            true,
            false); // includeBatchResolve = false for mutations

    ResolversFileModel fileModel =
        new ResolversFileModel("com.example.tenant", "Mutation", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertThat(generated)
        .contains("@ResolverFor(typeName = \"Mutation\", fieldName = \"createUser\")")
        .contains("public abstract CompletableFuture<User> resolve(Context ctx)")
        .doesNotContain("batchResolve");
  }

  @Test
  void generatesMultipleResolversPerType() {
    ResolverModel resolver1 =
        new ResolverModel(
            "User",
            "profile",
            "Profile",
            "Profile",
            "com.example.types.User",
            "com.example.types.Query",
            "Arguments.None",
            "com.example.types.Profile",
            false,
            true,
            true);

    ResolverModel resolver2 =
        new ResolverModel(
            "User",
            "orders",
            "Orders",
            "List<Order>",
            "com.example.types.User",
            "com.example.types.Query",
            "Arguments.None",
            "com.example.types.Order",
            false,
            true,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel("com.example.tenant", "User", List.of(resolver1, resolver2));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertThat(generated)
        .contains("public final class UserResolvers")
        .contains("@ResolverFor(typeName = \"User\", fieldName = \"profile\")")
        .contains("public abstract static class Profile")
        .contains("@ResolverFor(typeName = \"User\", fieldName = \"orders\")")
        .contains("public abstract static class Orders");
  }

  @Test
  void generatesResolverWithScalarOutput() {
    ResolverModel resolverModel =
        new ResolverModel(
            "User",
            "fullName",
            "FullName",
            "String",
            "com.example.types.User",
            "com.example.types.Query",
            "Arguments.None",
            "CompositeOutput.None",
            false,
            false,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel("com.example.tenant", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertThat(generated)
        .contains("@ResolverFor(typeName = \"User\", fieldName = \"fullName\")")
        .contains("CompositeOutput.None")
        .contains("public abstract CompletableFuture<String> resolve(Context ctx)");
  }

  @Test
  void generatesContextWithAllDelegateMethods() {
    ResolverModel resolverModel =
        new ResolverModel(
            "User",
            "profile",
            "Profile",
            "Profile",
            "com.example.types.User",
            "com.example.types.Query",
            "Arguments.None",
            "com.example.types.Profile",
            false,
            true,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel("com.example.tenant", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    // Check that Context class has all required delegate methods
    assertThat(generated)
        .contains("public com.example.types.User getObjectValue()")
        .contains("public com.example.types.Query getQueryValue()")
        .contains("public Arguments.None getArguments()")
        .contains("public Object getSelections()")
        .contains(
            "public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String"
                + " internalID)")
        .contains("public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID)")
        .contains("public Object getRequestContext()")
        .contains("public <T extends NodeCompositeOutput> T nodeFor(GlobalID<T> id)");
  }
}
