package viaduct.codegen.ct

import javassist.CtClass
import javassist.CtMethod
import javassist.CtNewConstructor
import javassist.CtNewMethod
import javassist.bytecode.AccessFlag
import javassist.bytecode.Bytecode
import javassist.bytecode.Descriptor
import javassist.bytecode.FieldInfo
import javassist.bytecode.MethodInfo
import javassist.bytecode.Opcode

private const val ENUM_VALUE_ACCESS_FLAGS =
    AccessFlag.PUBLIC or AccessFlag.STATIC or AccessFlag.FINAL or AccessFlag.ENUM

private const val ENUM_SYNTHETIC_VALUES_ACCESS_FLAGS =
    AccessFlag.PRIVATE or AccessFlag.STATIC or AccessFlag.FINAL or AccessFlag.SYNTHETIC

private const val ENUM_CONSTRUCTOR_DESCRIPTOR = "(Ljava/lang/String;I)V"

/**
 * For a kotlin enum class:
 *
 * enum class WeatherType {
 *     SUNNY,
 *     CLOUDY
 * }
 *
 * The corresponding Java class looks like:
 *
 * public final class WeatherType extends Enum<WeatherType> {
 *     public static final /* enum */ WeatherType SUNNY;
 *     public static final /* enum */ WeatherType CLOUDY;
 *     private static final /* synthetic */ WeatherType[] $VALUES;
 *
 *     public static WeatherType[] values() {
 *         return (WeatherType[])$VALUES.clone();
 *     }
 *
 *     public static WeatherType valueOf(String value) {
 *         return (WeatherType)Enum.valueOf(WeatherType.class, value);
 *     }
 *
 *     private static final /* synthetic */ WeatherType[] $values() {
 *         return new WeatherType[] { WeatherType.SUNNY, WeatherType.CLOUDY };
 *     }
 *
 *     private WeatherType(String s, int i) {
 *         super(s, i);
 *     }
 *
 *     static {
 *         SUNNY = new WeatherType("SUNNY", 0);
 *         CLOUDY = new WeatherType("CLOUDY", 1);
 *         $VALUES = $values();
 *     }
 * }
 */
internal fun CtGenContext.kmToCtEnum(
    kmClassWrapper: KmClassWrapper,
    outer: KmClassWrapper?
): CtClass {
    if (outer != null) {
        throw IllegalArgumentException("Nested enumerations are not supported ($kmClassWrapper).")
    }

    val kmName = kmClassWrapper.kmClass.kmName
    val jvmName = kmName.asJvmName
    val javaBinaryName = kmName.asJavaBinaryName
    val javaName = kmName.asJavaName.toString()

    val result = getClass(javaBinaryName)
    result.applySupers(this, kmClassWrapper)
    val enumArrayCtClass = getClass(CtName("$javaBinaryName[]")) // Must _follow_ creation of result!
    val entryDescriptor = Descriptor.of(jvmName)
    val valuesArrayDescriptor = Descriptor.of(enumArrayCtClass)
    val valuesDescriptor = "()$valuesArrayDescriptor"
    result.classFile.apply {
        val cp = constPool
        accessFlags = kmClassWrapper.kmClass.jvmAccessFlags or AccessFlag.ENUM

        // Add enum value fields
        kmClassWrapper.kmClass.enumEntries.forEach { valueName ->
            withContext(valueName) {
                addField(
                    FieldInfo(cp, valueName, entryDescriptor).apply {
                        accessFlags = ENUM_VALUE_ACCESS_FLAGS
                    }
                )
            }
        }

        // Add $VALUES
        withContext("\$VALUES") {
            addField(
                FieldInfo(cp, "\$VALUES", valuesArrayDescriptor).apply {
                    accessFlags = ENUM_SYNTHETIC_VALUES_ACCESS_FLAGS
                }
            )
        }
    }
    // Add $values(), as bytecode: entry names are legal JVM field names but may be Java keywords
    // (e.g. `if`) that Javassist's source compiler cannot parse.
    withContext("\$values") {
        val cp = result.classFile.constPool
        val bc = Bytecode(cp)
        bc.addIconst(kmClassWrapper.kmClass.enumEntries.size)
        bc.addAnewarray(jvmName)
        kmClassWrapper.kmClass.enumEntries.forEachIndexed { index, valueName ->
            bc.addOpcode(Opcode.DUP)
            bc.addIconst(index)
            bc.addGetstatic(jvmName, valueName, entryDescriptor)
            bc.addOpcode(Opcode.AASTORE)
        }
        bc.addOpcode(Opcode.ARETURN)
        bc.maxLocals = 0
        val minfo =
            MethodInfo(cp, "\$values", valuesDescriptor).apply {
                accessFlags = ENUM_SYNTHETIC_VALUES_ACCESS_FLAGS
                codeAttribute = bc.toCodeAttribute()
            }
        result.addMethod(CtMethod.make(minfo, result))
    }

    // Add values()
    withContext("values") {
        val valuesMethodBody = "public static $javaName[] values() { return ($javaName[])\$VALUES.clone(); }"
        val valuesMethod =
            handleCompilerError(valuesMethodBody) {
                CtNewMethod.make(it, result)
            }
        result.addMethod(valuesMethod)
    }

    // Add valueOf(String value)
    withContext("valueOf") {
        val valueOfMethodBody =
            """
                public static $javaName valueOf(String value) {
                    return ($javaName)Enum.valueOf($javaName.class, value);
            }
            """.trimIndent()
        val valueOfMethod =
            handleCompilerError(valueOfMethodBody) {
                CtNewMethod.make(it, result)
            }
        result.addMethod(valueOfMethod)
    }

    // Add constructor
    withContext("${result.simpleName}(String,int)") {
        val constructorBody = "private ${result.simpleName}(String s, int i) { super(s, i); }"
        val ctor =
            handleCompilerError(constructorBody) {
                CtNewConstructor.make(it, result)
            }
        result.addConstructor(ctor)
    }

    // Add the static initializer
    withContext("clinit") {
        val cp = result.classFile.constPool
        val bc = Bytecode(cp)
        kmClassWrapper.kmClass.enumEntries.forEachIndexed { index, valueName ->
            bc.addNew(jvmName)
            bc.addOpcode(Opcode.DUP)
            bc.addLdc(valueName)
            bc.addIconst(index)
            bc.addInvokespecial(jvmName, MethodInfo.nameInit, ENUM_CONSTRUCTOR_DESCRIPTOR)
            bc.addPutstatic(jvmName, valueName, entryDescriptor)
        }
        bc.addInvokestatic(jvmName, "\$values", valuesDescriptor)
        bc.addPutstatic(jvmName, "\$VALUES", valuesArrayDescriptor)
        bc.addReturn(CtClass.voidType)
        bc.maxLocals = 0
        result.makeClassInitializer().methodInfo.codeAttribute = bc.toCodeAttribute()
    }

    return result
}
