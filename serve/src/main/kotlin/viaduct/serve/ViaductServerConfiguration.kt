package viaduct.serve

/**
 * Marks a class as a Viaduct serve server provider.
 *
 * The annotated class must implement [ViaductProvider] and have
 * a no-argument constructor. The serve server will:
 * 1. Scan the classpath for classes with this annotation
 * 2. Instantiate the annotated class
 * 3. Call [ViaductProvider.getViaduct] to obtain the Viaduct instance
 *
 * Example:
 * ```kotlin
 * @ViaductServerConfiguration
 * class MyViaductProvider : ViaductProvider {
 *     override fun getViaduct(): Viaduct {
 *         val context = ApplicationContext.run()
 *         return context.getBean(Viaduct::class.java)
 *     }
 * }
 * ```
 *
 * @see ViaductProvider
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ViaductServerConfiguration
