package viaduct.java.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a reusable named GraphQL fragment on a {@link
 * viaduct.java.api.documents.FragmentFromAnnotation} class.
 *
 * <p>The fragment is extracted and validated against the tenant schema at build time. It may be
 * spread from resolver required selections and {@code @GraphQLOperation} documents in the same
 * tenant module.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphQLFragment {
  /** A document containing exactly one named GraphQL fragment definition. */
  String value();
}
