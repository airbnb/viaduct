---
date: 2026-03-13
categories:
  - Releases
description: Release notes for Viaduct 0.24.0.
---

# Release 0.24.0

See [GitHub Release](https://github.com/airbnb/viaduct/releases/tag/v0.24.0).

<!-- more -->

## Breaking Changes

- improve support for concurrent property tests ([e3700045](https://github.com/airbnb/viaduct/commit/e3700045)) by @jbellenger
- rename config keys ([f5ff1c81](https://github.com/airbnb/viaduct/commit/f5ff1c81)) by @jbellenger
- scoped QueryPlan caching ([deb511b4](https://github.com/airbnb/viaduct/commit/deb511b4)) by @jbellenger
- unified Arb value model ([1c28ad5e](https://github.com/airbnb/viaduct/commit/1c28ad5e)) by @jbellenger
- rename `executeSelectionSet` to `resolveSelectionSet` ([44ded17a](https://github.com/airbnb/viaduct/commit/44ded17a)) by @skevy
- move FragmentLoader interface to common/viaduct and remove dead code ([63b3b55b](https://github.com/airbnb/viaduct/commit/63b3b55b)) by @skevy

## Features

- Improved mechanism for release managers to validate release branches ([#301](https://github.com/airbnb/viaduct/pull/301)) by @rstata
- Improved scripting for demoapp publishing ([#300](https://github.com/airbnb/viaduct/pull/300)) by @rstata
- pass result path of the selection as part of the instrumentFetchSelection. The result path will point to the hierarchy path of the fetched selection. ([10c40f36](https://github.com/airbnb/viaduct/commit/10c40f36)) by @vickeyyeh
- Adds connection resolver support to `FeatureTestBuilder`, enabling feature tests to register and test Relay-style connection resolvers. ([b8989a98](https://github.com/airbnb/viaduct/commit/b8989a98)) by @kristileka
- Add includeFieldArg to SchemaFilter for granular field-arg filtering ([83302d60](https://github.com/airbnb/viaduct/commit/83302d60)) by @dtimilsina
- Adds `ConnectionBuilder` pagination examples to the StarWars demo app. ([e9d27f35](https://github.com/airbnb/viaduct/commit/e9d27f35)) by @kristileka
- set InternalApi opt-in to ERROR ([7f2f2263](https://github.com/airbnb/viaduct/commit/7f2f2263)) by @nmarsollier
- ([e3ea7d40](https://github.com/airbnb/viaduct/commit/e3ea7d40)) by @junjinp
- Enable API Stability Tooling for service/wiring ([6c38d147](https://github.com/airbnb/viaduct/commit/6c38d147)) by @nmarsollier
- Introduces StandardResolutionValue, the inverse of ParentManagedValue. ([43db0aeb](https://github.com/airbnb/viaduct/commit/43db0aeb)) by @njlynch
- add API annotation conflict detection rules ([74d31f44](https://github.com/airbnb/viaduct/commit/74d31f44)) by @gokhan-ozgozen
- Extract shared test infrastructure from FeatureAppTestBase (Kotlin) and JavaFeatureAppTestBase (Java) into a common AbstractFeatureAppTestBase in service/wiring/src/testFixtures/. The two test base classes were ~70% identical, duplicating logic for ViaductBuilder wiring, query execution, and test lifecycle.  Now both extend the shared abstract base, eliminating ~140 lines of duplicated code. ([58cd3982](https://github.com/airbnb/viaduct/commit/58cd3982)) by @catacraciun
- add JMH benchmarks for end-to-end codegen pipeline and fix scalar type handling ([10e45ba8](https://github.com/airbnb/viaduct/commit/10e45ba8)) by @geovannefduarte
- Adds field resolvers and feature app test for connections ([863a5cdd](https://github.com/airbnb/viaduct/commit/863a5cdd)) by @kristileka
- Adds connection and argument support and kotlingen testing for them ([78152dd8](https://github.com/airbnb/viaduct/commit/78152dd8)) by @kristileka
- Add JavaRequiredSelectionSetFactory to parse @Resolver annotation properties (objectValueFragment, queryValueFragment, @Variable) into RequiredSelectionSets for the Java API. ([e2f5952e](https://github.com/airbnb/viaduct/commit/e2f5952e)) by @catacraciun
- Created new feature app tests framework for Java, started with Enums example. ([14bfaebc](https://github.com/airbnb/viaduct/commit/14bfaebc)) by @catacraciun
- Adds codegen support for @edge directive . ([ea887359](https://github.com/airbnb/viaduct/commit/ea887359)) by @kristileka
- add ConnectionFieldExecutionContextImpl for connection field resolvers ([1ae2944d](https://github.com/airbnb/viaduct/commit/1ae2944d)) by @kristileka
- instrument SyncEngineObjectDataFactory fetch selection. ([c00bf6ac](https://github.com/airbnb/viaduct/commit/c00bf6ac)) by @vickeyyeh
- add completeSelectionSet API for completing already-resolved fields ([632b746f](https://github.com/airbnb/viaduct/commit/632b746f)) by @skevy
- Add a feature flag (ENABLE_SYNC_VALUE_COMPUTATION) to control sync value computation in Viaduct's ResolverDataFetcher. ([db4b0338](https://github.com/airbnb/viaduct/commit/db4b0338)) by @vickeyyeh
- Add Java bootstrapper with automatic resolver discovery for the x-javaapi-runtime module. ([ab8d9530](https://github.com/airbnb/viaduct/commit/ab8d9530)) by @catacraciun
- validate resolver completeness in FeatureAppTestBase ([be2019f4](https://github.com/airbnb/viaduct/commit/be2019f4)) by @fireboy1919
- Modify visibility on interfaces used in ResolverTestBase ([cf283ee6](https://github.com/airbnb/viaduct/commit/cf283ee6)) by @nmarsollier
- Adds Connections tenant api functionalltiy ([b8037539](https://github.com/airbnb/viaduct/commit/b8037539)) by @kristileka
- integrate DefaultSchemaValidator into Gradle plugin ([1325b7ef](https://github.com/airbnb/viaduct/commit/1325b7ef)) by @geovannefduarte
- `@TestingApi` annotation is renamed as `@VisibleForTest`. ([553ea25e](https://github.com/airbnb/viaduct/commit/553ea25e)) by @gokhan-ozgozen
- disallow PageInfo customizations for OSS users ([4576bd4f](https://github.com/airbnb/viaduct/commit/4576bd4f)) by @geovannefduarte
- add scaffold task to bootstrap new Viaduct projects ([478884c9](https://github.com/airbnb/viaduct/commit/478884c9)) by @fireboy1919
- add schema validation framework core ([8e0a4477](https://github.com/airbnb/viaduct/commit/8e0a4477)) by @geovannefduarte
- add getFullyQualifiedErrorClass() to ViaductDelegationException ([693d95d1](https://github.com/airbnb/viaduct/commit/693d95d1)) by @viaduct-maintainers
- Custom Detekt Rules are moved to separate gradle module. ([243487c2](https://github.com/airbnb/viaduct/commit/243487c2)) by @gokhan-ozgozen
- extended `QueryPlanExecutionCondition` interface to support environment-based conditions via `shouldExecute(env: DataFetchingEnvironment?)` ([75eed602](https://github.com/airbnb/viaduct/commit/75eed602)) by @amity177
- add adaptive favicon for dark mode visibility ([3f1bcbb7](https://github.com/airbnb/viaduct/commit/3f1bcbb7)) by @geovannefduarte
- Separated GRTs codegen from Resolvers codegen. ([0fe5e7ef](https://github.com/airbnb/viaduct/commit/0fe5e7ef)) by @catacraciun
- add schema2csv CLI tool for schema analysis ([20dde34a](https://github.com/airbnb/viaduct/commit/20dde34a)) by @rstata
- add cachingExceptionsEnabled option to control per-key exception caching ([e9721fe9](https://github.com/airbnb/viaduct/commit/e9721fe9)) by @skevy

## Bug Fixes

- Revert RequirestOptIn to be WARNING to avoid IntelliJ issues. ([011771d1](https://github.com/airbnb/viaduct/commit/011771d1)) by @nmarsollier
- fix type condition tracking for conditionless inline fragments ([dc859322](https://github.com/airbnb/viaduct/commit/dc859322)) by @jbellenger
- Add build and .gradle to .gitignore ([22678c20](https://github.com/airbnb/viaduct/commit/22678c20)) by @rstata
- Move gradle/gradlew.stock to gradlew to unconfuse copybara. ([#297](https://github.com/airbnb/viaduct/pull/297)) by @rstata
- correct Worker API isolation, path sensitivity, lazy wiring, and task property types in build-test-plugins ([bcfdf106](https://github.com/airbnb/viaduct/commit/bcfdf106)) by @rstata
- fix standalone build failures and align dependency versions ([6b18c5dc](https://github.com/airbnb/viaduct/commit/6b18c5dc)) by @geovannefduarte
- fix standalone build failures and align dependency versions ([66994e00](https://github.com/airbnb/viaduct/commit/66994e00)) by @geovannefduarte
- generate NodeResolvers into resolverbases subpackage ([a2348516](https://github.com/airbnb/viaduct/commit/a2348516)) by @fireboy1919
- add Caffeine system scheduler to enable cache expiration ([aeabf8a8](https://github.com/airbnb/viaduct/commit/aeabf8a8)) by @dtimilsina
- remove unused `resolverId` parameter from subquery execution path ([c53740b9](https://github.com/airbnb/viaduct/commit/c53740b9)) by @skevy
- extract original exception class from GraphQL error extensions for accurate exceptionClasses in full_execution metrics ([d48869bf](https://github.com/airbnb/viaduct/commit/d48869bf)) by @cetinsahin
- rename VariableProviderContextImpl to VariablesProviderContextImpl in comment ([6abc2bda](https://github.com/airbnb/viaduct/commit/6abc2bda)) by @fireboy1919
- fix contextQueryValues passed as requestContext in createNodeExecutionContext. ([b1861b42](https://github.com/airbnb/viaduct/commit/b1861b42)) by @viaduct-maintainers
- refactor ExecutionParameters child plan builders and optimize QueryPlan construction ([13884e21](https://github.com/airbnb/viaduct/commit/13884e21)) by @skevy
- fix IR value generation for cyclic input objects ([48ad583c](https://github.com/airbnb/viaduct/commit/48ad583c)) by @jbellenger
- disable arb-arb-arb test ([fa17b63a](https://github.com/airbnb/viaduct/commit/fa17b63a)) by @jbellenger
- add missing newlines before bullet lists in observability docs ([700e83b8](https://github.com/airbnb/viaduct/commit/700e83b8)) by @geovannefduarte
- parent completableDeferred from request scope context ([33121fc2](https://github.com/airbnb/viaduct/commit/33121fc2)) by @gummybug
- add missing Viaduct dependencies to micronaut-starter and include in CI ([61d33616](https://github.com/airbnb/viaduct/commit/61d33616)) by @geovannefduarte
- resolve ktor-starter compile error and add it to CI ([6705b86f](https://github.com/airbnb/viaduct/commit/6705b86f)) by @geovannefduarte
- switch BCV to inclusion model to prevent API leakage ([d010c54f](https://github.com/airbnb/viaduct/commit/d010c54f)) by @gokhan-ozgozen
- QueryPlan handling of variable plans ([09b6257f](https://github.com/airbnb/viaduct/commit/09b6257f)) by @jbellenger
- allow ProxyEngineObjectData to fetch introspection fields ([6ba6ae94](https://github.com/airbnb/viaduct/commit/6ba6ae94)) by @jbellenger
- improve QueryPlan handling of fields that are missing a source location ([3f2ab378](https://github.com/airbnb/viaduct/commit/3f2ab378)) by @jbellenger

## Performance Improvements

- add ProjectedEngineSelectionSet for O(1) field lookups on concrete types ([b7f82b8b](https://github.com/airbnb/viaduct/commit/b7f82b8b)) by @geovannefduarte
- Adds a global cache for RSS child plans in QueryPlanFactory.Cached, shared across all operation-level plan build passes, in order to reduce old-gen heap usage. Previously, every top-level plan build would redundantly rebuild the same RSS child plans from scratch. ([c4f51644](https://github.com/airbnb/viaduct/commit/c4f51644)) by @gummybug
- add caching Conv factories ([9d8b69ef](https://github.com/airbnb/viaduct/commit/9d8b69ef)) by @jbellenger
- GRTConv performance improvements ([0d7fd74e](https://github.com/airbnb/viaduct/commit/0d7fd74e)) by @jbellenger

## Documentation

- update code of conduct link ([#294](https://github.com/airbnb/viaduct/pull/294)) by @ryantanner
- remove broken Java API docs link from index ([dba5bf6e](https://github.com/airbnb/viaduct/commit/dba5bf6e)) by @geovannefduarte
- add subqueries documentation to navigation ([92471cf0](https://github.com/airbnb/viaduct/commit/92471cf0)) by @skevy
- Document the guidelines and contributors guide to use api stability annotations in kdoc. ([01e973b6](https://github.com/airbnb/viaduct/commit/01e973b6)) by @nmarsollier
- Add documentation to kdos related to Viaduct interface api, builder and result interfaces. ([9ee41b56](https://github.com/airbnb/viaduct/commit/9ee41b56)) by @nmarsollier
- disable djlint for insecure localhost links in docs ([#291](https://github.com/airbnb/viaduct/pull/291)) by @ryantanner
- expand subqueries documentation to cover full API surface ([be09dc82](https://github.com/airbnb/viaduct/commit/be09dc82)) by @skevy
- add KDoc documentation to ErrorBuilder.kt ([34a8b66b](https://github.com/airbnb/viaduct/commit/34a8b66b)) by @fireboy1919
- add KDoc documentation to FlagManager.kt ([8eb66dc6](https://github.com/airbnb/viaduct/commit/8eb66dc6)) by @fireboy1919
- fix inaccurate KDocs and improve class documentation in Viaduct.kt ([fd548293](https://github.com/airbnb/viaduct/commit/fd548293)) by @fireboy1919
- add KDoc documentation to ViaductBuilder.kt ([d09a8215](https://github.com/airbnb/viaduct/commit/d09a8215)) by @fireboy1919
- add KDoc documentation to BasicViaductFactory.kt ([cd59cf0c](https://github.com/airbnb/viaduct/commit/cd59cf0c)) by @fireboy1919
- improve KDoc documentation for SchemaId.kt ([217cdfa2](https://github.com/airbnb/viaduct/commit/217cdfa2)) by @fireboy1919
- add KDoc documentation to TenantCodeInjector.kt ([0628961f](https://github.com/airbnb/viaduct/commit/0628961f)) by @fireboy1919
- add KDoc documentation to TenantAPIBootstrapperBuilder.kt ([37ec6dbe](https://github.com/airbnb/viaduct/commit/37ec6dbe)) by @fireboy1919
- Add missing images to architecture page ([#279](https://github.com/airbnb/viaduct/pull/279)) by @ryantanner
- Updating outdated documentation and fixing formatting ([9181e6c4](https://github.com/airbnb/viaduct/commit/9181e6c4)) by @gummybug
- add PageInfo documentation for Relay Connection spec compliance ([5b179a79](https://github.com/airbnb/viaduct/commit/5b179a79)) by @geovannefduarte

## Testing

- enable concurrent property test execution ([6f08729d](https://github.com/airbnb/viaduct/commit/6f08729d)) by @jbellenger
- mv KotestPropertyBase to testFixtures ([5cf9cba9](https://github.com/airbnb/viaduct/commit/5cf9cba9)) by @jbellenger
- expand schema validation coverage for walkSchema and source location ([daf36c3d](https://github.com/airbnb/viaduct/commit/daf36c3d)) by @geovannefduarte

## Refactoring

- move TemporaryBypassAccessCheck to engine.api.spi package ([6fb822a1](https://github.com/airbnb/viaduct/commit/6fb822a1)) by @geovannefduarte
- Removing unused code path that executes access checks in `NodeEngineObjectDataImpl`. These are actually executed inside the execution strategy itself. ([193b2067](https://github.com/airbnb/viaduct/commit/193b2067)) by @gummybug
- Remove default arguments in codegen stack. ([b6dede82](https://github.com/airbnb/viaduct/commit/b6dede82)) by @nmarsollier
- merge build-test-plugins into build-logic/test-support subproject ([4cc28aba](https://github.com/airbnb/viaduct/commit/4cc28aba)) by @rstata
- extract RSS graph logic ([75f3aa25](https://github.com/airbnb/viaduct/commit/75f3aa25)) by @jbellenger
- consolidate integration tests into core modules using Worker API isolation ([7ca31c84](https://github.com/airbnb/viaduct/commit/7ca31c84)) by @skevy
- revert removing EngineObjectDataFetchException ([b64614b8](https://github.com/airbnb/viaduct/commit/b64614b8)) by @ryantanner
- remove EngineObjectDataFetchException ([a3b0ec2f](https://github.com/airbnb/viaduct/commit/a3b0ec2f)) by @fireboy1919
- This PR is a pure refactor that moves `QueryPlanFactory` (and `QueryPlanBuilder`) out of `QueryPlan.kt` into its own file. Moves the build functions in `QueryPlan.Companion` into `QueryPlanFactory.Default`, since they're only used there. ([34b1d4d4](https://github.com/airbnb/viaduct/commit/34b1d4d4)) by @gummybug
- rename handleTenantAPIErrors to wrapFrameworkErrors ([e3a6f244](https://github.com/airbnb/viaduct/commit/e3a6f244)) by @fireboy1919
- extract engineExecutionContext from VariablesResolver.ResolveCtx per RFC-262 ([516cc5c6](https://github.com/airbnb/viaduct/commit/516cc5c6)) by @fireboy1919
- address post-merge review feedback on ViaductSchemaValidator ([345f2bef](https://github.com/airbnb/viaduct/commit/345f2bef)) by @geovannefduarte
- introduce Conv factory interfaces ([1b6fe465](https://github.com/airbnb/viaduct/commit/1b6fe465)) by @jbellenger
- rename non-conforming *Provider classes to *Factory ([8024c63a](https://github.com/airbnb/viaduct/commit/8024c63a)) by @fireboy1919
- update OSS BUILD visibility grants for classic tenant runtime relocation ([82f15054](https://github.com/airbnb/viaduct/commit/82f15054)) by @skevy
- undo RAW_VALUE_SLOT to ENGINE_VALUE_SLOT rename ([6bf4071a](https://github.com/airbnb/viaduct/commit/6bf4071a)) by @fireboy1919
- rename InvariantChecker to FailureCollector ([daa8bffe](https://github.com/airbnb/viaduct/commit/daa8bffe)) by @geovannefduarte
- remove RawSelectionSet typealiases and update all references ([6565fe6b](https://github.com/airbnb/viaduct/commit/6565fe6b)) by @fireboy1919
- rename UnsetSelectionException to UnsetFieldException ([9d38ca30](https://github.com/airbnb/viaduct/commit/9d38ca30)) by @geovannefduarte
- rename RAW_VALUE_SLOT to ENGINE_VALUE_SLOT and setRawValue to setEngineValue ([882a5075](https://github.com/airbnb/viaduct/commit/882a5075)) by @fireboy1919
- consistent type parameter naming across ExecutionContext interfaces ([34a2ef87](https://github.com/airbnb/viaduct/commit/34a2ef87)) by @fireboy1919
- remove RequiredSelectionSets data class in favor of Pair ([562cafbd](https://github.com/airbnb/viaduct/commit/562cafbd)) by @fireboy1919
- Rename batchResolve to resolve in NodeResolverExecutor ([38fadffb](https://github.com/airbnb/viaduct/commit/38fadffb)) by @fireboy1919
- move ifDebug from shared/logging to shared/utils/slf4j ([ce8fde58](https://github.com/airbnb/viaduct/commit/ce8fde58)) by @fireboy1919
- rename EOD.graphQLObjectType to EOD.type ([fa72a821](https://github.com/airbnb/viaduct/commit/fa72a821)) by @fireboy1919
- remove legacy subquery execution feature flag ([065f8e79](https://github.com/airbnb/viaduct/commit/065f8e79)) by @skevy

## Chores

- remove SNAPSHOT from version (now 0.24.0) by @rstata
- consolidate integrationTest sources into ... ([a51f7d8d](https://github.com/airbnb/viaduct/commit/a51f7d8d)) by @rstata
- remove unused tools project ([d04c0abe](https://github.com/airbnb/viaduct/commit/d04c0abe)) by @rstata
- fix underscore-containing type names breaking Arg... ([c3e8e35e](https://github.com/airbnb/viaduct/commit/c3e8e35e)) by @skevy
- update kotest packages to latest versions ([e3582911](https://github.com/airbnb/viaduct/commit/e3582911)) by @jbellenger
- increment snapshot version ([#290](https://github.com/airbnb/viaduct/pull/290)) by @ryantanner
- consolidate duplicate child plan launching logic from `AccessCheckRunner.typeCheck()` and `FieldResolver.launchQueryPlan()` into a shared `FieldResolver.launchChildPlan()` method. ([c74f74a8](https://github.com/airbnb/viaduct/commit/c74f74a8)) by @amity177
- arb habitation fixes ([64fc6f19](https://github.com/airbnb/viaduct/commit/64fc6f19)) by @jbellenger
- BCV check tasks are being run with gradle check. ([fb6be4b9](https://github.com/airbnb/viaduct/commit/fb6be4b9)) by @gokhan-ozgozen
- unify naming of factory methods ([594379ee](https://github.com/airbnb/viaduct/commit/594379ee)) by @ryantanner
- revert bump version to v0.24.0 ([a5fd6221](https://github.com/airbnb/viaduct/commit/a5fd6221)) by @junjinp
- Add DefaultSchemaValidator with all standar... ([65a4a7b6](https://github.com/airbnb/viaduct/commit/65a4a7b6)) by @geovannefduarte
- Add ApplicationOnlyDefinitionsRule for sche... ([d92c4e37](https://github.com/airbnb/viaduct/commit/d92c4e37)) by @geovannefduarte
- Bump version to v0.24.0 ([#281](https://github.com/airbnb/viaduct/pull/281)) by @junjinp
- Upgrade GitHub Actions for Node 24 compatibility ([#272](https://github.com/airbnb/viaduct/pull/272)) by @13schishti
- Upgrade GitHub Actions to latest versions ([#273](https://github.com/airbnb/viaduct/pull/273)) by @13schishti
- Add NoCustomScalarsRule for schema validation ([327c5dff](https://github.com/airbnb/viaduct/commit/327c5dff)) by @geovannefduarte
- Add NoSubscriptionsRule for schema validation ([f8592e7f](https://github.com/airbnb/viaduct/commit/f8592e7f)) by @geovannefduarte
- Remove unused konsist code ([95683975](https://github.com/airbnb/viaduct/commit/95683975)) by @rstata
- Fix DFP execution supervisor lifecycle to ... ([f7c42081](https://github.com/airbnb/viaduct/commit/f7c42081)) by @skevy
- reclassify malformed GlobalIDs as tenant/client errors, not framework errors ([abf0adeb](https://github.com/airbnb/viaduct/commit/abf0adeb)) by @njlynch
- Fixes bazel build for feature app test ([7face36d](https://github.com/airbnb/viaduct/commit/7face36d)) by @kristileka
- Version bump as a release mgmt step 1. ([#277](https://github.com/airbnb/viaduct/pull/277)) by @gokhan.ozgozen

## Continuous Integration

- add standalone demoapp tests workflow ([#296](https://github.com/airbnb/viaduct/pull/296)) by @rstata

## Build System

- Isolated demoapp testing using mavenLocal to increase stability. ([efcfa935](https://github.com/airbnb/viaduct/commit/efcfa935)) by @gokhan-ozgozen
- redirect gradle output directories to a central dist/ location ([3b35bc27](https://github.com/airbnb/viaduct/commit/3b35bc27)) by @skevy
- trigger build on outbound commits and fix slack alerts ([#293](https://github.com/airbnb/viaduct/pull/293)) by @ryantanner
- fix handling of kotlinx dependencies in composite build ([#292](https://github.com/airbnb/viaduct/pull/292)) by @ryantanner
- only post build scans for failed build ([#287](https://github.com/airbnb/viaduct/pull/287)) by @ryantanner
- post build scan links as pull request comments ([#282](https://github.com/airbnb/viaduct/pull/282)) by @ryantanner
- Assorted dokka/djlint improvements ([#280](https://github.com/airbnb/viaduct/pull/280)) by @ryantanner
- Add default OptIn gradle compiler parameters for stability annotations. ([8977e51b](https://github.com/airbnb/viaduct/commit/8977e51b)) by @nmarsollier
- Fix dokka output directory ([480e4196](https://github.com/airbnb/viaduct/commit/480e4196)) by @ryantanner
- Lint website for broken links in CI ([#278](https://github.com/airbnb/viaduct/pull/278)) by @ryantanner
