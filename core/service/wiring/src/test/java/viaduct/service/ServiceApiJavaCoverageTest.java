package viaduct.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import kotlin.Pair;
import org.junit.jupiter.api.Test;
import viaduct.service.api.ExecutionInput;
import viaduct.service.api.ExecutionResult;
import viaduct.service.api.GraphQLError;
import viaduct.service.api.SchemaId;
import viaduct.service.api.Viaduct;
import viaduct.service.api.spi.CodeInjector;
import viaduct.service.api.spi.ErrorBuilder;
import viaduct.service.api.spi.ErrorReporter;
import viaduct.service.api.spi.FlagManager;
import viaduct.service.api.spi.GlobalIDCodec;
import viaduct.service.api.spi.InputStreamSource;
import viaduct.service.api.spi.NaiveTenantModuleInjectorFactory;
import viaduct.service.api.spi.ResolverErrorBuilder;
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory;
import viaduct.service.runtime.SchemaConfiguration;
import viaduct.service.spi.JavaCodeInjector;
import viaduct.service.spi.JavaErrorReporter;
import viaduct.service.spi.JavaFlagManager;
import viaduct.service.spi.JavaGlobalIDCodec;
import viaduct.service.spi.JavaInputStreamSource;
import viaduct.service.spi.JavaResolverErrorBuilder;
import viaduct.service.wiring.graphiql.GraphiQLHtmlConfig;
import viaduct.service.wiring.graphiql.GraphiQLHtmlKt;

/**
 * Compile-and-execute coverage harness proving the {@code viaduct.service.*} {@code @StableApi}
 * surface is consumable from Java. If this class compiles, Java callers and implementers can use
 * the surface; {@link #executionSmoke()} proves the end-to-end Java execution path.
 *
 * <p>As later slices land they edit this harness (clean the call / add the newly-possible coverage
 * and remove the marker), so the marker count is a live progress meter for the initiative.
 */
class ServiceApiJavaCoverageTest {

  // Minimal schema: the full schema serves { __typename } with no tenants and no resolvers.
  private static final String SDL =
      "extend type Query @scope(to: [\"viaduct-public\"]) { greeting: String }";

  @Test
  void executionSmoke() {
    final var viaduct = buildMinimalViaduct();
    final var input =
        ExecutionInput.Companion.create("{ __typename }", null, Collections.emptyMap(), null);
    final ExecutionResult result = viaduct.executeAsync(input, SchemaId.Full.INSTANCE).join();

    final Map<String, Object> data = result.getData();
    assertNotNull(data, "expected data for { __typename }");
    assertEquals("Query", data.get("__typename"));
    assertTrue(result.getErrors().isEmpty(), "expected no errors");
    assertNotNull(result.toSpecification());
  }

  @Test
  void executeAsyncOverloadsAndAppliedScopes() {
    final var viaduct = buildMinimalViaduct();
    final var input = ExecutionInput.Companion.builder().operationText("{ __typename }").build();

    // All three executeAsync overloads are Java-callable
    final CompletableFuture<ExecutionResult> f1 = viaduct.executeAsync(input);
    final CompletableFuture<ExecutionResult> f2 =
        viaduct.executeAsync(input, SchemaId.Full.INSTANCE);
    final CompletableFuture<ExecutionResult> f3 =
        viaduct.executeAsync(input, SchemaId.Full.INSTANCE, ForkJoinPool.commonPool());
    assertEquals("Query", Objects.requireNonNull(f1.join().getData()).get("__typename"));
    assertEquals("Query", Objects.requireNonNull(f2.join().getData()).get("__typename"));
    assertEquals("Query", Objects.requireNonNull(f3.join().getData()).get("__typename"));

    // getAppliedScopes on the full schema is always valid. The None/Scoped ids are constructed
    // here for compile coverage but not passed to getAppliedScopes: None is a sentinel and
    // getAppliedScopes on an unregistered schema throws by design.
    viaduct.getAppliedScopes(SchemaId.Full.INSTANCE);
    final SchemaId none = SchemaId.None.INSTANCE;
    final SchemaId scoped = new SchemaId.Scoped("public", Set.of("viaduct-public"));
    assertNotNull(none);
    assertNotNull(scoped);
    // NOTE: Viaduct.execute(...) is a suspend fun — intentionally NOT covered
  }

  @Test
  void spiImplementationsAreUsableFromJava() throws IOException {
    // Each Java-implementable SPI (implementations live in the spi/ package).
    final ErrorReporter reporter = new JavaErrorReporter();
    reporter.reportResolverError(new RuntimeException("x"), "msg", new ErrorReporter.Metadata());
    final ErrorReporter noopReporter = ErrorReporter.Companion.getNOOP();
    assertNotNull(noopReporter);

    final ResolverErrorBuilder resolverErrorBuilder = new JavaResolverErrorBuilder();
    final List<GraphQLError> errs =
        resolverErrorBuilder.exceptionToGraphQLError(
            new RuntimeException(), ErrorReporter.Metadata.Companion.getEMPTY());
    assertTrue(errs == null || errs.isEmpty());
    final ResolverErrorBuilder noopBuilder = ResolverErrorBuilder.Companion.getNOOP();
    assertNotNull(noopBuilder);

    final GlobalIDCodec codec = new JavaGlobalIDCodec();
    final Pair<String, String> parts = codec.deserialize(codec.serialize("User", "42"));
    assertEquals("User", parts.getFirst());

    final FlagManager flagManager = new JavaFlagManager();
    flagManager.isEnabled(FlagManager.Flags.ENABLE_SELECTIVE_OER_KEYS);
    assertNotNull(FlagManager.disabled.INSTANCE); // FlagManager.disabled is Java-legal
    // TODO: FlagManager.default is unreferenceable from Java ('default' is a reserved word);
    //       add coverage once it renames to FlagManager.Default.

    final CodeInjector codeInjector = new JavaCodeInjector();
    codeInjector.getProvider(String.class);
    assertNotNull(CodeInjector.Companion.getNaive());

    final InputStreamSource source = new JavaInputStreamSource();
    source
        .openStream()
        .close(); // @Throws(IOException) lets Java declare/handle the checked exception
    assertNotNull(
        InputStreamSource.fromString(
            "hi", "greeting")); // already @JvmStatic — clean, no .Companion

    // TenantModuleInjectorFactory.bootstrap/finalize are suspend
    // TODO: add a direct Java implementation once suspend is dropped. Until then the concrete
    //       subclasses are Java-usable and cover the type:
    final var shared = new SharedTenantModuleInjectorFactory(CodeInjector.Companion.getNaive());
    assertNotNull(shared);
    assertNotNull(NaiveTenantModuleInjectorFactory.INSTANCE);
  }

  @Test
  void factoriesBuildersAndErrorTypesAreUsableFromJava() {
    // ExecutionInput builder + getters.
    final var input =
        ExecutionInput.Companion.builder()
            .operationText("{ __typename }")
            .variables(Collections.emptyMap())
            .build();
    assertNotNull(input.getOperationText());
    assertNotNull(input.getExecutionId());

    // ErrorBuilder fluent -> GraphQLError, plus a directly-constructed GraphQLError.
    final GraphQLError built =
        ErrorBuilder.Companion.newError().message("boom").extension("code", "E_TEST").build();
    assertEquals("boom", built.getMessage());
    final var direct = new GraphQLError("msg", null, null, Map.of());
    assertNotNull(direct.getMessage());

    // SchemaScopeInfo (wiring)
    final var scopeInfo = new SchemaScopeInfo("public", Set.of("viaduct-public"));
    assertEquals(Set.of("viaduct-public"), scopeInfo.getScopesToApply());

    // GraphiQL HTML
    assertNotNull(GraphiQLHtmlKt.graphiQLHtml());
    assertNotNull(GraphiQLHtmlKt.graphiQLHtml(new GraphiQLHtmlConfig()));
  }

  private static Viaduct buildMinimalViaduct() {
    final var schemaConfig =
        SchemaConfiguration.Companion.fromSdl(
            SDL,
            Set.of(new SchemaConfiguration.ScopeConfig("public", Set.of("viaduct-public"))),
            false);
    return new ViaductBuilder()
        .withFlagManager(new JavaFlagManager())
        // No public "no tenants" switch (withNoTenantAPIBootstrapper is internal); the public
        // path supplies an injector factory and discovers zero tenants on this classpath.
        .withTenantModuleInjectorFactory(NaiveTenantModuleInjectorFactory.INSTANCE)
        .withLenientResolverValidation(true)
        .withSchemaConfiguration(schemaConfig)
        .build();
  }

  /**
   * Never invoked — exists purely to prove these calls COMPILE from Java. Kept out of the executed
   * tests because {@link BasicViaductFactory#create} discovers tenant modules from the classpath
   * and {@code SchemaConfiguration.getDEFAULT()} loads schema resources, neither of which exists
   * here; building/executing is already covered by {@link #executionSmoke()}.
   */
  @SuppressWarnings("unused")
  private static void compileOnlyReferences() {
    // BasicViaductFactory is a Kotlin object -> .INSTANCE from Java
    final var factory = BasicViaductFactory.INSTANCE;
    final Viaduct v1 = factory.create();
    final Viaduct v2 = factory.create(NaiveTenantModuleInjectorFactory.INSTANCE);
    final Viaduct v3 = factory.create(NaiveTenantModuleInjectorFactory.INSTANCE, List.of());

    // Every public, non-experimental ViaductBuilder setter is Java-callable.
    final Viaduct v4 =
        new ViaductBuilder()
            .withFlagManager(new JavaFlagManager())
            .withTenantModuleInjectorFactory(NaiveTenantModuleInjectorFactory.INSTANCE)
            .withScopedSchemas(List.of(new SchemaScopeInfo("public", Set.of("viaduct-public"))))
            .withSchemaConfiguration(SchemaConfiguration.Companion.getDEFAULT()) // S5
            .withMeterRegistry(new SimpleMeterRegistry())
            .withResolverErrorReporter(ErrorReporter.Companion.getNOOP()) // S5
            .withDataFetcherErrorBuilder(ResolverErrorBuilder.Companion.getNOOP()) // S5
            .withGlobalIDCodec(new JavaGlobalIDCodec())
            .withLenientResolverValidation(true)
            .build();
    // note: SchemaConfiguration.fromSchema(ViaductSchema) requires a non-service (engine) type,
    //       so it is intentionally not exercised here; the smoke uses fromSdl instead.
  }
}
