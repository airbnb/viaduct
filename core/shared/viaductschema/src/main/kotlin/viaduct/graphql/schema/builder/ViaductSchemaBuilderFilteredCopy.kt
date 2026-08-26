package viaduct.graphql.schema.builder

import viaduct.graphql.schema.ViaductSchema

internal class ViaductSchemaBuilderFilteredCopy(
    private val source: ViaductSchema,
    private val filter: ViaductSchemaBuilderFilter,
) {
    private val builder =
        ViaductSchemaBuilder(
            queryTypeName = source.queryTypeDef?.name,
            mutationTypeName = source.mutationTypeDef?.name,
            subscriptionTypeName = source.subscriptionTypeDef?.name,
            noStandardDefs = true,
        )

    fun copy(): ViaductSchemaBuilder {
        source.directives.values.forEach(::copyDirective)
        source.types.values.forEach { type ->
            when (type) {
                is ViaductSchema.Scalar -> copyScalar(type)
                is ViaductSchema.Enum -> copyEnum(type)
                is ViaductSchema.Union -> copyUnion(type)
                is ViaductSchema.Input -> copyInput(type)
                is ViaductSchema.Interface -> copyInterface(type)
                is ViaductSchema.Object -> copyObject(type)
            }
        }
        return builder
    }

    private fun clearRootIfNeeded(source: ViaductSchema.TopLevelDef) {
        if (source.name == builder.queryTypeName) builder.queryTypeName = null
        if (source.name == builder.mutationTypeName) builder.mutationTypeName = null
        if (source.name == builder.subscriptionTypeName) builder.subscriptionTypeName = null
    }

    private fun copyDirective(source: ViaductSchema.Directive) {
        if (!filter.filterTopLevelDef(source)) {
            return
        }
        val result =
            DirectiveBuilder(source.name)
                .repeatable(source.isRepeatable)
                .description(source.description)
                .sourceLocation(source.sourceLocation)
                .copyHolder(source)
        source.allowedLocations
            .filter { filter.filterDirectiveLocation(source, it) }
            .forEach(result::addLocation)
        source.args
            .filter(filter::filterArg)
            .map(::copyDirectiveArg)
            .forEach(result::addArgument)
        builder.addDefinition(result)
    }

    private fun copyDirectiveArg(source: ViaductSchema.DirectiveArg): ArgumentBuilder =
        ArgumentBuilder(source.name, copyType(source.type))
            .description(source.description)
            .copyDefault(source)
            .copyHolder(source)
            .copyAppliedDirectives(source, source.appliedDirectives)

    private fun copyScalar(source: ViaductSchema.Scalar) {
        if (!filter.filterTopLevelDef(source)) {
            clearRootIfNeeded(source)
            return
        }
        source.extensions.forEach { extension ->
            if (!extension.isBase && !filter.filterExtension(extension)) {
                return@forEach
            }
            val result =
                if (extension.isBase) {
                    ScalarTypeBuilder(source.name)
                        .description(source.description)
                        .copyHolder(source)
                } else {
                    ScalarTypeExtensionBuilder(source.name)
                }
            result.state.sourceLocation = extension.sourceLocation
            result.copyAppliedDirectives(extension)
            builder.addDefinition(result)
        }
    }

    private fun copyEnum(source: ViaductSchema.Enum) {
        if (!filter.filterTopLevelDef(source)) {
            clearRootIfNeeded(source)
            return
        }
        source.extensions.forEach { extension ->
            if (!extension.isBase && !filter.filterExtension(extension)) {
                return@forEach
            }
            val result =
                if (extension.isBase) {
                    EnumTypeBuilder(source.name)
                        .description(source.description)
                        .copyHolder(source)
                } else {
                    EnumTypeExtensionBuilder(source.name)
                }
            extension.members
                .filter(filter::filterEnumValue)
                .map(::copyEnumValue)
                .forEach { result.addEnumValue(it) }
            result.state.sourceLocation = extension.sourceLocation
            result.copyAppliedDirectives(extension)
            builder.addDefinition(result)
        }
    }

    private fun copyEnumValue(source: ViaductSchema.EnumValue): EnumValueBuilder =
        EnumValueBuilder(source.name)
            .description(source.description)
            .copyHolder(source)
            .copyAppliedDirectives(source, source.appliedDirectives)

    private fun copyUnion(source: ViaductSchema.Union) {
        if (!filter.filterTopLevelDef(source)) {
            clearRootIfNeeded(source)
            return
        }
        source.extensions.forEach { extension ->
            if (!extension.isBase && !filter.filterExtension(extension)) {
                return@forEach
            }
            val result =
                if (extension.isBase) {
                    UnionTypeBuilder(source.name)
                        .description(source.description)
                        .copyHolder(source)
                } else {
                    UnionTypeExtensionBuilder(source.name)
                }
            extension.members
                .filter { filter.filterMember(extension, it) }
                .forEach { result.addUnionMember(it.name) }
            result.state.sourceLocation = extension.sourceLocation
            result.copyAppliedDirectives(extension)
            builder.addDefinition(result)
        }
    }

    private fun copyInput(source: ViaductSchema.Input) {
        if (!filter.filterTopLevelDef(source)) {
            clearRootIfNeeded(source)
            return
        }
        source.extensions.forEach { extension ->
            if (!extension.isBase && !filter.filterExtension(extension)) {
                return@forEach
            }
            val result =
                if (extension.isBase) {
                    InputObjectTypeBuilder(source.name)
                        .description(source.description)
                        .copyHolder(source)
                } else {
                    InputObjectTypeExtensionBuilder(source.name)
                }
            extension.members
                .filter(filter::filterField)
                .map(::copyInputField)
                .forEach { result.addInputField(it) }
            result.state.sourceLocation = extension.sourceLocation
            result.copyAppliedDirectives(extension)
            builder.addDefinition(result)
        }
    }

    private fun copyInputField(source: ViaductSchema.Field): InputFieldBuilder =
        InputFieldBuilder(source.name, copyType(source.type))
            .description(source.description)
            .copyDefault(source)
            .copyHolder(source)
            .copyAppliedDirectives(source, source.appliedDirectives)

    private fun copyInterface(source: ViaductSchema.Interface) {
        if (!filter.filterTopLevelDef(source)) {
            clearRootIfNeeded(source)
            return
        }
        source.extensions.forEach { extension ->
            if (!extension.isBase && !filter.filterExtension(extension)) {
                return@forEach
            }
            val result =
                if (extension.isBase) {
                    InterfaceTypeBuilder(source.name)
                        .description(source.description)
                        .copyHolder(source)
                } else {
                    InterfaceTypeExtensionBuilder(source.name)
                }
            extension.supers
                .filter { filter.filterSupertype(extension, it) }
                .forEach { result.addInterfaceName(it.name) }
            extension.members
                .filter(filter::filterField)
                .map(::copyOutputField)
                .forEach { result.addOutputField(it) }
            result.state.sourceLocation = extension.sourceLocation
            result.copyAppliedDirectives(extension)
            builder.addDefinition(result)
        }
    }

    private fun copyObject(source: ViaductSchema.Object) {
        if (!filter.filterTopLevelDef(source)) {
            clearRootIfNeeded(source)
            return
        }
        source.extensions.forEach { extension ->
            if (!extension.isBase && !filter.filterExtension(extension)) {
                return@forEach
            }
            val result =
                if (extension.isBase) {
                    ObjectTypeBuilder(source.name)
                        .description(source.description)
                        .copyHolder(source)
                } else {
                    ObjectTypeExtensionBuilder(source.name)
                }
            extension.supers
                .filter { filter.filterSupertype(extension, it) }
                .forEach { result.addInterfaceName(it.name) }
            extension.members
                .filter(filter::filterField)
                .map(::copyOutputField)
                .forEach { result.addOutputField(it) }
            result.state.sourceLocation = extension.sourceLocation
            result.copyAppliedDirectives(extension)
            builder.addDefinition(result)
        }
    }

    private fun copyOutputField(source: ViaductSchema.Field): OutputFieldBuilder {
        val result =
            OutputFieldBuilder(source.name, copyType(source.type))
                .description(source.description)
                .copyHolder(source)
                .copyAppliedDirectives(source, source.appliedDirectives)
        source.args
            .filter(filter::filterArg)
            .map(::copyFieldArg)
            .forEach(result::addArgument)
        return result
    }

    private fun copyFieldArg(source: ViaductSchema.FieldArg): ArgumentBuilder =
        ArgumentBuilder(source.name, copyType(source.type))
            .description(source.description)
            .copyDefault(source)
            .copyHolder(source)
            .copyAppliedDirectives(source, source.appliedDirectives)

    private fun copyType(source: ViaductSchema.TypeExpr<*>): TypeExprBuilder {
        var result = TypeExprBuilder(source.baseTypeDef.name, source.baseTypeNullable)
        for (depth in source.listDepth - 1 downTo 0) {
            result = result.list(source.nullableAtDepth(depth))
        }
        return result
    }

    private fun copyAppliedDirective(source: ViaductSchema.AppliedDirective<*>): AppliedDirectiveBuilder {
        val result = AppliedDirectiveBuilder(source.name)
        source.arguments.forEach { (name, value) ->
            result.addArgument(name, value)
        }
        return result
    }

    private fun <T : DefinitionBuilder> T.copyHolder(source: ViaductSchema.Def): T =
        apply {
            state.copyHolder(source.holder)
        }

    private fun ArgumentBuilder.copyDefault(source: ViaductSchema.HasDefaultValue): ArgumentBuilder =
        apply {
            if (source.hasDefault) {
                defaultValue(source.defaultValue)
            }
        }

    private fun InputFieldBuilder.copyDefault(source: ViaductSchema.HasDefaultValue): InputFieldBuilder =
        apply {
            if (source.hasDefault) {
                defaultValue(source.defaultValue)
            }
        }

    private fun ArgumentBuilder.copyHolder(source: ViaductSchema.Def): ArgumentBuilder =
        apply {
            state.copyHolder(source.holder)
        }

    private fun EnumValueBuilder.copyHolder(source: ViaductSchema.Def): EnumValueBuilder =
        apply {
            state.copyHolder(source.holder)
        }

    private fun OutputFieldBuilder.copyHolder(source: ViaductSchema.Def): OutputFieldBuilder =
        apply {
            state.copyHolder(source.holder)
        }

    private fun InputFieldBuilder.copyHolder(source: ViaductSchema.Def): InputFieldBuilder =
        apply {
            state.copyHolder(source.holder)
        }

    private fun ArgumentBuilder.copyAppliedDirectives(
        owner: ViaductSchema.Def,
        directives: Collection<ViaductSchema.AppliedDirective<*>>,
    ): ArgumentBuilder =
        apply {
            directives
                .filter { filter.filterAppliedDirective(owner, it) }
                .map(::copyAppliedDirective)
                .forEach(::addAppliedDirective)
        }

    private fun EnumValueBuilder.copyAppliedDirectives(
        owner: ViaductSchema.Def,
        directives: Collection<ViaductSchema.AppliedDirective<*>>,
    ): EnumValueBuilder =
        apply {
            directives
                .filter { filter.filterAppliedDirective(owner, it) }
                .map(::copyAppliedDirective)
                .forEach(::addAppliedDirective)
        }

    private fun OutputFieldBuilder.copyAppliedDirectives(
        owner: ViaductSchema.Def,
        directives: Collection<ViaductSchema.AppliedDirective<*>>,
    ): OutputFieldBuilder =
        apply {
            directives
                .filter { filter.filterAppliedDirective(owner, it) }
                .map(::copyAppliedDirective)
                .forEach(::addAppliedDirective)
        }

    private fun InputFieldBuilder.copyAppliedDirectives(
        owner: ViaductSchema.Def,
        directives: Collection<ViaductSchema.AppliedDirective<*>>,
    ): InputFieldBuilder =
        apply {
            directives
                .filter { filter.filterAppliedDirective(owner, it) }
                .map(::copyAppliedDirective)
                .forEach(::addAppliedDirective)
        }

    private fun DefinitionBuilder.copyAppliedDirectives(source: ViaductSchema.Extension<*, *>) {
        source.appliedDirectives
            .filter { filter.filterExtensionAppliedDirective(source, it) }
            .map(::copyAppliedDirective)
            .forEach(state::addAppliedDirective)
    }

    private fun DefinitionBuilder.addEnumValue(value: EnumValueBuilder) {
        when (this) {
            is EnumTypeBuilder -> addValue(value)
            is EnumTypeExtensionBuilder -> addValue(value)
            else -> error("Cannot add an enum value to ${this::class.simpleName}")
        }
    }

    private fun DefinitionBuilder.addUnionMember(name: String) {
        when (this) {
            is UnionTypeBuilder -> addMember(name)
            is UnionTypeExtensionBuilder -> addMember(name)
            else -> error("Cannot add a union member to ${this::class.simpleName}")
        }
    }

    private fun DefinitionBuilder.addInputField(field: InputFieldBuilder) {
        when (this) {
            is InputObjectTypeBuilder -> addField(field)
            is InputObjectTypeExtensionBuilder -> addField(field)
            else -> error("Cannot add an input field to ${this::class.simpleName}")
        }
    }

    private fun DefinitionBuilder.addInterfaceName(name: String) {
        when (this) {
            is InterfaceTypeBuilder -> addInterface(name)
            is InterfaceTypeExtensionBuilder -> addInterface(name)
            is ObjectTypeBuilder -> addInterface(name)
            is ObjectTypeExtensionBuilder -> addInterface(name)
            else -> error("Cannot add an interface to ${this::class.simpleName}")
        }
    }

    private fun DefinitionBuilder.addOutputField(field: OutputFieldBuilder) {
        when (this) {
            is InterfaceTypeBuilder -> addField(field)
            is InterfaceTypeExtensionBuilder -> addField(field)
            is ObjectTypeBuilder -> addField(field)
            is ObjectTypeExtensionBuilder -> addField(field)
            else -> error("Cannot add an output field to ${this::class.simpleName}")
        }
    }
}
