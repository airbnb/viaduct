package viaduct.codegen.ct

import javassist.ClassPool
import javassist.CtBehavior
import javassist.CtClass
import viaduct.codegen.utils.JavaBinaryName

internal class CtGenContext(
    private val pool: ClassPool
) {
    /** Context is a stack of strings giving the location of the compilation process within the
     *  Kotlin metadata tree.  The stack is used to provide more meaningful error messages when Javassist
     *  code generation fails.
     */
    private val context = mutableListOf<String>()

    fun pushContext(label: String) = context.add(label)

    fun popContext() = context.removeLast()

    fun <T> withContext(
        label: String,
        block: () -> T
    ): T {
        pushContext(label)
        val result = block()
        popContext()
        return result
    }

    val fullContext: String get() = context.joinToString(".")

    private var compilables: List<Pair<String, CtBehavior>> = mutableListOf()

    /** Add a compilable function body to be compiled after all classes and their
     *  members have been declared.
     *  This ensures that method bodies are able to reference classes and
     *  their members irrespective of the order that the classes are generated in.
     */
    fun addCompilable(
        body: String,
        behavior: CtBehavior
    ): CtGenContext {
        compilables += body to behavior
        return this
    }

    fun compileCompilables() {
        compilables.forEach { (body, behavior) ->
            handleCompilerError(body) { compiled ->
                behavior.setBody(compiled)
            }
        }
    }

    /**
     * Wrapper for blocks that call the Javassist compiler to allow us to see the code
     * that caused the compiler error.
     */
    fun <T> handleCompilerError(
        codeToBeCompiled: String,
        blockCallingCompiler: (String) -> T
    ): T {
        try {
            return blockCallingCompiler(codeToBeCompiled)
        } catch (e: Exception) {
            throw IllegalArgumentException("Compiler error at $fullContext\n   on: $codeToBeCompiled", e)
        }
    }

    private fun getClass(name: String): CtClass =
        try {
            pool.get(name)
        } catch (e: Exception) {
            throw RuntimeException("$fullContext\n${e.message}", e)
        }

    fun getClass(ctName: CtName) = getClass(ctName.toString())

    fun getClass(jbName: JavaBinaryName) = getClass(jbName.toString())

    fun getClassOrNull(jbName: JavaBinaryName): CtClass? = pool.getOrNull(jbName.toString())
}
