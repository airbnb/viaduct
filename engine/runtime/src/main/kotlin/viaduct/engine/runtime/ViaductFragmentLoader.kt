package viaduct.engine.runtime

import graphql.GraphqlErrorBuilder
import graphql.execution.FieldCollector
import graphql.execution.FieldCollectorParameters
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.NodeUtil
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.derived.DerivedFieldQueryMetadata
import viaduct.engine.api.fragment.ExecutableFragmentParser
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentFieldEngineResolutionResult
import viaduct.engine.api.fragment.errors.FragmentFieldEngineResolutionError
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT
import viaduct.utils.collections.parallelMap
import viaduct.utils.slf4j.logger

@ExperimentalCoroutinesApi
@Suppress("ktlint:standard:indent")
class ViaductFragmentLoader
    @Inject
    constructor(
        private val fragmentTransformer: ExecutableFragmentParser
    ) : FragmentLoader {
            companion object {
                private val log by logger()
            }

            private fun SelectionSet.toMergedField(): MergedField {
                val field = Field.newField().selectionSet(this).build()
                return MergedField.newMergedField(field).build()
            }

            override suspend fun loadFromEngine(
                fragment: Fragment,
                metadata: DerivedFieldQueryMetadata,
                source: Any?,
                dataFetchingEnvironment: DataFetchingEnvironment?
            ): FragmentFieldEngineResolutionResult {
                val (objectEngineResult, metadataForField) =
                    loadEngineObjectDataImpl(
                        fragment,
                        metadata,
                        dataFetchingEnvironment!! // shouldUseEngineResultLoader checks for non-null
                    )

                val fieldPrefix = metadataForField.prefix

                // transform the document to add any named fragments and insert any dynamic fragments
                val fragmentDocument = fragmentTransformer.parse(fragment)

                val fragmentDefinition = fragmentDocument.getFirstDefinitionOfType(FragmentDefinition::class.java).orElse(null)
                    ?: throw IllegalStateException("Invariant: expected fragment document to have a FragmentDefinition.")

                val engineExecutionContext = dataFetchingEnvironment.getLocalContextForType<EngineExecutionContextImpl>()
                    ?: throw IllegalStateException("Missing EngineExecutionContext")

                val (data, errors) =
                    resolveObjectEngineResult(
                        objectEngineResult,
                        fragmentDefinition.selectionSet.toMergedField(),
                        fieldPrefix,
                        fragmentsByName = NodeUtil.getFragmentsByName(fragmentDocument),
                        variables = fragment.variables.asMap(),
                        schema = engineExecutionContext.fullSchema.schema,
                    )

                val fragmentResolutionErrors =
                    errors.map {
                        FragmentFieldEngineResolutionError(
                            GraphqlErrorBuilder.newError()
                                .message(it.throwable.message)
                                .path(it.path)
                                .build(),
                            it.throwable
                        )
                    }

                val result = FragmentFieldEngineResolutionResult(data, fragmentResolutionErrors)
                log.debug(
                    "FragmentFieldResolutionResult [CompositeFragmentLoader] for class '{}': {}",
                    metadata.providerShortClasspath,
                    result
                )
                return result
            }

            override suspend fun loadEngineObjectData(
                fragment: Fragment,
                metadata: DerivedFieldQueryMetadata,
                source: Any,
                dataFetchingEnvironment: DataFetchingEnvironment
            ): ObjectEngineResult {
                return loadEngineObjectDataImpl(fragment, metadata, dataFetchingEnvironment).first
            }

            private fun loadEngineObjectDataImpl(
                fragment: Fragment,
                metadata: DerivedFieldQueryMetadata,
                dataFetchingEnvironment: DataFetchingEnvironment
            ): Pair<ObjectEngineResult, FieldRewriterMetadata> {
                val path = ResultPath.fromList(dataFetchingEnvironment.executionStepInfo.path.keysOnly)
                val engineResultLocalContext =
                    dataFetchingEnvironment.getLocalContextForType<EngineResultLocalContext>()
                        ?: throw IllegalStateException(
                            "Invariant: expected EngineResultLocalContext @ $path. Is `EngineResultInstrumentation` enabled?"
                        )
                val allMetadataForField =
                    (dataFetchingEnvironment.field as? FieldWithMetadata)?.metadata
                        ?: throw UnsupportedOperationException(
                            "Invariant: expected `FieldWithMetadata` but got `Field` @ $path. " +
                                "Is `DocumentRewriterInstrumentation` enabled?"
                        )
                val metadataForField =
                    allMetadataForField.firstOrNull {
                        if (it is FieldRewriterMetadata) {
                            if (it.classPath == metadata.classPath) {
                                return@firstOrNull true
                            }
                        }
                        return@firstOrNull false
                    } as? FieldRewriterMetadata
                        ?: throw IllegalStateException(
                            "Invariant: expected metadata to be available for field. " +
                                "Check implementation of BaseDFPRewriterStrategy."
                        )

                // resolve late resolved variables
                val variablesForFragment = fragment.variables.asMap()
                // loop through all the late resolved variables...
                metadataForField.lateResolvedVariables.forEach { (key, lateResolvedVariable) ->
                    lateResolvedVariable as? DeferredLateResolvedVariable
                        ?: throw IllegalStateException(
                            "Temporary invariant: we only know how to handle DeferredLateResolvedVariable."
                        )
                    // if we have a value for the variable, complete it
                    if (key in variablesForFragment) {
                        lateResolvedVariable.complete(variablesForFragment[key])
                    } else {
                        lateResolvedVariable.complete(null)
                    }
                }

                val objectEngineResult =
                    if (metadata.onRootQuery) {
                        engineResultLocalContext.queryEngineResult
                    } else {
                        engineResultLocalContext.parentEngineResult
                    }
                return objectEngineResult to metadataForField
            }

            private suspend fun unwrapListOfObjectEngineResults(
                list: List<*>,
                parentPath: ResultPath,
                mergedField: MergedField,
                fieldPrefix: String,
                fragmentsByName: Map<String, FragmentDefinition>,
                variables: Map<String, Any?>,
                schema: GraphQLSchema
            ): Pair<List<*>, List<ResolutionError>> {
                val errors = mutableListOf<ResolutionError>()
                val data =
                    list.mapIndexed { i, obj ->
                        val currentPath = parentPath.segment(i)
                        if (obj is List<*>) {
                            val (nestedList, nestedErrors) =
                                unwrapListOfObjectEngineResults(
                                    obj,
                                    parentPath,
                                    mergedField,
                                    fieldPrefix,
                                    fragmentsByName,
                                    variables,
                                    schema
                                )
                            errors.addAll(nestedErrors)
                            nestedList
                        } else if (obj is ObjectEngineResult) {
                            val (nestedData, nestedErrors) =
                                resolveObjectEngineResult(
                                    obj,
                                    mergedField,
                                    fieldPrefix,
                                    fragmentsByName,
                                    variables,
                                    schema,
                                    currentPath
                                )
                            errors.addAll(nestedErrors)
                            nestedData
                        } else {
                            obj
                        }
                    }
                return Pair(data, errors)
            }

            private val fieldCollector = FieldCollector()

            private suspend fun resolveObjectEngineResult(
                objectEngineResult: ObjectEngineResult,
                mergedField: MergedField,
                // metadata
                fieldPrefix: String,
                fragmentsByName: Map<String, FragmentDefinition>,
                variables: Map<String, Any?>,
                schema: GraphQLSchema,
                parentPath: ResultPath = ResultPath.rootPath()
            ): Pair<Map<String, Any?>, List<ResolutionError>> {
                val params =
                    FieldCollectorParameters.newParameters()
                        .schema(schema)
                        .variables(variables)
                        .fragments(fragmentsByName)
                        .objectType(objectEngineResult.graphQLObjectType)
                        .build()

                val mergedSelectionSet = fieldCollector.collectFields(params, mergedField)

                // we now can recurse through the merged selection set and wait for the OER fields
                val subFields = mergedSelectionSet.subFields
                val errors = mutableListOf<ResolutionError>()
                return subFields.entries.parallelMap(200, 200) { (key, mergedField) ->
                    val fieldName =
                        if (mergedField.arguments.isNotEmpty() && mergedField.resultKey != "__typename") {
                            DocumentRewriterHelper.rewriteFieldName(fieldPrefix, mergedField.singleField)
                        } else {
                            mergedField.resultKey
                        }
                    val oerKey = ObjectEngineResult.Key(fieldName) // TODO: handle aliases,arguments,directives
                    val currentPath = parentPath.segment(oerKey.name)

                    @Suppress("TooGenericExceptionCaught")
                    val oerResult =
                        try {
                            log.debug("fetching field '{}' from OER: {}", oerKey, objectEngineResult)
                            objectEngineResult.fetch(oerKey, RAW_VALUE_SLOT)
                        } catch (e: Exception) {
                            errors.add(ResolutionError(e, currentPath))
                            null
                        }
                    log.debug("fetched field '{}' from OER: {}", oerKey, oerResult)
                    key to
                        if (oerResult is ObjectEngineResult) {
                            val (nestedData, nestedErrors) =
                                resolveObjectEngineResult(
                                    oerResult,
                                    mergedField,
                                    fieldPrefix,
                                    fragmentsByName,
                                    variables,
                                    schema,
                                    currentPath
                                )
                            errors.addAll(nestedErrors)
                            nestedData
                        } else if (oerResult is List<*>) {
                            val (nestedList, nestedErrors) =
                                unwrapListOfObjectEngineResults(
                                    oerResult,
                                    currentPath,
                                    mergedField,
                                    fieldPrefix,
                                    fragmentsByName,
                                    variables,
                                    schema
                                )
                            errors.addAll(nestedErrors)
                            nestedList
                        } else {
                            oerResult
                        }
                }.toList().toMap() to errors.toList()
            }
    }

class ResolutionError(val throwable: Throwable, val path: ResultPath)
