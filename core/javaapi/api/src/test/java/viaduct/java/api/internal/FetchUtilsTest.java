package viaduct.java.api.internal;

import static graphql.Scalars.GraphQLString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.schema.GraphQLObjectType;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import viaduct.errors.UnsetFieldException;

class FetchUtilsTest {
  @Test
  void fetchOrNullPreservesTypedValueWithoutBlocking() {
    CompletableFuture<TestValue> source = new CompletableFuture<>();

    CompletableFuture<TestValue> result = FetchUtils.fetchOrNull(() -> source);

    assertThat(result).isNotDone();
    TestValue value = new TestValue("value");
    source.complete(value);
    assertThat(result).isCompletedWithValue(value);
  }

  @Test
  void fetchOrNullReturnsNullForAsyncException() {
    CompletableFuture<String> result =
        FetchUtils.fetchOrNull(
            () -> CompletableFuture.failedFuture(new IllegalStateException("failed")));

    assertThat(result).isCompletedWithValue(null);
  }

  @Test
  void fetchOrDefaultReturnsDefaultForSynchronousUnsetField() {
    GraphQLObjectType type =
        GraphQLObjectType.newObject()
            .name("TestObject")
            .field(field -> field.name("missing").type(GraphQLString))
            .build();
    UnsetFieldException unsetField = new UnsetFieldException("missing", type, null);

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault(
            "default",
            () -> {
              throw unsetField;
            });

    assertThat(result).isCompletedWithValue("default");
  }

  @Test
  void fetchOrDefaultReturnsDefaultForCompletionWrappedException() {
    CompletionException failure = new CompletionException(new IllegalStateException("failed"));

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault("default", () -> CompletableFuture.failedFuture(failure));

    assertThat(result).isCompletedWithValue("default");
  }

  @Test
  void fetchOrDefaultReturnsDefaultForCauseLessCompletionException() {
    CompletionException failure = new CompletionException("failed", null);

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault("default", () -> CompletableFuture.failedFuture(failure));

    assertThat(result).isCompletedWithValue("default");
  }

  @Test
  void fetchOrDefaultPreservesSuccessfulNull() {
    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault("default", () -> CompletableFuture.completedFuture(null));

    assertThat(result).isCompletedWithValue(null);
  }

  @Test
  void fetchOrDefaultPropagatesDirectCancellation() {
    CancellationException cancellation = new CancellationException("cancelled");

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault("default", () -> CompletableFuture.failedFuture(cancellation));

    assertThat(result).isCancelled();
    assertThatThrownBy(result::join).isSameAs(cancellation);
  }

  @Test
  void fetchOrDefaultPropagatesCompletionWrappedCancellation() {
    CancellationException cancellation = new CancellationException("cancelled");
    CompletionException failure = new CompletionException(cancellation);

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault("default", () -> CompletableFuture.failedFuture(failure));

    assertThat(result).isCancelled();
    assertThatThrownBy(result::join).isSameAs(cancellation);
  }

  @Test
  void fetchOrDefaultPropagatesSynchronousCancellation() {
    CancellationException cancellation = new CancellationException("cancelled");

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault(
            "default",
            () -> {
              throw cancellation;
            });

    assertThat(result).isCancelled();
    assertThatThrownBy(result::join).isSameAs(cancellation);
  }

  @Test
  void fetchOrDefaultPropagatesAsyncError() {
    AssertionError error = new AssertionError("failed");

    CompletableFuture<String> result =
        FetchUtils.fetchOrDefault("default", () -> CompletableFuture.failedFuture(error));

    assertThatThrownBy(result::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseReference(error);
  }

  @Test
  void fetchOrDefaultPropagatesSynchronousError() {
    AssertionError error = new AssertionError("failed");

    assertThatThrownBy(
            () ->
                FetchUtils.fetchOrDefault(
                    "default",
                    () -> {
                      throw error;
                    }))
        .isSameAs(error);
  }

  @Test
  void fetchOrNullRejectsNullStage() {
    assertThatThrownBy(() -> FetchUtils.fetchOrNull(() -> null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("block returned null");
  }

  private record TestValue(String value) {}
}
