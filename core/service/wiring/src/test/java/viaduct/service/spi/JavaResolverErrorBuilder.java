package viaduct.service.spi;

import java.util.List;
import viaduct.service.api.GraphQLError;
import viaduct.service.api.spi.ErrorReporter;
import viaduct.service.api.spi.ResolverErrorBuilder;

/** Java implementation of the {@link ResolverErrorBuilder} SPI. */
public final class JavaResolverErrorBuilder implements ResolverErrorBuilder {
  @Override
  public List<GraphQLError> exceptionToGraphQLError(
      Throwable throwable, ErrorReporter.Metadata errorMetadata) {
    // Returning null delegates to the framework's default error handling.
    return null;
  }
}
