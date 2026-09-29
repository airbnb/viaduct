package model

import viaduct.graphql.schema.ViaductSchema

fun ViaductSchema.requireQueryTypeDef(): ViaductSchema.Object = queryTypeDef ?: error("Schema has no query root")

fun ViaductSchema.requireType(name: String): ViaductSchema.TypeDef = types[name] ?: error("Schema has no type named $name")

fun ViaductSchema.requireField(
    typeName: String,
    fieldName: String,
): ViaductSchema.Field {
    val containingDef = requireType(typeName) as? ViaductSchema.OutputRecord
    return containingDef?.field(fieldName)
        ?: error("Schema has no field named $typeName/$fieldName")
}

fun ViaductSchema.requireObjectField(
    typeName: String,
    fieldName: String,
): ViaductSchema.ObjectField {
    val containingDef = requireType(typeName) as? ViaductSchema.Object
    return containingDef?.field(fieldName)
        ?: error("Schema has no object field named $typeName/$fieldName")
}

fun ViaductSchema.OutputRecord.requireField(name: String): ViaductSchema.Field = field(name) ?: error("Output record ${this.name} has no field named $name")

fun ViaductSchema.Object.requireField(name: String): ViaductSchema.ObjectField = field(name) ?: error("Object ${this.name} has no field named $name")

fun ViaductSchema.Input.requireField(name: String): ViaductSchema.Field = field(name) ?: error("Input object ${this.name} has no field named $name")

fun ViaductSchema.Field.arg(name: String): ViaductSchema.FieldArg? = args.find { it.name == name }

fun ViaductSchema.Field.requireArg(name: String): ViaductSchema.FieldArg = arg(name) ?: error("Field ${containingDef.name}/${this.name} has no argument named $name")

fun ViaductSchema.Enum.requireValue(name: String): ViaductSchema.EnumValue = value(name) ?: error("Enum ${this.name} has no value named $name")

val ViaductSchema.HasDefaultValue.inputType:
    ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>
    get() = type.requireInputType()

val ViaductSchema.Field.outputType:
    ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>
    get() = type.requireOutputType()

@Suppress("UNCHECKED_CAST")
fun ViaductSchema.TypeExpr<ViaductSchema.TypeDef>.requireInputType(): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    require(baseTypeDef is ViaductSchema.InputTypeDef) {
        "$this is not an input type"
    }
    return this as ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>
}

@Suppress("UNCHECKED_CAST")
fun ViaductSchema.TypeExpr<ViaductSchema.TypeDef>.requireOutputType(): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> {
    require(baseTypeDef is ViaductSchema.OutputTypeDef) {
        "$this is not an output type"
    }
    return this as ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>
}

@Suppress("UNCHECKED_CAST")
fun ViaductSchema.TypeExpr<ViaductSchema.TypeDef>.requireSimpleType(): ViaductSchema.TypeExpr<ViaductSchema.SimpleTypeDef> {
    require(baseTypeDef is ViaductSchema.SimpleTypeDef) {
        "$this is not a simple type"
    }
    return this as ViaductSchema.TypeExpr<ViaductSchema.SimpleTypeDef>
}

internal fun ViaductSchema.TypeExpr<ViaductSchema.TypeDef>.canContainPure(inner: ViaductSchema.TypeExpr<ViaductSchema.TypeDef>): Boolean {
    if (!isNullable && inner.isNullable) return false
    val outerElement = unwrapList()
    val innerElement = inner.unwrapList()
    if (outerElement != null || innerElement != null) {
        return outerElement != null &&
            innerElement != null &&
            outerElement.canContainPure(innerElement)
    }

    val outerType = baseTypeDef
    val innerType = inner.baseTypeDef
    return when {
        outerType is ViaductSchema.InputTypeDef || innerType is ViaductSchema.InputTypeDef ->
            outerType == innerType
        outerType is ViaductSchema.CompositeTypeDef && innerType is ViaductSchema.Object ->
            innerType in outerType.possibleObjectTypes
        else -> outerType == innerType
    }
}
