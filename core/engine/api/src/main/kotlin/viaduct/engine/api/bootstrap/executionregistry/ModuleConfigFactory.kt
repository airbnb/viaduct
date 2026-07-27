package viaduct.engine.api.bootstrap.executionregistry

/**
 * Produces a generated [ModuleConfigSource] for a module whose configuration is synthesized at
 * runtime rather than read from a packaged resource.
 *
 * This is the extension point that replaces the old built-in `TenantModuleBootstrapper` pattern:
 * built-ins (e.g. the `Query.node`/`Query.nodes` resolvers and namespace-type synthetic fields)
 * are expressed as factories that generate an in-memory [ModuleConfigSource], so they flow through
 * the same file-based bootstrap path as resource-backed tenant modules.
 *
 * All inputs needed to generate the config — including the full schema — are provided to the
 * implementation as constructor inputs. An implementation returns exactly one
 * [ModuleConfigSource], or `null` if the built-in is irrelevant for the current schema.
 *
 * Resource-loaded tenant module configs do not implement this interface; they are loaded directly.
 * This abstraction is only for generated module config, especially built-ins.
 */
fun interface ModuleConfigFactory {
    /**
     * @return the generated [ModuleConfigSource] for this module, or `null` if the module is not
     *   relevant for the current schema.
     */
    fun moduleConfigSource(): ModuleConfigSource?
}
