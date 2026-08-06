package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "com.example.types.Profile",
            false,
            true,
            false,
            false); // isBatching = false: generates resolve()

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertTrue(generated.contains("package com.example.tenant.resolverbases;"));
    assertTrue(generated.contains("import com.example.grt.*;"));
    assertTrue(!generated.contains("import com.example.tenant.*;"));
    assertTrue(generated.contains("public final class UserResolvers"));
    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"profile\", isSelective = false,"
                + " isBatching = false)"));
    assertTrue(generated.contains("public abstract static class Profile"));
    assertTrue(
        generated.contains(
            "implements FieldResolverBase<Profile, com.example.types.User, com.example.types.Query,"
                + " Arguments.NoArguments, com.example.types.Profile>,"
                + " BaseUnbatchedFieldResolver"));
    assertTrue(generated.contains("public static final class Context"));
    assertTrue(
        generated.contains(
            "public <A extends Arguments, T extends GraphQLObject> T rootFieldRef("));
    assertTrue(generated.contains("RootObjectField<?, T, A> field, A arguments)"));
    assertTrue(generated.contains("return inner.rootFieldRef(field, arguments);"));
    assertTrue(
        generated.contains("public abstract CompletableFuture<Profile> resolve(Context ctx)"));
    assertTrue(generated.contains("public final CompletableFuture<?> invokeFieldResolver("));
    assertTrue(generated.contains("return resolve(new Context((FieldExecutionContext<"));
    assertTrue(!generated.contains("batchResolve"));
  }

  @Test
  void generatesBatchResolverWhenIsBatchingIsTrue() {
    ResolverModel resolverModel =
        new ResolverModel(
            "User",
            "profile",
            "Profile",
            "Profile",
            "com.example.types.User",
            "com.example.types.Query",
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "com.example.types.Profile",
            false,
            true,
            false,
            true); // isBatching = true: generates batchResolve()

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"profile\", isSelective = false,"
                + " isBatching = true)"));
    assertTrue(
        generated.contains(
            "public abstract CompletableFuture<Map<Context, Profile>> batchResolve(List<Context>"
                + " contexts)"));
    assertTrue(generated.contains("implements FieldResolverBase<"));
    assertTrue(generated.contains(", BaseBatchedFieldResolver"));
    assertTrue(
        generated.contains(
            "public final CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, Object>>"
                + " invokeFieldBatchResolver("));
    assertTrue(generated.contains("new Context((FieldExecutionContext<"));
    assertTrue(generated.contains("wrappedToOriginal.get(wrappedContext)"));
    assertTrue(generated.contains("BaseBatchedFieldResolver.failedForUnknownContext("));
    assertTrue(!generated.contains("CompletableFuture<Profile> resolve(Context ctx)"));
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
            "com.example.types.Mutation",
            "com.example.types.Query_User_Arguments",
            "com.example.types.User",
            true,
            true,
            false,
            false); // isBatching = false: generates resolve()

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "Query", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"Query\", fieldName = \"user\", isSelective = false,"
                + " isBatching = false)"));
    assertTrue(generated.contains("com.example.types.Query_User_Arguments"));
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
            "com.example.types.Mutation",
            "com.example.types.Mutation_CreateUser_Arguments",
            "com.example.types.User",
            true,
            true,
            false,
            false); // isBatching = false for mutations (mutations never batch)

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "Mutation", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"Mutation\", fieldName = \"createUser\", isSelective = false,"
                + " isBatching = false)"));
    assertTrue(generated.contains("public abstract CompletableFuture<User> resolve(Context ctx)"));
    assertTrue(!generated.contains("batchResolve"));
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
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "com.example.types.Profile",
            false,
            true,
            false,
            false);

    ResolverModel resolver2 =
        new ResolverModel(
            "User",
            "orders",
            "Orders",
            "List<Order>",
            "com.example.types.User",
            "com.example.types.Query",
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "com.example.types.Order",
            false,
            true,
            false,
            false);

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "User", List.of(resolver1, resolver2));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertTrue(generated.contains("public final class UserResolvers"));
    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"profile\", isSelective = false,"
                + " isBatching = false)"));
    assertTrue(generated.contains("public abstract static class Profile"));
    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"orders\", isSelective = false,"
                + " isBatching = false)"));
    assertTrue(generated.contains("public abstract static class Orders"));
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
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "CompositeOutput.None",
            false,
            false,
            false,
            false);

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"fullName\", isSelective = false,"
                + " isBatching = false)"));
    assertTrue(generated.contains("CompositeOutput.None"));
    assertTrue(
        generated.contains("public abstract CompletableFuture<String> resolve(Context ctx)"));
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
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "com.example.types.Profile",
            false,
            true,
            true,
            false);

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "User", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    // Check that Context class has all required delegate methods
    assertTrue(generated.contains("public com.example.types.User getObjectValue()"));
    assertTrue(generated.contains("public com.example.types.Query getQueryValue()"));
    assertTrue(generated.contains("public Arguments.NoArguments getArguments()"));
    assertTrue(
        generated.contains(
            "@ResolverFor(typeName = \"User\", fieldName = \"profile\", isSelective = true,"
                + " isBatching = false)"));
    assertTrue(
        generated.contains(
            "implements FieldResolverBase.Context<com.example.types.User, com.example.types.Query,"
                + " Arguments.NoArguments, com.example.types.Profile>,"
                + " SelectiveFieldExecutionContext<com.example.types.Profile>"));
    assertTrue(generated.contains("public Object getSelections()"));
    assertTrue(
        generated.contains(
            "public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String"
                + " internalID)"));
    assertTrue(
        generated.contains(
            "public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID)"));
    assertTrue(generated.contains("public Object getRequestContext()"));
    assertTrue(
        generated.contains("public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id)"));
  }

  @Test
  void generatesContextWithQueryAndMutationMethods() {
    ResolverModel resolverModel =
        new ResolverModel(
            "Container",
            "derivedFromQuery",
            "DerivedFromQuery",
            "Integer",
            "com.example.types.Container",
            "com.example.types.Query",
            "com.example.types.Mutation",
            "Arguments.NoArguments",
            "CompositeOutput.None",
            false,
            false,
            true,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "Container", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    // Check that Context has typed query() and mutation() convenience methods
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Query> query(String selections)"));
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Query> query(String selections,"
                + " Map<String, Object> variables)"));
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Mutation> mutation(String selections)"));
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Mutation> mutation(String selections,"
                + " Map<String, Object> variables)"));
    assertTrue(
        generated.contains(
            "inner.query(selections, java.util.Map.of(), com.example.types.Query.class)"));
    assertTrue(
        generated.contains(
            "inner.mutation(selections, java.util.Map.of(), com.example.types.Mutation.class)"));
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Query> query(QueryFromAnnotation"
                + " operation)"));
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Mutation> mutation(MutationFromAnnotation"
                + " operation)"));
    assertTrue(
        generated.contains(
            "inner.query(operation, java.util.Map.of(), com.example.types.Query.class)"));
    assertTrue(
        generated.contains(
            "inner.mutation(operation, java.util.Map.of(), com.example.types.Mutation.class)"));
  }

  @Test
  void omitsMutationMethodsWhenNoMutationType() {
    ResolverModel resolverModel =
        new ResolverModel(
            "Container",
            "derivedFromQuery",
            "DerivedFromQuery",
            "Integer",
            "com.example.types.Container",
            "com.example.types.Query",
            null, // no mutation type
            "Arguments.NoArguments",
            "CompositeOutput.None",
            false,
            false,
            true,
            true);

    ResolversFileModel fileModel =
        new ResolversFileModel(
            "com.example.tenant", "com.example.grt", "Container", List.of(resolverModel));

    String generated = JavaResolverGenerator.generate(fileModel);

    // Should have query() methods but not mutation() methods
    assertTrue(
        generated.contains(
            "public CompletableFuture<com.example.types.Query> query(String selections)"));
    assertTrue(!generated.contains("mutation(String selections)"));
  }
}
