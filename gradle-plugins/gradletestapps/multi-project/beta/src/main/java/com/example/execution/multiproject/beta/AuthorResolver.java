package com.example.execution.multiproject.beta;

import com.example.execution.multiproject.beta.resolverbases.QueryResolvers;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

@Resolver
public class AuthorResolver extends QueryResolvers.Author {
  @Override
  public CompletableFuture<String> resolve(Context ctx) {
    return CompletableFuture.completedFuture("hello from multi-project beta");
  }
}
