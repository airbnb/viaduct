package com.example.viadapp.resolvers;

import com.example.viadapp.resolvers.resolverbases.QueryResolvers;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

@Resolver
public class GreetingResolver extends QueryResolvers.Greeting {
  @Override
  public CompletableFuture<String> resolve(Context ctx) {
    return CompletableFuture.completedFuture("Hello, World!");
  }
}
