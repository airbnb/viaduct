package viaduct.java.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a build-time validated GraphQL operation on a {@link
 * viaduct.java.api.documents.QueryFromAnnotation} or {@link
 * viaduct.java.api.documents.MutationFromAnnotation} class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphQLOperation {
  /** A document containing exactly one GraphQL query or mutation. */
  String value();
}
