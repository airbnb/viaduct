# Introduction

This document describes the direct-to-bytecode generator.  This generator translates an input GraphQL schema into a set of type-safe "GraphQL Representational Types" (GRTs) that allow tenant code to read and write values in the Viaduct data graph.

Errors in this code are likely to manifest themselves in ways that are very difficult for tenants to diagnose.  Such errors would be  an unacceptable burden on tenants, and even just a few of them would breed tenant distrust in the Viaduct build process, resulting in an increased support burden ("My code's not working even though it is correct, this must be a codegen problem, can you look into it?").  "Errors" here aren't limited to errors that lead to incorrect behavior: incorrect metadata in classfiles can lead to mysterious type-errors for tenants at compile time that would be equally vexing to diagnose.

At the same time, the correctness of this code depends on a lot of obscure details that were learned though a process of trial and error.  We've striven to document these details in the code itself, as well as in the companion [_Learnings_](learnings.md) document.

To help prevent regressions, we've carefully structured it to improve its maintainability.  This document describes that structure - and also our strategy for testing it.


# Testing practices

Before getting into the details of our design and testing strategy, here's a simple overview of our testing practices.  As will be discussed in the testing section, our test suite includes tests that run on the entire central schema.  These can take 10-20 minutes to complete -- too long for the inner-loop of development.  Therefore, these tests have been marked `manual` and aren't automatically run from the command-line (`bazel ...`) or from the IDE.

Instead, we've created a `yak` script for running the full suite of tests:

```
    yak script projects/viaduct/build-src:prepush-test
```

which, as the name implies, is intended to be run prior to any push of changes to Treehouse.  If you're making changes to the code generator, please be sure to run this test suite!

We have found that the unit tests of tenant code provide an additional layer of testing for GRTs.  The easiest way to run these is to simply create a PR against your code changes and let CI run those tests for you.

TODO: explain how to do Airdev-only pushes, and SxS testing as well.


# Dependencies and Layers

## Kotlin-flavored bytecode

This direct-to-bytecode generator replaces the previous Viaduct code generator which generated Kotlin source code, which was then compiled by the Kotlin compiler.  As the Viaduct central schema grew, and became increasingly interconnected, the time required by the Kotlin compiler grew unacceptably long (despite significant investments in build-time infrastructure to keep it short).

This replacement generator, therefore, is generating _Kotlin_ bytecode, not Java bytecode.  Kotlin has a number of features (e.g., suspend functions and companion objects) that get compiled into bytecodes in idiosyncratic ways.  To simplify our bytecode generation, we did change the previous code generator to remove as many of these idiosyncracies as possible.  For example, we vastly reduced the use of companion objects and interface-default methods.  This required changes to tenant code, but that effort significantly simplified the bytecode-generation process.

That said, we couldn't eliminate all usage of Kotlin features.  For example, the use of "suspending getters" in the GRTs for object types is important to Viaduct's overall asynchrony strategy.  Thus, we needed to reproduce the Kotlin compiler's behavior at least partially.

## Km: @Metadata and kotlinx-metadata

One critical behavior we needed to reproduce is the generation of [`@Metadata`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-metadata) annotations on the classfiles we generate.  This annotation uses an undocumented, binary encoding to represent Kotlin-specific metadata _not_ captured in the JVM classfile format.  Examples include whether or not a function is a suspending function, and metadata associated with generics.

Fortunately, the Kotlin distribution comes with a library called [`kotlinx-metadata`](https://github.com/JetBrains/kotlin/blob/master/libraries/kotlinx-metadata/jvm/ReadMe.md) for reading and writing such metadata.  We use this library extensively in our code generator.  In our code, we use the prefix "Km" and "kM" to indicate types, functions, and variables associated with this library, and in this current document will use the acroym KM to refer to the library as a whole.

The KM library is a "shallow" representation of just the _signatures_ of Kotlin classes, functions, and properties.  This means that the KM library does _not_ represent the actual bytecode in a function, for example, but just the functions name, result type, and parameter names.  Also, the representations of Kotlin types are not "linked:" if two different functions return a Kotlin `Int`, for example, they each will have distinct objects to represent that type; and those objects will not be `.equals` to each other, your code needs to inspect them to determine equivalency.

## Ct: Javassist

In addition to generating `@Metadata` annotations, we obviously also need to generate the bytecodes themselves.  To aid in that process, we depend upon the [Javassist](https://www.javassist.org/tutorial/tutorial.html) bytecode library.  Javassist is not the most popular open source bytecode library.  However, when it comes to _writing_ bytecodes, Javassist is unique in that it has a built-in Java(-lite) compiler.  That is, to generate the bytecodes that make up the body of a method, Javassist allows you to give Java-like source code rather than forcing you to generate the low-level bytecodes themselves.  We felt this would _vastly_ simplify the bytecode generator.

In our code, we use the prefix "Ct" and "cT" to indicate types, functions, and variables associated with this library.  This prefix was chosen because Javassist itself prefixes many of is class names with "Ct" (which stands for "compile time," FWIW).

## Layering

In our initial implementation, we had independent code paths to produce the KM data structures needed to generate the `@Metadata` annotations and the Javassist data structures needed to generate the classfiles.  It was immediately apparent that, when it came to generating the classfile signature information - e.g., the names, return-types, and argument types of methods - our code was highly repetitive: We were doing the same thing to generate both the KM and Ct data structures.  In addition, we were baking-in a lot of Kotlin-specific logic into the generation of the Ct data structures (eg, specifics regarding suspend-function continuations) - and we were repeating this logic in multiple places.

We considered writing our own code-generation API, specialized to the needs of Viaduct, that would encapsulate KM and Ct.  However, we rejected such a totalizing abstraction because the KM library in particular is quite large, so a lot of our code would've been just empty wrappers around that library.

Instead, we chose to decompose the code generation process into layers: the first-layer translates the GraphQL schema into a KM-based representation of the GRTs plus Javassist-compatible method bodies for methods, constructors, and other executable items.  The second layer (in the code called "KmToCt") consumes the KM-based signature metadata and Ct-compatible method bodies and generates the classfiles from those.  In terms of separation of concerns, the first layer encapsulates the Viaduct-specific logic of translating GraphQL schema into Kotlin GRTs, while the second layer encapsulates (and keeps DRY) the Kotlin-specific logic of translating Kotlin "source code" (as represented by KM-data plus Ct-method-bodies) into classfiles.

This layering can be depicted as follows:

<img src="./2%20layer%20codegen.jpg">

From a correctness perspective, this introduced a challenge that we will illustrate in a sequence of modifications to the above diagram.  Note that the unboxed text in the diagram (GraphQL, Km+Ct-bodies, and classfiles) all represent _data structures._  In the following diagram we depict the "surface area" of those data structures:

<center><img src="./2%20layer%20codegen%20with%20surface%20area.jpg"></center>

The surface area is the relative "size" of the universe of data structures one can describe.  An important point here is that the KM library allows you to define a very large universe of objects -- inner classes, top-level functions, `object` declarations, and much more.  The following diagram depicts the proportion of these surface areas produced and consumed by each layer:

<center><img src="2%20layer%20codegen%20with%20surface%20area%20and%20funnels.jpg"></center>

The first layer needs to be _complete_, ie, translate any GraphQL schema into GRTs.  However, in doing so, it uses a small subset of Kotlin.  This means our KmToCt layer in turn only needs to consume a small subset of what's possible to express with the KM library.  The inverse of this subset represents a "red zone" of inputs that the KmToCt layer doesn't generate correct code for, but which could be generated by a bug in the GqlToKm layer:

<center><img src="2 layer codegen with surface area and funnels and red zone.jpg"></center>

This begs the question: How do we protect ourselves from such "red zone" bugs?

Initially we wrote an elaborate checker for the output of GqlToKm to ensure it stays out of the red zone.  While this checker is useful and [still exists](../src/main/kotlin/viaduct/codegen/km/KmGenContextInvariants.kt), it was difficult to convince ourselves that those checks were sufficient, i.e., that they ruled out the entire "red zone."

Thus we decided to add a third layer to our generator:

<img src="3%20layer%20codegen.jpg">

The new layer sits between the GqlToKm layer and the raw KM library.  The API to this new layer is a set of "builders" that build "KmWrapper" objects.  These builders significantly restrict the KM data structures the GqlToKm can generate, helping to keep it out of the red zone.  In addition, this layer encapsulates a lot of KM-specific logic.  For example, in the KM library, when you add a Kotlin property to a class, you have to worry about its backing field, its getter and setter functions, and other such matters.  The wrapper layer encapsulates this logic in an easy-to-reuse manner.

We mentioned earlier that we did _not_ want to insert an API between GqlToKm and the KM library.  The KmWrappers do just that, but it's important to note that we haven't attempted to completely intermediate between GqlToKm and the KM library.  For example, the GqlToKm code directly generates KM objects to express the types it wants in generated code.  Also, there's a tight correlation between our `KmWrapper` class and the `KmClass` in the KM library.  Bottom line: if you're working on the GqlToKm code, it's important that you have a deep understanding of the KM library.

One more point: we have chosen to keep _all_ aspects of GraphQL to Kotlin logic in the GqlToKm.  For example, the KmWrapper classes could have taken GraphQL types as arguments rather than the KM library's `KmType`.  However, that would have moved some of the GraphQL-to-Kotlin logic into KmWrapper, which we didn't want to do.  So, for example, if in a future version of the Viaduct API, we want to change the way we map GraphQL types to Kotlin types, we can write a variant GqlToKm layer that makes that change, which would share the same KmWrapper layer with the "legacy" GqlToKm.


# Source Files

The GqlToKm layer is in [`:tenant:codegen`](../../../tenant/codegen).  The KmWrapper and KmToCt layers live in Treehouse at [`:shared:codegen`](..).  The GqlToKm layer is kept separately from the other layers because, theoretically, we want to support multiple "Tenant APIs", and each of those might generate code slightly differently.  (Inside of Airbnb, we have an older tenant-facing API we still support that indeed has a different GqlToKm layer that uses the `:shared:codegen` library.)


# Testing Overview

As mentioned in the introduction, correctness is critical to the bytecode generator, and yet errors are easily made in its code.  To help ensure correctness, we've implemented a three-part testing strategy: unit, structural, and behavioral testing.  We'll talk about each of these below.

## Unit testing

Unit tests test the individual functions that make up the code generator.  They are found in the usual places.


## Test and Central Schemas

In addition to unit testing, we do extensive integration testing on bytecode generation using two different schemas: the *test schema* and the *central schema.*  Integration tests for the test schema are kept in a subfolder called [`testschema`](https://git.musta.ch/airbnb/treehouse/tree/master/projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/testschema) and for the central schema in [`centralschema`](https://git.musta.ch/airbnb/treehouse/tree/master/projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema).

The test schema is a relatively small schema found in [this file](https://git.musta.ch/airbnb/treehouse/blob/master/projects/viaduct/build-src/src/test/resources/graphql/schema.graphqls) which is intended to cover all cases the bytecode generator is expected to handle.  Whenever a bug is found in the bytecode generator, it's important to put a reproducing test into the test schema to mitigate regressions.

The central schema is the actual Viaduct central schema checked into Treehouse by tenants.  The schema is very large, and running our integration tests over it can take over ten minutes - not something we want on the "inner edit-compile-test" loop of developers.  Thus, in Bazel, central schema tests are marked as "manual" and "no_ide" - meaning they aren't run by `...` targets, or in the IDE, or in CI.  Instead, as mentioned earlier, we have a "prepush" script in `yak`, which developers are expected to run before pushing bytecodegen changes to github.

The central schema tests are _highly_ redundant.  For example, we run the same battery of tests against every `enum` GRT in the central schema, of which there are a few hundred.  It's highly unlikely that the 101-th `enum` in the schema represents a test case any different from the 100 prior `enum`s already tested.  You might call this "needle in the hay stack" testing.  While the 101-th `enum` is not likely to expose a bug, it just might.  Tenants do crazy things - which makes them awesome testcase generators.  We don't yet know how to separate the needle/crazy-tenant-thing from the hay-stack/super-redundant-testing, so we test the central schema exhaustively, using our tenants as test-case generators.  (Again, when a crazy tenant thing triggers a bug in our code, we turn that "crazy" thing into a regression case in the test schema.)

(TODO: we talked about making the central schema tests part of the staging deploy pipeline, if that happens mention it here.)


## Legacy vs test Kotlin generation

As discussed below, an important class of testing is testing our direct-to-bytecode output against the output of the Kotlin compiler.  To generate Kotlin-compiled output, we need to generate Kotlin source code that corresponds to the structure (not the behavior) of the bytecode we're generating.  We currently have two ways of doing this.

The first way is to invoke the "legacy" code generator, i.e., the generator that we used before bytecode gen was implemented.  In our Bazel scripts, where you see the word "legacy" in a target name or identifier, that relates to this form of code generation.

We'll be phasing out this legacy Kotlin code generator in favor of what we call the test Kotlin code generator.  This is an easier-to-maintain Kotlin code generator that generates only the structure of our GRTs (e.g., classes, subclass relationship, property and function signatures), not working function bodies.  Over time we will be replacing legacy codegen with this test codegen.  Where you see "kotlin_grt" in Bazel files, that's referring to this test codegen.


## InvariantChecker Class

The integration testing described below uses a class called [`InvariantChecker`](../../invariants/src/main/kotlin/viaduct/invariants/InvariantChecker.kt) to accumulate errors encountered during integration testing.  `InvariantChecker` has testing functions similar to the assertion functions found in Junit and Google truth.  However, instead of raising an exception when they fail, the failures are collected into a log of failures that can be inspected after a testing run.

We use this accumulator because our integration tests perform over 100K tests in total.  It's not scalable to make each of those an individual Junit test.  Also, for this kind of testing we want "continue on failure" semantics (ie, an entire test run should not terminate on the first error).  The `InvariantChecker` class is meant to support use-cases like this.


## Structural testing

*Structural integration tests* ensure that the "structure" of our byte code is correct, e.g., that classes have the right methods with the right names, parameters and return types.

One form of integration testing we do is ["invariant checking"](../src/main/kotlin/viaduct/codegen/km/KmClassFilesBuilder.kt) (not to be confused with `InvariantChecker`, which is the class used to accumulate errors found during the process of "invariant checker").

Invariant checking (along with assertions sprinkled throughout our code base) is how we help ensure that we stay out of the "red zone" mentioned above.  We run invariant checking on both the test and central schemas.

Another important mechanism we use for structural testing is classfile "diff" testing.  In these diff tests, we compare the structural aspects of the classfiles generated by the direct-to-bytecode generated classfiles against those of the "legacy" compiled-from-Kotlin classfiles.  We only run diff testing on the central schema, mainly due to complications in getting the legacy code-generator to run (there's a lot of complicated scripting in place for the central schema that we can't trivially reuse for the test schema).  Thus, *it's important to run the pre-push tests to get the benefits of diff testing for your changes.*

### Details on diff testing

When it comes to the nitty-gritty details of JVM metadata and Kotlin `@Metadata`, diff testing has proven to be extremely valuable and we recommend keeping it in place indefinitely.  This does come at a cost: it means we need to maintain the Kotlin-based codegen in place indefinitely, and we need to update that codegen when we want to change the code we generate for GRTs.  However, as discussed above, given how easy it is to make small mistakes with the direct-to-codegen libraries we're using, and given the cost of such mistakes, we believe this on-going maintenance cost is worthwhile.

The implementation of the classfile diff algorithm is found in the [`com.airbnb.viaduct.codegen.utils.ctdiff`](../src/main/kotlin/viaduct/codegen/km/ctdiff) package (in the Treehouse `tools/viaduct/oss` directory).  The algorithm uses Java reflection to compare the pure JVM-related structure of classfiles, with Javassist for annotations (because Java reflection hides "runtime invisible" annotation), and it uses `kotlinx-metadata` to compare the `@Metadata` structures.

Classfile diff testing requires that we run the legacy code generator.  As a result, we put the Bazel scripts for this testing into its own [source directory](https://git.musta.ch/airbnb/treehouse/tree/master/projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema).


## Behavioral testing

*Behavioral integration tests* ensure that our generated code _behaves_ as expected, e.g., that a getter returns the correct value for a property.  We run these tests on both the test and central schemas.

We have two approaches to behavioral tests, what we call _reflection-driven_ and _Kotlin-driven_ behavioral tests.  Reflection-driven behavioral tests uses Java reflection (e.g., `java.reflect.Method.invoke`) to drive the behavior of generated GRTs.  Kotlin-driven behavioral tests use generated Kotlin code to drive the behavior of generated GRTs.

Because of the size of the central schema, scaling the Kotlin-driven tests have been challenging.  To achieve acceptable compile times, we've had to [parallelize their compilation](https://git.musta.ch/airbnb/treehouse/blob/master/projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema/kotlin_sharded_testing.bzl).  Also, for types with large numbers of fields (e.g., `ExploreRequest`) we've had to exclude them because the size of the generated tester-methods is too large.

So we've restricted tests done through Kotlin code to tests that exercise Kotlin-specific behavior, such as default values for function parameters, and invocations of `suspend` functions.  Testing of non-Kotlin-specific behaviors (e.g., invoking getters and setters, or equality functions) is done through the reflection-driven tests.

To test the behavior of generated functions, we need to construct test values to call those functions against.  The code for doing this is in [CreateValue.kt](https://git.musta.ch/airbnb/treehouse/blob/master/projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/CreateValue.kt).  This turns out to be a bit tricky mostly because of cycles in the schema (we don't want the value-creation algorithm to get caught in a loop attempting to create an infinitely-recursing value).


# Inspecting Generated Outputs
There are times you may need to examine generated outputs for testing and debugging purposes. This section describes how.

## Bazel Outputs
When you run a bazel command, you can use the flags `--remote_download_toplevel` and `--remote_download_outputs=all` to
download the outputs of just the top-level target or also of all intermediate actions that ran. These outputs will then appear in
`treehouse/dist/bin/<path-to-target>`, which is symlinked to the actual directory that the outputs are downloaded to.

For example, after running the following bazel command:
```
bazel build //projects/viaduct/modules/data:generated_schema_objects --config=engflow --remote_download_outputs=all
```
The outputs will appear in `dist/bin/projects/viaduct/modules/data`. The outputs you're likely
most interested in are the jar files. Here are some helpful commands:
* `jar -tf`: Lists the contents of a jar file
* `jar -xf`: Extracts the contents of a jar file into the current directory

You can see all available targets using `bazel query`, e.g. `bazel query "//projects/viaduct:all"` will print all targets under `//projects/viaduct`.

## Useful Targets
Here are some Bazel targets that you might find useful.

### Test source targets
These targets are used for our central schema tests, so they don't go through the exact same Bazel code path as when we
build the Viaduct service. However, they should still produce the same classfiles, and their outputs are more convenient
to inspect.

All central schema GRTs, in `central_schema_bytecode_for_exercisers_generated_schema_objects.jar`:
```
//projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema:central_schema_bytecode_for_exercisers
```

All GRTs for a specific schema module, `central_schema_bytecode_for_diff_<module name>_generated_schema_objects.jar`,
where `<module name>` is `data`, `presentation`, etc:
```
//projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema:central_schema_bytecode_for_diff_<module name>`
```

All GRTs for a scoped block schema, in `<block name>_scoped_schema_bytecode_generated_schema_objects.jar`, where `<block name>`
is `listingblock`, `userblock`, etc:
```
//projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema:<block name>_scoped_schema_bytecode
```

Generated Kotlin source exercisers for testing, in 50 separate source jars e.g. `central_schema_kotlin_driven_exercisers_src_0_generated.srcjar`:
```
//projects/viaduct/build-src/src/test/kotlin/com/airbnb/viaduct/server/generators/types/bytecode/centralschema:central_schema_kotlin_exercisers_test
```

### Viaduct build targets

These targets are used for building the Viaduct service.

All GRTs for a specific module (`remote_download_outputs=all` is necessary). Direct-to-bytecode GRTs will be in one or more
jars depending on the number of workers used for that module, e.g. `viaduct_generated_schema_objects_jar_0_generated_schema_objects.jar`:
```
//projects/viaduct/modules/data:generated_schema_objects
```

The deployable jar for the Viaduct service. A good source of truth for what's actually being shipped:
```
//projects/viaduct/services/viaduct:viaduct_bin_deploy.jar
```

