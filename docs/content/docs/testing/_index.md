---
title: Testing
description: Testing Tenant Code
weight: 20
---

## Unit Testing resolvers

Resolver unit tests can extend `ViaductResolverTestBase`, which provides utilities for mocking `ExecutionContext` and handles injection. In order to test a resolver, inject it into the test class, and then call its `resolve` function in your test case with a `ExecutionContext` constructed using `ViaductResolverTestBase.contextForResolver(...)`.
