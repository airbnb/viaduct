/**
 * {@code ExecutionRegistryConfigFile} bootstrap support for Java resolvers.
 *
 * <p>The Java registry annotation processor discovers resolvers at build time and emits an
 * execution-registry resource. {@code ViaductJavaExecutorFactory} consumes that resource at startup
 * and creates resolver executors without classpath scanning.
 */
package viaduct.java.runtime.bootstrap;
