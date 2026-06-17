/**
 * Bootstrap package for Java resolver discovery and registration.
 *
 * <p>This package provides the interface for discovering Java resolvers at application startup. The
 * implementation classes are in the {@code viaduct.java.runtime.bridge} package (Kotlin).
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@code viaduct.java.api.internal.ResolverClassFinder} - Interface for discovering resolver
 *       classes via classpath scanning (lives in the API layer)
 *   <li>{@code viaduct.java.runtime.bridge.DefaultResolverClassFinder} - Default implementation
 *       using ClassGraphScanner (Kotlin)
 *   <li>{@code viaduct.java.runtime.bridge.ModuleBootstrapper} - Bootstrapper that implements
 *       {@code LegacyTenantModuleBootstrapper} for Java resolvers (Kotlin)
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Create the class finder for your resolver package
 * ResolverClassFinder classFinder = new DefaultResolverClassFinder(
 *     "com.mycompany.resolvers",  // Package containing @Resolver classes
 *     "com.mycompany.grts"        // Package containing generated GRT classes
 * );
 *
 * // Create the bootstrapper
 * LegacyTenantModuleBootstrapper bootstrapper = new ModuleBootstrapper(
 *     classFinder,
 *     CodeInjector.Naive  // Or use your DI framework's injector
 * );
 *
 * // Use the bootstrapper in a feature test
 * JavaFeatureTestHelper.run(schemaSDL, bootstrapper, test -> {
 *     test.runQueryAndAssert("{ greeting }", "{data: {greeting: \"Hello, World!\"}}");
 * });
 * }</pre>
 *
 * <h2>How Discovery Works</h2>
 *
 * <ol>
 *   <li>Codegen generates abstract base classes annotated with {@code @ResolverFor(typeName,
 *       fieldName)}
 *   <li>Developers extend these base classes and annotate with {@code @Resolver}
 *   <li>At bootstrap, the class finder scans for all {@code @ResolverFor} classes
 *   <li>For each base class, it finds subclasses annotated with {@code @Resolver}
 *   <li>The bootstrapper wraps each resolver in a {@code JavaFieldResolverExecutorImpl}
 * </ol>
 */
package viaduct.java.runtime.bootstrap;
