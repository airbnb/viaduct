package model.registry

import model.arg
import model.inputType
import model.requireObjectField
import viaduct.graphql.schema.ViaductSchema

/** One external `fromArgument` declaration compiled for canonical registry construction. */
class FromArgument private constructor(
    internal val argument: ViaductSchema.FieldArg,
    internal val inputPath: List<ViaductSchema.Field>,
) : VariableDeclaration {
    internal fun isCompatibleWith(
        locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
        locationHasDefault: Boolean,
    ): Boolean {
        var sourceType = argument.inputType
        var nullableTraversal = false
        inputPath.forEach { field ->
            nullableTraversal = nullableTraversal || sourceType.isNullable
            sourceType = field.inputType
        }
        return compatibleTypes(
            locationType = locationType,
            sourceType = sourceType,
            nullableTraversal = nullableTraversal,
            locationHasDefault = locationHasDefault,
        )
    }

    internal fun isCompatibleWithInclusionCondition(): Boolean {
        var sourceType = argument.inputType
        var nullableTraversal = false
        inputPath.forEach { field ->
            nullableTraversal = nullableTraversal || sourceType.isNullable
            sourceType = field.inputType
        }
        return sourceType.isCompatibleWithInclusionCondition(nullableTraversal)
    }

    companion object {
        internal fun of(
            argument: ViaductSchema.FieldArg,
            inputPath: List<ViaductSchema.Field>,
        ): FromArgument = FromArgument(argument, inputPath)
    }
}

/** Defines a variable from [argumentName] of its defining resolver [field]. */
fun ViaductSchema.fromArgument(
    field: ViaductSchema.ObjectField,
    argumentName: String,
): FromArgument = fromArgument(field, listOf(argumentName))

/** Defines a variable from a nonempty input-object [path] rooted at an argument of [field]. */
fun ViaductSchema.fromArgument(
    field: ViaductSchema.ObjectField,
    path: List<String>,
): FromArgument {
    require(requireObjectField(field.containingDef.name, field.name) == field) {
        "${field.containingDef.name}/${field.name} is not canonical in this schema"
    }
    require(path.isNotEmpty()) {
        "From-argument variable path must not be empty"
    }
    val argument =
        field.arg(path.first())
            ?: throw IllegalArgumentException(
                "${field.containingDef.name}/${field.name} has no argument ${path.first()}",
            )
    var currentType = argument.inputType
    val inputPath =
        path.drop(1).map { fieldName ->
            require(!currentType.isList) {
                "From-argument variable path cannot traverse list type $currentType"
            }
            val inputObject = currentType.baseTypeDef as? ViaductSchema.Input
                ?: throw IllegalArgumentException(
                    "From-argument variable path cannot traverse non-object type $currentType",
                )
            val inputField =
                inputObject.field(fieldName)
                    ?: throw IllegalArgumentException(
                        "Input object ${inputObject.name} has no field $fieldName",
                    )
            currentType = inputField.inputType
            inputField
        }
    return FromArgument.of(argument, inputPath)
}
