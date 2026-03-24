package viaduct.codegen.ct

import javassist.CtClass
import javassist.CtField
import javassist.CtMethod
import javassist.Modifier
import javassist.bytecode.Bytecode
import javassist.bytecode.MethodInfo
import kotlinx.metadata.KmProperty
import kotlinx.metadata.Visibility
import kotlinx.metadata.isNullable
import kotlinx.metadata.isVar
import kotlinx.metadata.visibility
import viaduct.codegen.utils.INVISIBLE
import viaduct.codegen.utils.VISIBLE

private val KmProperty.backingFieldName: String get() = backingFieldNameFor(name)

private fun backingFieldNameFor(name: String) = name

internal fun CtClass.addPropertyFromKm(
    ctx: CtGenContext,
    propWrapper: KmPropertyWrapper
) = ctx.withContext(propWrapper.property.name) {
    val typeCtClass = ctx.getClass(propWrapper.property.javaTypeName)

    this.addPropertyBackingField(propWrapper, typeCtClass)
    this.addPropertyGetter(ctx, propWrapper, typeCtClass)
    this.addPropertySetter(ctx, propWrapper, typeCtClass)
}

private fun CtClass.addPropertyBackingField(
    propWrapper: KmPropertyWrapper,
    typeCtClass: CtClass
) {
    if (!propWrapper.hasBackingField()) return

    val property = propWrapper.property
    val cp = this.classFile.constPool

    val field =
        CtField(typeCtClass, property.backingFieldName, this).also {
            // Kotlin `val` properties have JVM `final` backing fields, whereas `var` properties don't. Note that this
            // differs from the Kotlin `final`, which both val and var properties can have.
            it.modifiers = if (propWrapper.property.isVar) Modifier.PRIVATE else Modifier.PRIVATE or Modifier.FINAL

            if (propWrapper.static) it.modifiers = it.modifiers or Modifier.STATIC
            it.genericSignature = propWrapper.fieldJvmSignature
        }
    propWrapper.annotationsAttribute(cp, VISIBLE)?.let { field.fieldInfo.addAttribute(it) }
    val needsNullabilityAnnotation = (property.returnType.returnNullabilityAnnotation != null)
    val inviz = propWrapper.annotationsAttribute(cp, INVISIBLE, notNull = needsNullabilityAnnotation)
    property.returnType.returnNullabilityAnnotation?.let { inviz!!.addAnnotation(cp.annotation(it)) }
    inviz?.let { field.fieldInfo.addAttribute(it) }
    this.addField(field)
}

private fun CtClass.addPropertyGetter(
    ctx: CtGenContext,
    propWrapper: KmPropertyWrapper,
    typeCtClass: CtClass
) {
    val property = propWrapper.property

    val getterWrapper = propWrapper.getter
    if (getterWrapper.body != null) {
        this.addClassFunctionFromKm(ctx, getterWrapper)
        return
    }

    if (property.visibility == Visibility.PRIVATE) {
        return
    }

    val cp = this.classFile.constPool
    getterWrapper.function.let { getter ->
        ctx.withContext(getter.name) {
            val bc = Bytecode(cp)
            bc.addAload(0)
            bc.addGetfield(this, property.backingFieldName, property.fieldJvmDesc)
            bc.addReturn(typeCtClass)
            bc.maxLocals = 1
            val minfo =
                MethodInfo(cp, getter.name, getterWrapper.jvmDesc).apply {
                    accessFlags = getter.jvmAccessFlags
                    codeAttribute = bc.toCodeAttribute()
                }
            val method = CtMethod.make(minfo, this)
            method.setAdditionalInfoFromKm(getterWrapper, cp)
            this.addMethod(method)
        }
    }
}

private fun CtClass.addPropertySetter(
    ctx: CtGenContext,
    propWrapper: KmPropertyWrapper,
    typeCtClass: CtClass
) {
    val property = propWrapper.property
    val setterWrapper = propWrapper.setter

    // Intentionally checking for setterWrapper instead of hasSetter here because Kotlin var properties
    // with private default setters will have hasSetter == true, but setterWrapper == null.
    if (setterWrapper == null) return
    if (setterWrapper.body != null) {
        this.addClassFunctionFromKm(ctx, setterWrapper)
        return
    }

    if (property.visibility == Visibility.PRIVATE) {
        return
    }

    val cp = this.classFile.constPool
    val setter = setterWrapper.function
    ctx.withContext(setter.name) {
        val bc = Bytecode(cp)
        if (!property.returnType.isNullable && !property.returnType.isJavaPrimitive) {
            bc.addAload(1)
            bc.addLdc("<set-?>")
            bc.addInvokestatic(
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V"
            )
        }
        bc.addAload(0) // "this"
        val argSize = bc.addLoad(1, typeCtClass)
        bc.maxLocals = argSize + 1 // +1 for "this"
        bc.addPutfield(this, property.backingFieldName, property.fieldJvmDesc)
        bc.addReturn(CtClass.voidType)
        val minfo =
            MethodInfo(cp, setter.name, setterWrapper.jvmDesc).apply {
                accessFlags = setter.jvmAccessFlags
                codeAttribute = bc.toCodeAttribute()
            }
        val method = CtMethod.make(minfo, this)
        method.setAdditionalInfoFromKm(setterWrapper, cp)
        this.addMethod(method)
    }
}
