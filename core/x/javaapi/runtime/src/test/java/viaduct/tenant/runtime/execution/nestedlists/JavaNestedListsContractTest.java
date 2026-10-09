package viaduct.tenant.runtime.execution.nestedlists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.ResolvedEngineObjectData;
import viaduct.errors.FrameworkException;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.tenant.runtime.execution.nestedlists.resolverbases.QueryResolvers;

public class JavaNestedListsContractTest extends NestedListsContractTest {
  @Test
  void rawNestedInputsConvertMapsEnumNamesAndTemporalStringsAtLeaves() {
    Map<String, Object> data =
        Map.ofEntries(
            Map.entry("children2", matrix(Map.of("value", 9))),
            Map.entry("children3", cube(Map.of("value", 9))),
            Map.entry("statuses2", matrix("ACTIVE")),
            Map.entry("statuses3", cube("ACTIVE")),
            Map.entry("dateTimes2", matrix(DATE_TIME.toString())),
            Map.entry("dateTimes3", cube(DATE_TIME.toString())),
            Map.entry("dates2", matrix(DATE.toString())),
            Map.entry("dates3", cube(DATE.toString())),
            Map.entry("times2", matrix(TIME.toString())),
            Map.entry("times3", cube(TIME.toString())));
    NestedInput input = new NestedInput(null, data, null);
    assertThat(input.getChildren2().get(0).get(0).getValue()).isEqualTo(9);
    assertThat(input.getChildren3().get(0).get(0).get(0).getValue()).isEqualTo(9);
    assertThat(input.getChildren2().get(1)).isNull();
    assertThat(input.getChildren3().get(0).get(0).get(1)).isNull();
    assertTemporalAndEnumInputs(input);
  }

  @Test
  void invalidNestedInputLeavesFailInsideTheAccessor() {
    NestedInput input =
        new NestedInput(null, Map.of("children2", matrix(9), "children3", cube(9)), null);
    assertThatThrownBy(input::getChildren2)
        .isInstanceOf(FrameworkException.class)
        .hasRootCauseInstanceOf(ClassCastException.class);
    assertThatThrownBy(input::getChildren3)
        .isInstanceOf(FrameworkException.class)
        .hasRootCauseInstanceOf(ClassCastException.class);
  }

  @Test
  void invalidInputListContainersFailInsideTheAccessorAtEveryDepth() {
    Map<String, java.util.function.Function<NestedInput, ?>> accessors =
        Map.ofEntries(
            Map.entry("children2", NestedInput::getChildren2),
            Map.entry("children3", NestedInput::getChildren3),
            Map.entry("statuses2", NestedInput::getStatuses2),
            Map.entry("statuses3", NestedInput::getStatuses3),
            Map.entry("ids2", NestedInput::getIds2),
            Map.entry("ids3", NestedInput::getIds3),
            Map.entry("dateTimes2", NestedInput::getDateTimes2),
            Map.entry("dateTimes3", NestedInput::getDateTimes3),
            Map.entry("dates2", NestedInput::getDates2),
            Map.entry("dates3", NestedInput::getDates3),
            Map.entry("times2", NestedInput::getTimes2),
            Map.entry("times3", NestedInput::getTimes3));
    accessors.forEach(
        (field, accessor) -> {
          List<Object> invalidContainers =
              field.endsWith("3")
                  ? List.of(9, List.of(9), List.of(List.of(9)))
                  : List.of(9, List.of(9));
          for (Object invalid : invalidContainers) {
            NestedInput input = new NestedInput(null, Map.of(field, invalid), null);
            assertThatThrownBy(() -> accessor.apply(input))
                .as("%s with malformed container %s", field, invalid)
                .isInstanceOf(FrameworkException.class);
          }
        });
  }

  private static void assertTemporalAndEnumInputs(NestedInput input) {
    assertThat(input.getStatuses2()).isEqualTo(matrix(Status.ACTIVE));
    assertThat(input.getStatuses3()).isEqualTo(cube(Status.ACTIVE));
    assertThat(input.getDateTimes2()).isEqualTo(matrix(DATE_TIME));
    assertThat(input.getDateTimes3()).isEqualTo(cube(DATE_TIME));
    assertThat(input.getDates2()).isEqualTo(matrix(DATE));
    assertThat(input.getDates3()).isEqualTo(cube(DATE));
    assertThat(input.getTimes2()).isEqualTo(matrix(TIME));
    assertThat(input.getTimes3()).isEqualTo(cube(TIME));
  }

  private static void assertInput(NestedInput input, GlobalID<Person> id) {
    assertSpecializedLeaves(
        Map.ofEntries(
            Map.entry("statuses2", input.getStatuses2()),
                Map.entry("statuses3", input.getStatuses3()),
            Map.entry("ids2", input.getIds2()), Map.entry("ids3", input.getIds3()),
            Map.entry("dateTimes2", input.getDateTimes2()),
                Map.entry("dateTimes3", input.getDateTimes3()),
            Map.entry("dates2", input.getDates2()), Map.entry("dates3", input.getDates3()),
            Map.entry("times2", input.getTimes2()), Map.entry("times3", input.getTimes3()),
            Map.entry("ints2", input.getInts2()), Map.entry("ints3", input.getInts3())),
        Status.ACTIVE,
        id);
    assertThat(input.getChildren2().get(0).get(0).getValue()).isEqualTo(9);
    assertThat(input.getChildren3().get(0).get(0).get(0).getValue()).isEqualTo(9);
    assertThat(input.getChildren2().get(0).get(0).getFallback()).isEqualTo(17);
    assertThat(input.getChildren3().get(0).get(0).get(0).getFallback()).isEqualTo(17);
    assertThat(input.getChildren2().get(1)).isNull();
    assertThat(input.getChildren3().get(0).get(0).get(1)).isNull();
    assertThat(input.getStrict().get(0).get(0).getValue()).isEqualTo(9);
  }

  private static NestedInput input(ExecutionContext ctx, GlobalID<Person> id) {
    Child child = Child.builder(ctx).value(9).build();
    return NestedInput.builder(ctx)
        .children2(matrix(child))
        .children3(cube(child))
        .statuses2(matrix(Status.ACTIVE))
        .statuses3(cube(Status.ACTIVE))
        .ids2(matrix(id))
        .ids3(cube(id))
        .dateTimes2(matrix(DATE_TIME))
        .dateTimes3(cube(DATE_TIME))
        .dates2(matrix(DATE))
        .dates3(cube(DATE))
        .times2(matrix(TIME))
        .times3(cube(TIME))
        .ints2(matrix(9))
        .ints3(cube(9))
        .strict(List.of(List.of(child)))
        .build();
  }

  private static NestedOutput output(ExecutionContext ctx, GlobalID<Person> id) {
    Person person = Person.builder(ctx).id(id).value(9).build();
    return NestedOutput.builder(ctx)
        .children2(matrix(person))
        .children3(cube(person))
        .interfaces2(JavaNestedListsContractTest.<Named>matrix(person))
        .interfaces3(JavaNestedListsContractTest.<Named>cube(person))
        .unions2(JavaNestedListsContractTest.<Choice>matrix(person))
        .unions3(JavaNestedListsContractTest.<Choice>cube(person))
        .statuses2(matrix(Status.ACTIVE))
        .statuses3(cube(Status.ACTIVE))
        .ids2(matrix(id))
        .ids3(cube(id))
        .dateTimes2(matrix(DATE_TIME))
        .dateTimes3(cube(DATE_TIME))
        .dates2(matrix(DATE))
        .dates3(cube(DATE))
        .times2(matrix(TIME))
        .times3(cube(TIME))
        .ints2(matrix(9))
        .ints3(cube(9))
        .strict(List.of(List.of(person)))
        .build();
  }

  private static void assertOutput(NestedOutput output, GlobalID<Person> id, boolean aliased) {
    List<List<Named>> interfaces = output.getInterfaces2(aliased ? "aliasedInterfaces" : null);
    List<List<List<Choice>>> unions = output.getUnions3(aliased ? "aliasedUnions" : null);
    assertThat(output.getChildren2().get(0).get(0).getValue()).isEqualTo(9);
    assertThat(output.getChildren3().get(0).get(0).get(0).getValue()).isEqualTo(9);
    assertThat(output.getChildren2().get(1)).isNull();
    assertThat(output.getChildren3().get(0).get(0).get(1)).isNull();
    assertThat(interfaces.get(0).get(0).getValue()).isEqualTo(9);
    assertThat(interfaces.get(1)).isNull();
    assertThat(output.getInterfaces3().get(0).get(0).get(0).getValue()).isEqualTo(9);
    assertThat(output.getUnions2().get(0).get(0)).isInstanceOf(Person.class);
    assertThat(((Person) unions.get(0).get(0).get(0)).getValue()).isEqualTo(9);
    assertThat(unions.get(1)).isNull();
    assertSpecializedLeaves(
        Map.ofEntries(
            Map.entry("statuses2", output.getStatuses2()),
                Map.entry("statuses3", output.getStatuses3(aliased ? "aliasedStatuses" : null)),
            Map.entry("ids2", output.getIds2(aliased ? "aliasedIDs" : null)),
                Map.entry("ids3", output.getIds3()),
            Map.entry("dateTimes2", output.getDateTimes2()),
                Map.entry("dateTimes3", output.getDateTimes3(aliased ? "aliasedDateTimes" : null)),
            Map.entry("dates2", output.getDates2()), Map.entry("dates3", output.getDates3()),
            Map.entry("times2", output.getTimes2()), Map.entry("times3", output.getTimes3()),
            Map.entry("ints2", output.getInts2()), Map.entry("ints3", output.getInts3())),
        Status.ACTIVE,
        id);
    assertThat(output.getStrict().get(0).get(0).getValue()).isEqualTo(9);
    assertThat(output.getChildren2()).isSameAs(output.getChildren2());
    assertThat(output.getUnions3(aliased ? "aliasedUnions" : null)).isSameAs(unions);
    NestedFields interfaceView = output;
    assertThat(interfaceView.getChildren3OrThrow()).isSameAs(output.getChildren3());
    assertThat(interfaceView.getStatuses3(aliased ? "aliasedStatuses" : null))
        .isEqualTo(cube(Status.ACTIVE));
    assertThat(interfaceView.getDateTimes3(aliased ? "aliasedDateTimes" : null))
        .isEqualTo(cube(DATE_TIME));
    assertThat(interfaceView.getIds2OrThrow(aliased ? "aliasedIDs" : null)).isEqualTo(matrix(id));
  }

  @Resolver
  public static class VerifyResolver extends QueryResolvers.Verify {
    @Override
    public CompletableFuture<String> resolve(QueryResolvers.Verify.Context ctx) {
      GlobalID<Person> id = ctx.globalIDFor(Person.Reflection, "1");
      NestedInput input = input(ctx, id);
      assertInput(input, id);
      assertInput(input.toBuilder().build(), id);
      NestedOutput built = output(ctx, id);
      assertOutput(built, id, false);
      NestedOutput nulls =
          built.toBuilder()
              .children2(null)
              .interfaces3(null)
              .unions2(null)
              .statuses2(null)
              .ids3(null)
              .dateTimes2(null)
              .dates3(null)
              .times2(null)
              .build();
      assertThat(nulls.getChildren2()).isNull();
      assertThat(nulls.getInterfaces3()).isNull();
      assertThat(nulls.getUnions2()).isNull();
      assertThat(nulls.getStatuses2()).isNull();
      assertThat(nulls.getIds3()).isNull();
      assertThat(nulls.getDateTimes2()).isNull();
      assertThat(nulls.getDates3()).isNull();
      assertThat(nulls.getTimes2()).isNull();
      InternalContext internal = (InternalContext) ctx;
      NestedOutput raw =
          new NestedOutput(
              internal,
              new ResolvedEngineObjectData(
                  internal.getSchema().getSchema().getObjectType("NestedOutput"),
                  Map.ofEntries(
                      Map.entry("dateTimes2", matrix(DATE_TIME.toString())),
                      Map.entry("alias", cube(DATE_TIME.toString())),
                      Map.entry("dates2", matrix(DATE.toString())),
                      Map.entry("dates3", cube(DATE.toString())),
                      Map.entry("times2", matrix(TIME.toString())),
                      Map.entry("times3", cube(TIME.toString())))));
      assertThat(raw.getDateTimes2()).isEqualTo(matrix(DATE_TIME));
      assertThat(raw.getDateTimes3("alias")).isEqualTo(cube(DATE_TIME));
      assertThat(raw.getDates2()).isEqualTo(matrix(DATE));
      assertThat(raw.getDates3()).isEqualTo(cube(DATE));
      assertThat(raw.getTimes2()).isEqualTo(matrix(TIME));
      assertThat(raw.getTimes3()).isEqualTo(cube(TIME));
      NestedOutput malformed =
          new NestedOutput(
              internal,
              new ResolvedEngineObjectData(
                  internal.getSchema().getSchema().getObjectType("NestedOutput"),
                  Map.of("children2", List.of(9), "children3", List.of(List.of(9)))));
      assertThatThrownBy(malformed::getChildren2)
          .isInstanceOf(viaduct.errors.TenantUsageException.class);
      assertThatThrownBy(malformed::getChildren3)
          .isInstanceOf(viaduct.errors.TenantUsageException.class);

      NestedInput defaults = NestedInput.builder(ctx).strict(List.of()).build();
      assertThat(defaults.getChildren2().get(0).get(0).getValue()).isEqualTo(7);
      assertThat(defaults.getChildren2().get(0).get(1)).isNull();
      assertThat(defaults.getChildren2().get(1)).isNull();
      assertThat(defaults.toBuilder().build().getDefaultStatuses())
          .isEqualTo(Arrays.asList(Arrays.asList(Arrays.asList(Status.ACTIVE, null), null), null));
      NestedInput explicitNull = defaults.toBuilder().children2(null).defaultStatuses(null).build();
      assertThat(explicitNull.getChildren2()).isNull();
      assertThat(explicitNull.getDefaultStatuses()).isNull();

      Query_Echo_Arguments arguments =
          Query_Echo_Arguments.builder(ctx).input(input).ids2(matrix(id)).ids3(cube(id)).build();
      assertThat(arguments.getIds2()).isEqualTo(matrix(id));
      assertThat(arguments.getIds3()).isEqualTo(cube(id));
      assertThat(arguments.getInputData().get("ids2")).isEqualTo(matrix(ctx.serialize(id)));
      assertThat(arguments.getInputData().get("ids3")).isEqualTo(cube(ctx.serialize(id)));
      Query.echo().input(input).ids2(matrix(id)).ids3(cube(id)).build();

      return ctx.query(ECHO_SELECTION, arguments.getInputData(), Query.class)
          .thenApply(
              query -> {
                assertOutput(query.getEcho(), id, true);
                return "ok";
              });
    }
  }

  @Resolver
  public static class EchoResolver extends QueryResolvers.Echo {
    @Override
    public CompletableFuture<NestedOutput> resolve(QueryResolvers.Echo.Context ctx) {
      GlobalID<Person> id = ctx.globalIDFor(Person.Reflection, "1");
      assertInput(ctx.getArguments().getInput(), id);
      assertThat(ctx.getArguments().getIds2()).isEqualTo(matrix(id));
      assertThat(ctx.getArguments().getIds3()).isEqualTo(cube(id));
      return CompletableFuture.completedFuture(output(ctx, id));
    }
  }

  @Resolver
  public static class ConnectionResolver extends QueryResolvers.Connection {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public CompletableFuture<PersonConnection> resolve(QueryResolvers.Connection.Context ctx) {
      GlobalID<Person> id = ctx.globalIDFor(Person.Reflection, "1");
      PersonConnection connection =
          PersonConnection.builder(ctx)
              .ids2(matrix(id))
              .ids3(cube(id))
              .strictIDs(List.of(List.of(id)))
              .fromEdges(List.of())
              .build();
      assertThat(connection.getIds2()).isEqualTo(matrix(id));
      assertThat(connection.getIds3()).isEqualTo(cube(id));
      PersonConnection copy = connection.toBuilder().build();
      assertThat(copy.getIds2()).isEqualTo(matrix(id));
      assertThat(copy.getIds3()).isEqualTo(cube(id));
      PersonConnection nulls = connection.toBuilder().ids2(null).ids3(null).build();
      assertThat(nulls.getIds2()).isNull();
      assertThat(nulls.getIds3()).isNull();
      assertThatThrownBy(
              () ->
                  PersonConnection.builder(ctx)
                      .strictIDs(Arrays.asList((List<GlobalID<Person>>) null)))
          .isInstanceOf(viaduct.errors.TenantUsageException.class);
      assertThatThrownBy(
              () ->
                  PersonConnection.builder(ctx)
                      .strictIDs(List.of(Arrays.asList((GlobalID<Person>) null))))
          .isInstanceOf(viaduct.errors.TenantUsageException.class);
      assertThatThrownBy(() -> PersonConnection.builder(ctx).ids2((List) List.of(9)))
          .isInstanceOf(viaduct.errors.TenantUsageException.class);
      return CompletableFuture.completedFuture(copy);
    }
  }
}
