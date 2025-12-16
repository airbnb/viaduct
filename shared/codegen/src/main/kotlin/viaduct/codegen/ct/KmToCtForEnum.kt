package viaduct.codegen.ct

import javassist.CtClass
import javassist.CtNewConstructor
import javassist.CtNewMethod
import javassist.bytecode.AccessFlag
import javassist.bytecode.Descriptor
import javassist.bytecode.FieldInfo

private const val ENUM_VALUE_ACCESS_FLAGS =
    AccessFlag.PUBLIC or AccessFlag.STATIC or AccessFlag.FINAL or AccessFlag.ENUM

private const val ENUM_SYNTHETIC_VALUES_ACCESS_FLAGS =
    AccessFlag.PRIVATE or AccessFlag.STATIC or AccessFlag.FINAL or AccessFlag.SYNTHETIC

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
    result.classFile.apply {
        val cp = constPool
        accessFlags = kmClassWrapper.kmClass.jvmAccessFlags or AccessFlag.ENUM

        // Add enum value fields
        kmClassWrapper.kmClass.enumEntries.forEach { valueName ->
            withContext(valueName) {
                addField(
                    FieldInfo(cp, valueName, Descriptor.of(jvmName)).apply {
                        accessFlags = ENUM_VALUE_ACCESS_FLAGS
                    }
                )
            }
        }

        // Add $VALUES
        withContext("\$VALUES") {
            addField(
                FieldInfo(cp, "\$VALUES", Descriptor.of(enumArrayCtClass)).apply {
                    accessFlags = ENUM_SYNTHETIC_VALUES_ACCESS_FLAGS
                }
            )
        }
    }
    // Add $values()
    withContext("\$values") {
        val valuesList = kmClassWrapper.kmClass.enumEntries.joinToString(",")
        val body = when (valuesList) {
            "" -> "{ return new $javaName[0]; }"
            else -> "{ return new $javaName[]{ $valuesList }; }"
        }
        val syntheticValuesMethod =
            CtNewMethod.make(
                ENUM_SYNTHETIC_VALUES_ACCESS_FLAGS,
                enumArrayCtClass,
                "\$values",
                null,
                null,
                null,
                result
            )
        addCompilable(body, syntheticValuesMethod)
        result.addMethod(syntheticValuesMethod)
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
        val staticInit = result.makeClassInitializer()
        val initValues =
            kmClassWrapper.kmClass.enumEntries
                .mapIndexed { index, enumValue ->
                    "$enumValue = new $javaName(\"${enumValue}\", $index);"
                }.joinToString(" ")
        staticInit.setBody(
            "{ $initValues \$VALUES = \$values(); }"
        )
    }

    return result
}
