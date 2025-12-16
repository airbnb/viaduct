package viaduct.codegen.ct

import java.lang.reflect.Modifier
import javassist.CtClass
import javassist.CtConstructor
import javassist.CtMethod
import javassist.CtNewConstructor
import javassist.CtNewMethod
import javassist.bytecode.AccessFlag
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.ConstPool
import javassist.bytecode.ParameterAnnotationsAttribute
import javassist.bytecode.SignatureAttribute
import javassist.bytecode.annotation.Annotation
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.isSecondary
import kotlinx.metadata.jvm.JvmMetadataVersion
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.utils.DEFAULT_IMPLS
import viaduct.codegen.utils.INVISIBLE
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.VISIBLE
import viaduct.codegen.utils.name

// Conversion of classes
internal fun CtGenContext.kmToCtInterface(
    kmClassWrapper: KmClassWrapper,
    outer: KmClassWrapper?
): CtClass {
    if (outer != null) {
        throw IllegalArgumentException("Nested interfaces are not supported ($kmClassWrapper).")
    }

    val result = getClass(kmClassWrapper.kmClass.kmName.asJavaBinaryName)
    result.classFile.accessFlags = ((kmClassWrapper.kmClass.jvmAccessFlags or AccessFlag.INTERFACE or AccessFlag.ABSTRACT) and AccessFlag.FINAL.inv())
    kmClassWrapper.annotationsAttribute(result.classFile.constPool, VISIBLE)?.let {
        result.classFile.addAttribute(it)
    }
    kmClassWrapper.annotationsAttribute(result.classFile.constPool, INVISIBLE)?.let {
        result.classFile.addAttribute(it)
    }
    result.addElementsFromKm(this, kmClassWrapper)
    return result
}

internal fun CtGenContext.kmToCtClass(kmClassWrapper: KmClassWrapper): CtClass {
    val result = getClass(kmClassWrapper.kmClass.kmName.asJavaBinaryName)
    result.classFile.accessFlags = kmClassWrapper.kmClass.jvmAccessFlags
    kmClassWrapper.annotationsAttribute(result.classFile.constPool, VISIBLE)?.let {
        result.classFile.addAttribute(it)
    }
    kmClassWrapper.annotationsAttribute(result.classFile.constPool, INVISIBLE)?.let {
        result.classFile.addAttribute(it)
    }
    result.addElementsFromKm(this, kmClassWrapper)
    return result
}

// Add the elements from a KmClass to a CtClass

private fun CtClass.addElementsFromKm(
    ctx: CtGenContext,
    kmClassWrapper: KmClassWrapper
) {
    this.applySupers(ctx, kmClassWrapper)

    when (kmClassWrapper.kmClass.kind) {
        ClassKind.CLASS -> {
            for (p in kmClassWrapper.properties) {
                this.addPropertyFromKm(ctx, p)
            }
            for (c in kmClassWrapper.constructors) {
                this.addConstructorFromKm(ctx, c)
            }
            for (f in kmClassWrapper.functions) this.addClassFunctionFromKm(ctx, f)
        }
        ClassKind.INTERFACE -> for (f in kmClassWrapper.functions) this.addInterfaceFunctionFromKm(ctx, f)
        else -> throw IllegalStateException("Unexpected KmClass that is neither a class or interface: ${kmClassWrapper.kmClass.name}")
    }
}

internal fun CtClass.applySupers(
    ctx: CtGenContext,
    kmClassWrapper: KmClassWrapper
) {
    val interfaceClassTypes = mutableListOf<SignatureAttribute.ClassType>()
    var superClassType: SignatureAttribute.ClassType? = null
    for (s in kmClassWrapper.kmClass.supertypes) {
        if (s.name == Km.ANY) continue
        val supCtClass = ctx.getClass(s.name.asCtName)
        val classType = s.jvmClassType
        if (supCtClass.isInterface) {
            this.useDefaultImpls(ctx, supCtClass, s.arguments.jvmTypeArgsSignatures, kmClassWrapper)
            interfaceClassTypes.add(classType)
        } else {
            superclass = supCtClass
            superClassType = classType
        }
    }
    genericSignature =
        SignatureAttribute
            .ClassSignature(
                arrayOf<SignatureAttribute.TypeParameter>(),
                superClassType,
                interfaceClassTypes.toTypedArray()
            ).encode()
}

/**
 * This function deals with the way in which Kotlin "traditionally" implements the default
 * implementation of interface functions (we say "traditionally" because Kotlin now supports
 * the JVM's implementation of these defaults, but that approach isn't yet widely used).
 * See the DefaultImpls section in learnings.md for more information.
 *
 * For the following Kotlin code:
 *   interface iface<T> {
 *       fun foo(p: String) = this
 *   }
 *
 *   // ClassCtClass and InterfaceCtClass are example receivers for this function
 *   class ClassCtClass: iface<String>
 *   interface InterfaceCtClass: iface<Int>
 *
 * The compiled bytecode equivalent is:
 *   public interface iface<T> {
 *       public iface<T> foo(String p);
 *
 *       public static final class iface.DefaultImpls {
 *           public static iface<T> foo(iface<T> $this, String p) {
 *               return this;
 *           }
 *       }
 *   }
 *
 *   public class ClassCtClass implements iface<String> {
 *       // useDefaultImpls adds this function to the CtClass
 *       public iface<String> foo(String p) {
 *           return iface.DefaultImpls.foo(this, p)
 *       }
 *   }
 *
 *   public interface InterfaceCtClass implements iface<Int> {
 *       // useDefaultImpls creates this DefaultImpls class if necessary
 *       public static final class DefaultImpls {
 *           // useDefaultImpls adds this function to the CtClass
 *           public static iface<Integer> foo(InterfaceCtClass $this, String p) {
 *               return iface.DefaultImpls.foo(this, p)
 *           }
 *      }
 *   }
 *
 * Note that "foo" isn't in the Kotlin metadata for ClassCtClass & InterfaceCtClass and thus not in KmClass
 * unless the Kotlin code explicitly overrides foo.
 *
 * @param iface: The interface for this CtClass to implement
 * @param ifaceTypeArgs: The parameterized type arguments for iface. For ClassCtClass in the example above,
 *                       this would be [<TypeArgument for String>]
 * @param kmClassWrapper: The KmClass to convert to this CtClass
 */
private fun CtClass.useDefaultImpls(
    ctx: CtGenContext,
    iface: CtClass,
    ifaceTypeArgs: Array<SignatureAttribute.TypeArgument>,
    kmClassWrapper: KmClassWrapper
) {
    this.addInterface(iface)

    val defaultImpls = ctx.getClassOrNull(JavaBinaryName(iface.name + "$" + DEFAULT_IMPLS))
    if (defaultImpls == null) return

    val defaultImplsName = CtName(defaultImpls.name).toString()

    val typeParams = iface.genericSignature?.let { SignatureAttribute.toClassSignature(it).parameters } ?: emptyArray()
    if (typeParams.size != ifaceTypeArgs.size) {
        throw IllegalStateException(
            "${iface.name}'s generic type parameters (${typeParams.map { it.name }}) dont match its parameterized " +
                "type args (${ifaceTypeArgs.map { it.type }})"
        )
    }
    val typeVariablesToArgs = typeParams.mapIndexed { i, param -> param.name to ifaceTypeArgs[i] }.toMap()

    // Add any non-abstract method implementations from iface
    for (defaultImplsMethod in defaultImpls.declaredMethods) {
        // Only add public non-synthetic methods to the implementer
        val modifiers = defaultImplsMethod.modifiers
        if (!Modifier.isPublic(modifiers) || (modifiers and AccessFlag.SYNTHETIC != 0)) continue

        // The DefaultImpls method is static, and its first param is an instance of the interface.
        // Strip this param from the descriptor to get the descriptor of the corresponding method in the interface
        val methodDescriptor = "(" + defaultImplsMethod.methodInfo.descriptor.substringAfter(";")

        // Check if the interface function is overridden in the KmClass
        val overridden =
            kmClassWrapper.functions.any {
                defaultImplsMethod.name == it.function.name && methodDescriptor == it.jvmDesc
            }
        if (overridden) continue

        val superClass =
            kmClassWrapper.kmClass.supertypes.firstNotNullOfOrNull {
                val c = ctx.getClass(it.name.asCtName)
                if (c.isInterface) null else c
            }
        val inSuperclass =
            superClass?.methods?.any {
                defaultImplsMethod.name == it.name && methodDescriptor == it.methodInfo.descriptor
            } ?: false
        if (inSuperclass) continue

        ctx.withContext(defaultImplsMethod.name) {
            if (this.isInterface) {
                val implementer = ctx.getOrCreateDefaultImpls(this)

                val methodBody = "{ return $defaultImplsName.${defaultImplsMethod.name}($$); }"
                val paramTypes = defaultImplsMethod.parameterTypes
                paramTypes[0] = this
                val method =
                    CtNewMethod
                        .make(
                            AccessFlag.PUBLIC or AccessFlag.STATIC,
                            defaultImplsMethod.returnType,
                            defaultImplsMethod.name,
                            paramTypes,
                            null,
                            null,
                            implementer
                        ).also {
                            it.copyAnnotations(defaultImplsMethod, this.classFile.constPool)
                            it.copyGenericSignature(defaultImplsMethod, typeVariablesToArgs) { paramTypes ->
                                paramTypes[0] = SignatureAttribute.ClassType(this.name)
                                paramTypes
                            }
                        }
                ctx.addCompilable(methodBody, method)
                implementer.addMethod(method)
            } else {
                val methodBody = "{ return $defaultImplsName.${defaultImplsMethod.name}(this, $$); }"
                val method =
                    CtNewMethod
                        .make(
                            AccessFlag.PUBLIC,
                            defaultImplsMethod.returnType,
                            defaultImplsMethod.name,
                            defaultImplsMethod.parameterTypes.drop(1).toTypedArray(),
                            null,
                            null,
                            this
                        ).also {
                            it.copyAnnotations(defaultImplsMethod, this.classFile.constPool) { annotations ->
                                annotations.drop(1).toTypedArray()
                            }
                            it.copyGenericSignature(defaultImplsMethod, typeVariablesToArgs) { paramTypes ->
                                paramTypes.drop(1).toTypedArray()
                            }
                        }
                ctx.addCompilable(methodBody, method)
                this.addMethod(method)
            }
        }
    }
}

/**
 * Converts a KmFunction to a CtMethod and adds it to the (non-interface) CtClass. If the KmFunction has any
 * params with a default value, creates an additional synthetic <funcName>$default method.
 */
internal fun CtClass.addClassFunctionFromKm(
    ctx: CtGenContext,
    fnWrapper: KmFunctionWrapper
) {
    if (this.isInterface) {
        throw IllegalArgumentException("addClassFunctionFromKm should only be called for non-interface receivers")
    }
    fnWrapper.checkAbstract()

    val paramTypes =
        fnWrapper.jvmValueParameters
            .map {
                ctx.getClass(it.javaTypeName)
            }.toTypedArray()

    ctx.withContext(fnWrapper.function.name) {
        val method =
            CtNewMethod.make(
                fnWrapper.function.jvmAccessFlags,
                ctx.getClass(fnWrapper.function.javaReturnTypeName),
                fnWrapper.function.name,
                paramTypes,
                null,
                null,
                this
            )
        ctx.addCompilable(fnWrapper.body ?: "<null>", method)

        method.setAdditionalInfoFromKm(fnWrapper, this.classFile.constPool)
        this.addMethod(method)

        if (fnWrapper.bridgeParameters.isNotEmpty()) {
            addBridgedClassFunction(
                ctx,
                method,
                paramTypes,
                fnWrapper
            )
        }
    }

    this.addDefaultMethodIfNecessary(ctx, fnWrapper)
}

private fun CtClass.addBridgedClassFunction(
    ctx: CtGenContext,
    bridgeTo: CtMethod,
    paramTypes: Array<CtClass>,
    fnWrapper: KmFunctionWrapper
) {
    // Consider a function where only the return type is bridged:
    //   base method:        fun f(): String = ""
    //   bridged method:     fun f(): Object = f() as String
    //
    // Because only the return type is different, the base and bridged methods have
    // the same signature. This is usually disambiguated via the const pool, however,
    // this is a little challenging to do in this layer, and the javassist compiler
    // doesn't always select the right method which causes infinite loops at runtime.
    //
    // To simplify this case, if only the return type is bridged, we can reuse the
    // bridged body with the base method body, which javassist is ok with.
    val bridgeBody =
        if (fnWrapper.bridgeParameters == setOf(-1)) {
            fnWrapper.body!!
        } else {
            bridgeMethodBody(bridgeTo, fnWrapper.bridgeParameters)
        }

    val bridgeReturnType =
        if (fnWrapper.bridgeParameters.contains(-1)) {
            ctx.getClass(Ct.OBJECT)
        } else {
            bridgeTo.returnType
        }
    val bridgeParamTypes =
        paramTypes
            .mapIndexed { idx, pt ->
                if (fnWrapper.bridgeParameters.contains(idx)) {
                    ctx.getClass(Ct.OBJECT)
                } else {
                    pt
                }
            }.toTypedArray()
    val synthBridge =
        CtNewMethod.make(
            AccessFlag.PUBLIC or AccessFlag.SYNTHETIC or AccessFlag.BRIDGE,
            bridgeReturnType,
            fnWrapper.function.name,
            bridgeParamTypes,
            emptyArray(),
            null,
            this
        )
    // For most methods we would call `setAdditionalInfoFromKm` here, which has the effect of setting a
    // `genericSignature` attribute.
    // This is not desirable for a synthetic bridge method, which should be typed on java.lang.Object
    // and not carry other type information.
    //
    // Instead, add the synthBridge method without additional type data.
    ctx.addCompilable(bridgeBody, synthBridge)
    this.addMethod(synthBridge)
}

/**
 * Converts a KmFunction to one or more CtMethods and adds it to the interface CtClass. The methods include:
 * 1. An abstract method is added to interface
 * 2. If the KmFunction is non-abstract, adds a static method to <this>$DefaultImpls
 * 3. If the KmFunction has default param values, adds a synthetic <funcName>$default method to <this>$DefaultImpls
 */
private fun CtClass.addInterfaceFunctionFromKm(
    ctx: CtGenContext,
    fnWrapper: KmFunctionWrapper
) {
    if (!this.isInterface) {
        throw IllegalArgumentException("addInterfaceFunctionFromKm should only be called for interface receivers")
    }
    fnWrapper.checkAbstract()

    ctx.withContext(fnWrapper.function.name) {
        val abstractMethod =
            CtNewMethod.abstractMethod(
                ctx.getClass(fnWrapper.function.javaReturnTypeName),
                fnWrapper.function.name,
                fnWrapper.jvmValueParameters
                    .map {
                        ctx.getClass(it.javaTypeName)
                    }.toTypedArray(),
                null,
                this
            )
        abstractMethod.setAdditionalInfoFromKm(fnWrapper, this.classFile.constPool)
        this.addMethod(abstractMethod)

        if (this.isInterface && fnWrapper.body != null) {
            val defaultImpls = ctx.getOrCreateDefaultImpls(this)
            val paramTypes = listOf(this) + fnWrapper.getCtParamTypes(ctx)
            val method =
                CtNewMethod.make(
                    fnWrapper.function.jvmAccessFlags or AccessFlag.STATIC,
                    ctx.getClass(fnWrapper.function.javaReturnTypeName),
                    fnWrapper.function.name,
                    paramTypes.toTypedArray(),
                    null,
                    null,
                    defaultImpls
                )
            method.setAdditionalInfoFromKm(fnWrapper, defaultImpls.classFile.constPool)
            ctx.addCompilable(fnWrapper.body, method)
            defaultImpls.addMethod(method)
        }
    }

    this.addDefaultMethodIfNecessary(ctx, fnWrapper)
}

/**
 * Adds a synthetic <fn name>$default method if any of the params have a default value. If the receiver
 * CtClass is an interface, the default method gets added to <this>$DefaultImpls.
 */
private fun CtClass.addDefaultMethodIfNecessary(
    ctx: CtGenContext,
    fnWrapper: KmFunctionWrapper
) {
    val defaultsCount =
        fnWrapper.function.valueParameters.count { it.declaresDefaultValue }
    val defaultValues = fnWrapper.defaultParamValues
    if (defaultValues.size != defaultsCount) {
        val msg = "Bad default count (${defaultValues.size} != $defaultsCount) for method ${fnWrapper.function.name}"
        throw IllegalStateException(msg)
    }
    if (defaultsCount == 0) {
        return
    }
    val defaultName = "${fnWrapper.function.name}\$default"
    ctx.withContext(defaultName) {
        var accessFlags = AccessFlag.STATIC or AccessFlag.SYNTHETIC
        if (fnWrapper.function.visibility != Visibility.PRIVATE) {
            accessFlags = accessFlags or AccessFlag.PUBLIC
        }

        val intType = ctx.getClass(Ct.INT)
        val paramsForDefaultMethod = mutableListOf(this)
        paramsForDefaultMethod.addAll(fnWrapper.getCtParamTypes(ctx))
        repeat(defaultBitmaskCount(fnWrapper.function.valueParameters.size)) { paramsForDefaultMethod.add(intType) }
        paramsForDefaultMethod.add(ctx.getClass(Ct.OBJECT))

        val declaringClass = if (this.isInterface) ctx.getOrCreateDefaultImpls(this) else this

        val defaultMethod =
            CtNewMethod.make(
                accessFlags,
                ctx.getClass(fnWrapper.function.javaReturnTypeName),
                defaultName,
                paramsForDefaultMethod.toTypedArray(),
                null,
                null,
                declaringClass
            )
        ctx.addCompilable(
            defaultMethodBody(fnWrapper, fnWrapper.defaultParamValues, paramsForDefaultMethod.size),
            defaultMethod
        )
        declaringClass.addMethod(defaultMethod)
    }
}

internal fun CtClass.addConstructorFromKm(
    ctx: CtGenContext,
    kmCtorWrapper: KmConstructorWrapper
) {
    val cp = this.classFile.constPool
    val paramTypes = kmCtorWrapper.constructor.valueParameters
        .map { ctx.getClass(it.javaTypeName) }
        .toTypedArray()
    val label = "ctor(${paramTypes.joinToString(", ") { it.name }})"
    val ctorBody =
        if (kmCtorWrapper.superCall == null) {
            kmCtorWrapper.body!!
        } else {
            "{\n ${kmCtorWrapper.superCall}\n ${kmCtorWrapper.body!!}\n}"
        }
    val visibleAnnotations = kmCtorWrapper.annotationsAttribute(cp, VISIBLE)
    val invisibleAnnotations = kmCtorWrapper.annotationsAttribute(cp, INVISIBLE)

    ctx.withContext(label) {
        val constructor =
            CtConstructor(paramTypes, this).apply {
                modifiers = kmCtorWrapper.constructor.visibility.jvmAccessFlags
                genericSignature = kmCtorWrapper.constructor.jvmSignature
                visibleAnnotations?.let { methodInfo.addAttribute(it) }
                invisibleAnnotations?.let { methodInfo.addAttribute(it) }
                kmCtorWrapper.visibleParameterAnnotationsAttribute(cp)?.let { methodInfo.addAttribute(it) }

                if (kmCtorWrapper.constructor.visibility != Visibility.PRIVATE) {
                    nullabilityParamAnnotationsAttribute(
                        kmCtorWrapper.constructor.valueParameters.map { it.type },
                        cp
                    )?.let { methodInfo.addAttribute(it) }
                }
            }
        ctx.addCompilable(ctorBody, constructor)
        this.addConstructor(constructor)
    }

    // If any params have a default value, add a default version of the constructor
    val defaultsCount =
        kmCtorWrapper.constructor.valueParameters.count {
            it.declaresDefaultValue
        }
    val defaultValues = kmCtorWrapper.defaultParamValues
    if (defaultValues.size != defaultsCount) {
        val msg = "Bad default count (${defaultValues.size} != $defaultsCount) for method $label"
        throw IllegalStateException(msg)
    }
    if (0 < defaultsCount) {
        ctx.withContext("$label\$default") {
            val intType = ctx.getClass(Ct.INT)
            val paramsForDefaultConstructor = paramTypes.toMutableList()
            repeat(defaultBitmaskCount(kmCtorWrapper.constructor.valueParameters.size)) {
                paramsForDefaultConstructor.add(
                    intType
                )
            }
            paramsForDefaultConstructor.add(ctx.getClass(Ct.DEFAULT_MARKER))

            val defaultCtorBody = defaultConstructorBody(kmCtorWrapper, defaultValues, kmCtorWrapper.body)
            val defaultConstructor =
                CtConstructor(paramsForDefaultConstructor.toTypedArray(), this)
                    .apply {
                        modifiers = AccessFlag.PUBLIC or AccessFlag.SYNTHETIC
                    }
            ctx.addCompilable(defaultCtorBody, defaultConstructor)
            this.addConstructor(defaultConstructor)
        }
    } else if (kmCtorWrapper.genSyntheticAccessor) {
        ctx.withContext("${label}_syntheticAccessor") {
            val params =
                paramTypes.let {
                    val l = it.toMutableList()
                    l.add(ctx.getClass(Ct.DEFAULT_MARKER))
                    l.toTypedArray()
                }

            val synthCtor =
                CtConstructor(params, this)
                    .apply {
                        modifiers = AccessFlag.PUBLIC or AccessFlag.SYNTHETIC
                    }
            ctx.addCompilable(synthAccessorCtorBody(kmCtorWrapper), synthCtor)
            this.addConstructor(synthCtor)
        }
    }

    // Add an empty constructor if all of these conditions are true:
    // - all params have default values
    // - constructor is public
    // - constructor is not secondary
    ctx.withContext("$label-empty") {
        if (kmCtorWrapper.constructor.valueParameters.isNotEmpty() &&
            kmCtorWrapper.constructor.valueParameters.all { it.declaresDefaultValue } &&
            !kmCtorWrapper.constructor.isSecondary &&
            kmCtorWrapper.constructor.visibility == Visibility.PUBLIC
        ) {
            val params =
                kmCtorWrapper.constructor.valueParameters.map {
                    it.zeroValueExpression
                } + List(defaultBitmaskCount(kmCtorWrapper.constructor.valueParameters.size)) { "-1" } + listOf("null")
            val emptyCtorBody = "{ this(${params.joinToString(",")}); }"
            val emptyConstructor =
                CtNewConstructor
                    .make(
                        emptyArray(),
                        null,
                        null,
                        this
                    ).apply {
                        modifiers = AccessFlag.PUBLIC
                        visibleAnnotations?.let { methodInfo.addAttribute(it) }
                        invisibleAnnotations?.let { methodInfo.addAttribute(it) }
                    }
            ctx.addCompilable(emptyCtorBody, emptyConstructor)
            this.addConstructor(emptyConstructor)
        }
    }
}

// ctx.context already has the function name as context
internal fun CtMethod.setAdditionalInfoFromKm(
    fnWrapper: KmFunctionWrapper,
    cp: ConstPool
) {
    val isDefaultImpls = this.declaringClass.name.endsWith(DEFAULT_IMPLS)
    // Set signature
    this.genericSignature =
        when (isDefaultImpls) {
            true -> fnWrapper.defaultImplsJvmSignature(this.declaringClass)
            false -> fnWrapper.jvmSignature
        }

    // Set annotations
    fnWrapper.annotationsAttribute(cp, VISIBLE)?.let { this.methodInfo.addAttribute(it) }
    val nullabilityAnnotation = fnWrapper.function.jvmReturnType.returnNullabilityAnnotation
    val needsNullabilityAnnotation = nullabilityAnnotation != null
    val inviz = fnWrapper.annotationsAttribute(cp, INVISIBLE, notNull = needsNullabilityAnnotation)
    nullabilityAnnotation?.let { inviz!!.addAnnotation(cp.annotation(it)) }
    inviz?.let { this.methodInfo.addAttribute(it) }

    // Set parameter annotations
    val valueParams =
        when (isDefaultImpls) {
            true -> fnWrapper.defaultImplsJvmValueParameters(this.declaringClass)
            false -> fnWrapper.jvmValueParameters
        }
    nullabilityParamAnnotationsAttribute(valueParams.map { it.type }, cp)?.let {
        this.methodInfo.addAttribute(it)
    }
}

private fun CtMethod.copyAnnotations(
    from: CtMethod,
    cp: ConstPool,
    paramAnnotationsTransformer: ((Array<Array<Annotation>>) -> Array<Array<Annotation>>)? = null
) {
    from.methodInfo.attributes.forEach { attribute ->
        if (attribute is AnnotationsAttribute) {
            this.methodInfo.addAttribute(attribute.copy(cp, null))
        } else if (attribute is ParameterAnnotationsAttribute) {
            val paramAnnotations = attribute.copy(cp, null) as ParameterAnnotationsAttribute
            if (paramAnnotationsTransformer != null) {
                paramAnnotations.annotations = paramAnnotationsTransformer(paramAnnotations.annotations)
            }
            if (paramAnnotations.annotations.any { it.isNotEmpty() }) {
                this.methodInfo.addAttribute(paramAnnotations)
            }
        }
    }
}

/**
 * Copies the generic signature from another CtMethod, replacing generic type parameters where applicable.
 *
 * @param typeVariablesToArgs: If the `from` method's declaring class has any type parameters, this is a map
 *        from the generic type parameter name to the parameterized type parameter, e.g.,
 *        mapOf("T" to <TypeArgument for String>) for ClassCtClass in the useDefaultImpls example above.
 *
 * @see useDefaultImpls
 */
private fun CtMethod.copyGenericSignature(
    from: CtMethod,
    typeVariablesToArgs: Map<String, SignatureAttribute.TypeArgument>,
    paramTypeTransformer: ((Array<SignatureAttribute.Type>) -> Array<SignatureAttribute.Type>)? = null
) {
    if (from.genericSignature == null) return
    val toCopy = SignatureAttribute.toMethodSignature(from.genericSignature)
    val paramTypes =
        paramTypeTransformer?.let {
            it(toCopy.parameterTypes)
        } ?: toCopy.parameterTypes
    this.genericSignature =
        SignatureAttribute
            .MethodSignature(
                toCopy.typeParameters.filter { !typeVariablesToArgs.containsKey(it.name) }.toTypedArray(),
                paramTypes.map { it.replaceTypeVariables(typeVariablesToArgs) }.toTypedArray(),
                toCopy.returnType.replaceTypeVariables(typeVariablesToArgs),
                toCopy.exceptionTypes
            ).encode()
}

/**
 * Replaces generic with parameterized type parameters for the Type,
 * e.g., replacing iface<T> with iface<String> in the useDefaultImpls example above.
 *
 * @see useDefaultImpls
 */
private fun SignatureAttribute.Type.replaceTypeVariables(typeVariablesToArgs: Map<String, SignatureAttribute.TypeArgument>): SignatureAttribute.Type {
    if (this is SignatureAttribute.ClassType && typeVariablesToArgs.isNotEmpty()) {
        return SignatureAttribute.ClassType(
            this.name,
            this.typeArguments
                ?.map {
                    val type = it.type
                    if (type is SignatureAttribute.TypeVariable && typeVariablesToArgs.containsKey(type.name)) {
                        typeVariablesToArgs.getValue(type.name)
                    } else {
                        it
                    }
                }?.toTypedArray()
        )
    }
    return this
}

private fun CtGenContext.getOrCreateDefaultImpls(iface: CtClass): CtClass {
    val existing = this.getClassOrNull(JavaBinaryName(iface.name + "$" + DEFAULT_IMPLS))
    if (existing != null) return existing

    val defaultImpls = iface.makeNestedClassFixed(DEFAULT_IMPLS, AccessFlag.PUBLIC or AccessFlag.FINAL)
    defaultImpls.classFile.addAttribute(
        AnnotationsAttribute(defaultImpls.classFile.constPool, AnnotationsAttribute.visibleTag).also {
            it.addAnnotation(
                defaultImpls.asCtAnnotation(KotlinClassMetadata.SyntheticClass(null, JvmMetadataVersion.LATEST_STABLE_SUPPORTED, 0).write())
            )
        }
    )
    return defaultImpls
}

// Method body generators

/**
 * For the following Kotlin function in class Foo:
 *     fun bar(a: Int = 50, b: String?, c: Boolean? = null) { ... }
 *
 * This is the method body for bar$default(Foo f, int a, String b, Boolean c, int n, Object o):
 *     if ((n & 1) != 0) {
 *         a = 50;
 *     }
 *     if (n & 4) != 0) {
 *         c = null;
 *     }
 *     return f.bar(a, b, c);
 */
private fun defaultMethodBody(
    fnWrapper: KmFunctionWrapper,
    defaultValues: Map<JavaIdName, String>,
    numParamsForDefault: Int
) = buildString {
    val m = fnWrapper.function.modality
    val superCallCheck =
        if (m == Modality.OPEN || m == Modality.ABSTRACT) {
            """
            if ($$numParamsForDefault != null) {
                throw new java.lang.UnsupportedOperationException(
                    "Super calls with default arguments not supported in this target, function: ${fnWrapper.function.name}"
                );
            }
            """.trimIndent()
        } else {
            ""
        }

    val firstParamPosition = 2
    val firstBitmaskPosition = fnWrapper.jvmValueParameters.size + firstParamPosition
    val paramsString = (2..(fnWrapper.jvmValueParameters.size + 1)).joinToString(", ") { "$$it" }
    val ret =
        """
        {
            $superCallCheck
            ${defaultValuesCodeBlock(fnWrapper.function.valueParameters, defaultValues, firstParamPosition, firstBitmaskPosition)}
            return $1.${fnWrapper.function.name}($paramsString);
        }
        """.trimIndent()
    return ret
}

/**
 * For the following Kotlin constructor:
 *     class Foo(a: Int = 50, b: String?, c: Boolean? = null) { ... }
 *
 * This is the body for the synthetic constructor Foo(int a, String b, Boolean c, int n, DefaultConstructorMarker d):
 *     super(); // inserted implicitly by javassist
 *     if ((n & 1) != 0) {
 *         a = 50;
 *     }
 *     if (n & 4) != 0) {
 *         c = null;
 *     }
 *     <insert body of primary constructor here>
 */
private fun defaultConstructorBody(
    ctorWrapper: KmConstructorWrapper,
    defaultValues: Map<JavaIdName, String>,
    primaryConstructorBody: String
): String {
    val firstBitmaskPosition = 1 + ctorWrapper.constructor.valueParameters.size
    return buildString {
        append("{\n")
        if (ctorWrapper.superCall != null) {
            append("${ctorWrapper.superCall}\n")
        }
        append(defaultValuesCodeBlock(ctorWrapper.constructor.valueParameters, defaultValues, 1, firstBitmaskPosition))
        append(primaryConstructorBody)
        append("}\n")
    }
}

private fun synthAccessorCtorBody(ctorWrapper: KmConstructorWrapper): String =
    buildString {
        append("{\n")
        append("this(")
        append(
            // Synthetic Accessors have a DefaultConstructorMarker as their last parameter.
            // Using the javassist "$$" notation will forward the DefaultConstructorMarker
            // to our target constructor, which we want to avoid.
            // Manually create a "$1, $2, ..." string
            ctorWrapper.constructor.valueParameters.indices
                .joinToString(",") { "\$${it + 1}" }
        )
        append(")\n;")
        append("}")
    }

private fun castExpr(castTo: CtClass): String =
    buildString {
        append("(")
        append(castTo.name)
        append(")")
    }

/**
 * Generate a body for a bridge method. Currently only supports instance methods
 */
private fun bridgeMethodBody(
    bridgeTo: CtMethod,
    bridgeParameters: Set<Int>
): String =
    buildString {
        append("{\n")
        append("return ")
        if (bridgeParameters.contains(-1)) {
            // cast return type
            append(castExpr(bridgeTo.returnType))
            append(" ")
        }
        append("$0.")
        append(bridgeTo.name)
        append("(")
        append(
            bridgeTo.parameterTypes
                .mapIndexed { idx, pt ->
                    buildString {
                        if (bridgeParameters.contains(idx)) {
                            append(castExpr(pt))
                        }
                        append("$${idx + 1}")
                    }
                }.joinToString(",")
        )
        append(");\n")
        append("}")
    }

/**
 * @param valueParams: Parameters of the non-default method. Does not include the additional parameters the Kotlin
 *                     compiler adds to the default method.
 * @param defaultValues: Map from parameter-name (of params that have defaults) to their default value
 * @param firstParamPosition: The position of valueParams.first() in the default method's param list.
 *                            Note that the index for params starts at 1 so if firstParamPosition is 1,
 *                            that first param can be accessed via $1 in the default method body.
 * @param firstBitmaskPosition: The position of the first bitmask int
 */
private fun defaultValuesCodeBlock(
    valueParams: List<KmValueParameter>,
    defaultValues: Map<JavaIdName, String>,
    firstParamPosition: Int,
    firstBitmaskPosition: Int
) = buildString {
    valueParams.forEachIndexed { index, param ->
        val paramName = JavaIdName(param.name)
        if (!param.declaresDefaultValue) return@forEachIndexed
        if (!defaultValues.containsKey(paramName)) {
            throw IllegalArgumentException(
                "Missing default value for KmValueParameter ${param.name} with" +
                    " Flag.ValueParameter.DECLARES_DEFAULT_VALUE set"
            )
        }

        // For every 32 params, there's an additional int bitmask param used to determine if
        // any of those params is set
        val maskIndex = firstBitmaskPosition + (index / BITS_IN_INT)
        val paramBit = 1 shl (index and 31)
        append(
            """
            if (($$maskIndex & $paramBit) != 0) {
                $${firstParamPosition + index} = ${defaultValues[paramName]};
            }

            """.trimIndent()
        )
    }
}

// Misc helpers and constants

/**
 * The number of bitmasks to append to a default method or constructor's params
 * @param numParams: The number of parameters in the Kotlin function / constructor
 */
private fun defaultBitmaskCount(numParams: Int): Int = (numParams + BITS_IN_INT - 1) / BITS_IN_INT

private fun KmFunctionWrapper.checkAbstract() {
    val isAbstract = (function.modality == Modality.ABSTRACT)

    if (isAbstract && body != null) {
        throw IllegalArgumentException("Abstract functions can't have a body.")
    }

    // Javassist doesn't require this, but we require it for consistency with
    // our requirements for constructors
    if (!isAbstract && body == null) {
        throw IllegalArgumentException("Non-abstract functions must have a body")
    }
}

private fun KmFunctionWrapper.getCtParamTypes(ctx: CtGenContext) = jvmValueParameters.map { ctx.getClass(it.javaTypeName) }

private const val BITS_IN_INT = 32
