package viaduct.tenant.runtime.execution.accessorforms;

import graphql.schema.GraphQLObjectType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.errors.ErroneousFieldException;
import viaduct.errors.FieldError;
import viaduct.errors.FrameworkException;
import viaduct.errors.TenantResolverException;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.internal.InternalContext;
import viaduct.tenant.runtime.execution.accessorforms.resolverbases.QueryResolvers;
import viaduct.tenant.runtime.execution.accessorforms.resolverbases.WidgetResolvers;

public class JavaAccessorFormsContractTest extends AccessorFormsContractTest {

  private static final class ContractEngineData implements EngineObjectData.Sync {
    private final GraphQLObjectType type;
    private final Map<String, Object> values;

    private ContractEngineData(GraphQLObjectType type, Map<String, Object> values) {
      this.type = type;
      this.values = values;
    }

    @Override
    public GraphQLObjectType getType() {
      return type;
    }

    @Override
    public @Nullable Object get(String selection) {
      Object value = values.get(selection);
      if (value instanceof Exception exception) {
        throwUnchecked(exception);
      }
      return value;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
      throw (E) throwable;
    }

    @Override
    public @Nullable Object getOrNull(String selection) {
      return get(selection);
    }

    @Override
    public boolean isPresent(String selection) {
      return values.containsKey(selection);
    }

    @Override
    public Iterable<String> getSelections() {
      return values.keySet();
    }

    @Override
    public Object fetch(
        String selection, kotlin.coroutines.Continuation<? super Object> continuation) {
      return get(selection);
    }

    @Override
    public Object fetchOrNull(
        String selection, kotlin.coroutines.Continuation<? super Object> continuation) {
      return get(selection);
    }

    @Override
    public Object fetchSelections(
        kotlin.coroutines.Continuation<? super Iterable<String>> continuation) {
      return values.keySet();
    }
  }

  private static String joinReads(String... reads) {
    return Arrays.stream(reads)
        .map(read -> read == null ? "null" : read)
        .collect(Collectors.joining("|"));
  }

  private static String classify(Callable<?> read) {
    try {
      return read.call() == null ? "null" : "value";
    } catch (Exception e) {
      return e.getClass().getSimpleName();
    }
  }

  @SafeVarargs
  private static String classify(String label, Callable<?>... reads) {
    return label
        + "="
        + Arrays.stream(reads)
            .map(JavaAccessorFormsContractTest::classify)
            .collect(Collectors.joining(","));
  }

  private static Widget rawWidget(InternalContext context, String field, @Nullable Object value) {
    GraphQLObjectType type = context.getSchema().getSchema().getObjectType("Widget");
    return new Widget(
        context, new ContractEngineData(type, Collections.singletonMap(field, value)));
  }

  @Resolver
  public static class WidgetResolver extends QueryResolvers.Widget {
    @Override
    public CompletableFuture<Widget> resolve(QueryResolvers.Widget.Context ctx) {
      return CompletableFuture.completedFuture(Widget.builder(ctx).build());
    }
  }

  @Resolver
  public static class NameResolver extends WidgetResolvers.Name {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.Name.Context ctx) {
      return CompletableFuture.completedFuture("widget");
    }
  }

  @Resolver
  public static class NicknameResolver extends WidgetResolvers.Nickname {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.Nickname.Context ctx) {
      return CompletableFuture.completedFuture(null);
    }
  }

  @Resolver
  public static class BrokenResolver extends WidgetResolvers.Broken {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.Broken.Context ctx) {
      return CompletableFuture.failedFuture(new IllegalStateException("boom"));
    }
  }

  @Resolver(objectValueFragment = "nickname")
  public static class NullReadsResolver extends WidgetResolvers.NullReads {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.NullReads.Context ctx) {
      Widget widget = ctx.getObjectValue();
      return CompletableFuture.completedFuture(
          joinReads(widget.getNicknameOrThrow(), widget.getNickname()));
    }
  }

  @Resolver(objectValueFragment = "name")
  public static class UnselectedStrictReadResolver extends WidgetResolvers.UnselectedStrictRead {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.UnselectedStrictRead.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getNicknameOrThrow());
    }
  }

  @Resolver(objectValueFragment = "name")
  public static class UnselectedSoftReadResolver extends WidgetResolvers.UnselectedSoftRead {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.UnselectedSoftRead.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getNickname());
    }
  }

  @Resolver(objectValueFragment = "alias: name")
  public static class AliasedReadsResolver extends WidgetResolvers.AliasedReads {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.AliasedReads.Context ctx) {
      Widget widget = ctx.getObjectValue();
      return CompletableFuture.completedFuture(
          joinReads(widget.getNameOrThrow("alias"), widget.getName("alias")));
    }
  }

  @Resolver(objectValueFragment = "alias: name")
  public static class UnaliasedReadResolver extends WidgetResolvers.UnaliasedRead {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.UnaliasedRead.Context ctx) {
      return CompletableFuture.completedFuture(ctx.getObjectValue().getNameOrThrow());
    }
  }

  @Resolver
  public static class BuilderUnsetReadResolver extends WidgetResolvers.BuilderUnsetRead {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.BuilderUnsetRead.Context ctx) {
      Widget built = Widget.builder(ctx).name("built").build();
      return CompletableFuture.completedFuture(built.getNickname());
    }
  }

  @Resolver(objectValueFragment = "broken")
  public static class FailureReadsResolver extends WidgetResolvers.FailureReads {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.FailureReads.Context ctx) {
      Widget resolver = ctx.getObjectValue();
      Widget stored =
          rawWidget(
              ctx,
              "name",
              new ErroneousFieldException(List.of(new FieldError("boom", null, Map.of()))));
      Widget wrapped =
          rawWidget(
              ctx,
              "name",
              new FrameworkException(
                  "boom",
                  new TenantResolverException(new IllegalStateException("boom"), "Widget.name")));
      Widget framework = rawWidget(ctx, "name", new FrameworkException("boom", null));
      Widget cancellation = rawWidget(ctx, "name", new CancellationException("boom"));
      Widget wrappedCancellation =
          rawWidget(
              ctx,
              "name",
              new FrameworkException(
                  "boom",
                  new TenantResolverException(new CancellationException("boom"), "Widget.name")));
      return CompletableFuture.completedFuture(
          String.join(
              ";",
              classify("resolver", resolver::getBrokenOrThrow, resolver::getBroken),
              classify("stored", stored::getNameOrThrow, stored::getName),
              classify("wrapped", wrapped::getNameOrThrow, wrapped::getName),
              classify("framework", framework::getNameOrThrow, framework::getName),
              classify("cancellation", cancellation::getNameOrThrow, cancellation::getName),
              classify(
                  "wrappedCancellation",
                  wrappedCancellation::getNameOrThrow,
                  wrappedCancellation::getName)));
    }
  }

  @Resolver
  public static class InvalidValueReadsResolver extends WidgetResolvers.InvalidValueReads {
    @Override
    public CompletableFuture<String> resolve(WidgetResolvers.InvalidValueReads.Context ctx) {
      Widget nonNull = rawWidget(ctx, "requiredName", null);
      Widget listElement = rawWidget(ctx, "strictTags", Collections.singletonList(null));
      Widget list = rawWidget(ctx, "tags", "not-a-list");
      Widget objectValue = rawWidget(ctx, "child", "not-an-object");
      GraphQLObjectType otherType = ctx.getSchema().getSchema().getObjectType("Other");
      ContractEngineData other =
          new ContractEngineData(otherType, Collections.singletonMap("value", "other"));
      Widget concreteType = rawWidget(ctx, "child", other);
      Widget interfaceType = rawWidget(ctx, "abstractChild", other);
      Widget objectListElement = rawWidget(ctx, "children", List.of(other));
      return CompletableFuture.completedFuture(
          String.join(
              ";",
              classify("nonNull", nonNull::getRequiredNameOrThrow, nonNull::getRequiredName),
              classify(
                  "listElement", listElement::getStrictTagsOrThrow, listElement::getStrictTags),
              classify("list", list::getTagsOrThrow, list::getTags),
              classify("object", objectValue::getChildOrThrow, objectValue::getChild),
              classify("concreteType", concreteType::getChildOrThrow, concreteType::getChild),
              classify(
                  "interfaceType",
                  interfaceType::getAbstractChildOrThrow,
                  interfaceType::getAbstractChild),
              classify(
                  "objectListElement",
                  objectListElement::getChildrenOrThrow,
                  objectListElement::getChildren)));
    }
  }
}
