package com.example.execution.multiproject.beta;

import com.example.execution.multiproject.beta.resolverbases.MutationResolvers;
import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;

@Resolver
public class EchoMutationResolver extends MutationResolvers.Echo {
  @Override
  public CompletableFuture<String> resolve(Context ctx) {
    return CompletableFuture.completedFuture(ctx.getArguments().getMessage());
  }
}
