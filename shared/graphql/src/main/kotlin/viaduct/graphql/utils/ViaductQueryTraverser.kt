package viaduct.graphql.utils

import graphql.GraphQLContext
import graphql.analysis.NodeVisitorWithTypeTracking
import graphql.analysis.QueryReducer
import graphql.analysis.QueryTraversalOptions
import graphql.analysis.QueryVisitor
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.execution.CoercedVariables
import graphql.execution.RawVariables
import graphql.execution.ValuesResolver
import graphql.execution.conditional.ConditionalNodes
import graphql.language.DirectivesContainer
import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.Node
import graphql.language.NodeTraverser
import graphql.language.NodeUtil
import graphql.language.OperationDefinition
import graphql.language.SelectionSetContainer
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import java.util.Locale
import kotlin.reflect.jvm.isAccessible

/**
 * This class is a copy of [graphql.analysis.QueryTraverser] with the only difference being that it uses a custom
 * [NodeVisitorWithTypeTracking] that allows us to visit all conditional nodes in a query.
 *
 * Original doc below:
 *
 * Helps to traverse (or reduce) a Document (or parts of it) and tracks at the same time the corresponding Schema types.
 *
 *
 * This is an important distinction to just traversing the Document without any type information: Each field has a clearly
 * defined type. See [QueryVisitorFieldEnvironment].
 *
 *
 * Furthermore are the built in Directives skip/include automatically evaluated: if parts of the Document should be ignored they will not
 * be visited. But this is not a full evaluation of a Query: every fragment will be visited/followed regardless of the type condition.
 *
 *
 * It also doesn't consider field merging, which means for example `{ user{firstName} user{firstName}} ` will result in four
 * visitField calls.
 */
class ViaductQueryTraverser private constructor(
    private val schema: GraphQLSchema,
    private val roots: Collection<Node<*>>,
    private val rootParentType: GraphQLCompositeType,
    private val fragmentsByName: Map<String, FragmentDefinition>,
    private val coercedVariables: CoercedVariables?,
    private val options: QueryTraversalOptions?
) {
    companion object {
        private val queryTraversalContextKlass = Class.forName("graphql.analysis.QueryTraversalContext")
        private val queryTraversalOptions = QueryTraversalOptions.defaultOptions()
        private val queryTraversalContextConstructor =
            queryTraversalContextKlass.getDeclaredConstructor(
                GraphQLOutputType::class.java,
                QueryVisitorFieldEnvironment::class.java,
                SelectionSetContainer::class.java,
                GraphQLContext::class.java
            ).also {
                it.isAccessible = true
            }

        fun fromDocumentWithRawVariables(
            schema: GraphQLSchema,
            document: Document,
            operation: String?,
            rawVariables: RawVariables,
            options: QueryTraversalOptions?
        ): ViaductQueryTraverser {
            val getOperationResult = NodeUtil.getOperation(document, operation)
            val variableDefinitions = getOperationResult.operationDefinition.variableDefinitions
            val coercedVars = ValuesResolver.coerceVariableValues(
                schema,
                variableDefinitions,
                rawVariables,
                GraphQLContext.getDefault(),
                Locale.getDefault()
            )

            return ViaductQueryTraverser(
                schema = schema,
                roots = listOf(getOperationResult.operationDefinition),
                rootParentType = getRootTypeFromOperation(schema, getOperationResult.operationDefinition),
                fragmentsByName = getOperationResult.fragmentsByName,
                coercedVariables = coercedVars,
                options = options
            )
        }

        fun fromDocument(
            schema: GraphQLSchema,
            document: Document,
            operation: String?,
            coercedVariables: CoercedVariables,
            options: QueryTraversalOptions?
        ) = NodeUtil.getOperation(document, operation).let { getOperationResult ->
            ViaductQueryTraverser(
                schema = schema,
                roots = listOf(getOperationResult.operationDefinition),
                rootParentType = getRootTypeFromOperation(schema, getOperationResult.operationDefinition),
                fragmentsByName = getOperationResult.fragmentsByName,
                coercedVariables = coercedVariables,
                options = options
            )
        }

        fun fromRoot(
            schema: GraphQLSchema,
            root: Node<*>,
            rootParentType: GraphQLCompositeType,
            fragmentsByName: Map<String, FragmentDefinition>,
            coercedVariables: CoercedVariables,
            options: QueryTraversalOptions?
        ): ViaductQueryTraverser {
            return ViaductQueryTraverser(
                schema = schema,
                roots = listOf(root),
                rootParentType = rootParentType,
                fragmentsByName = fragmentsByName,
                coercedVariables = coercedVariables,
                options = options
            )
        }

        private fun getRootTypeFromOperation(
            schema: GraphQLSchema,
            operationDefinition: OperationDefinition
        ): GraphQLObjectType {
            return when (operationDefinition.operation!!) {
                OperationDefinition.Operation.MUTATION -> checkNotNull(schema.mutationType)
                OperationDefinition.Operation.QUERY -> checkNotNull(schema.queryType)
                OperationDefinition.Operation.SUBSCRIPTION -> checkNotNull(schema.subscriptionType)
            }
        }

        fun newQueryTraverser(): Builder {
            return Builder()
        }
    }

    fun visitDepthFirst(queryVisitor: QueryVisitor?): Any? {
        return visitImpl(queryVisitor, null)
    }

    /**
     * Visits the Document (or parts of it) in post-order.
     *
     * @param visitor the query visitor that will be called back
     */
    fun visitPostOrder(visitor: QueryVisitor?) {
        visitImpl(visitor, false)
    }

    /**
     * Visits the Document (or parts of it) in pre-order.
     *
     * @param visitor the query visitor that will be called back
     */
    fun visitPreOrder(visitor: QueryVisitor?) {
        visitImpl(visitor, true)
    }

    /**
     * Reduces the fields of a Document (or parts of it) to a single value. The fields are visited in post-order.
     *
     * @param queryReducer the query reducer
     * @param initialValue the initial value to pass to the reducer
     * @param T          the type of reduced value
     *
     * @return the calculated overall value T
     * */

    fun <T> reducePostOrder(
        queryReducer: QueryReducer<T?>,
        initialValue: T?
    ): T? {
        var acc: T? = initialValue
        visitPostOrder(object : QueryVisitorStub() {
            override fun visitField(env: QueryVisitorFieldEnvironment?) {
                acc = queryReducer.reduceField(env, acc)
            }
        })
        return acc
    }

    /**
     * Reduces the fields of a Document (or parts of it) to a single value. The fields are visited in pre-order.
     *
     * @param queryReducer the query reducer
     * @param initialValue the initial value to pass to the reducer
     * @param <T>          the type of reduced value
     *
     * @return the calculated overall value
     </T> */
    fun <T> reducePreOrder(
        queryReducer: QueryReducer<T?>,
        initialValue: T?
    ): T? {
        var acc: T? = initialValue
        visitPreOrder(object : QueryVisitorStub() {
            override fun visitField(env: QueryVisitorFieldEnvironment?) {
                acc = queryReducer.reduceField(env, acc)
            }
        })
        return acc
    }

    private fun childrenOf(node: Node<*>): MutableList<Node<*>?>? {
        if (node !is FragmentSpread) {
            return node.children
        }
        return mutableListOf<Node<*>?>(fragmentsByName[node.name])
    }

    private fun visitImpl(
        visitFieldCallback: QueryVisitor?,
        preOrder: Boolean?
    ): Any? {
        val rootVars: MutableMap<Class<*>?, Any?> = LinkedHashMap()
        val context =
            queryTraversalContextConstructor.newInstance(
                rootParentType,
                null,
                null,
                GraphQLContext.getDefault()
            )
        rootVars[queryTraversalContextKlass] = context
        val preOrderCallback: QueryVisitor?
        val postOrderCallback: QueryVisitor?
        if (preOrder == null) {
            preOrderCallback = visitFieldCallback
            postOrderCallback = visitFieldCallback
        } else {
            val noOp: QueryVisitor = QueryVisitorStub()
            preOrderCallback = if (preOrder) visitFieldCallback else noOp
            postOrderCallback = if (!preOrder) visitFieldCallback else noOp
        }

        val nodeTraverser = NodeTraverser(
            rootVars
        ) { node: Node<*>? ->
            this.childrenOf(
                node!!
            )
        }

        val nodeVisitorWithTypeTracking = ViaductNodeVisitorWithTypeTracking(
            preOrderCallback!!,
            postOrderCallback!!,
            coercedVariables!!.toMap(),
            schema,
            fragmentsByName,
            options ?: queryTraversalOptions
        )
        return nodeTraverser.depthFirst(nodeVisitorWithTypeTracking, roots)
    }

    class Builder {
        private lateinit var schema: GraphQLSchema
        private var document: Document? = null
        private var operation: String? = null
        private var coercedVariables: CoercedVariables = CoercedVariables.emptyVariables()
        private var rawVariables: RawVariables? = null

        private var root: Node<*>? = null
        private var rootParentType: GraphQLCompositeType? = null
        private var fragmentsByName: Map<String, FragmentDefinition> = emptyMap()
        private var options: QueryTraversalOptions? = QueryTraversalOptions.defaultOptions()

        /**
         * The schema used to identify the types of the query.
         *
         * @param schema the schema to use
         *
         * @return this builder
         */
        fun schema(schema: GraphQLSchema) = apply { this.schema = schema }

        /**
         * specify the operation if a document is traversed and there
         * are more than one operation.
         *
         * @param operationName the operation name to use
         *
         * @return this builder
         */
        fun operationName(operationName: String) = apply { this.operation = operationName }

        /**
         * document to be used to traverse the whole query.
         * If set a [Builder.operationName] might be required.
         *
         * @param document the document to use
         *
         * @return this builder
         */
        fun document(document: Document) = apply { this.document = document }

        /**
         * Raw variables used in the query.
         *
         * @param variables the variables to use
         *
         * @return this builder
         */
        fun variables(variables: Map<String, Any>) = apply { this.rawVariables = RawVariables.of(variables) }

        /**
         * Variables (already coerced) used in the query.
         *
         * @param coercedVariables the variables to use
         *
         * @return this builder
         */
        fun coercedVariables(coercedVariables: CoercedVariables) = apply { this.coercedVariables = coercedVariables }

        /**
         * Specify the root node for the traversal. Needs to be provided if there is
         * no [Builder.document].
         *
         * @param root the root node to use
         *
         * @return this builder
         */
        fun root(root: Node<*>) = apply { this.root = root }

        /**
         * The type of the parent of the root node. (See [Builder.root]
         *
         * @param rootParentType the root parent type
         *
         * @return this builder
         */
        fun rootParentType(rootParentType: GraphQLCompositeType) = apply { this.rootParentType = rootParentType }

        /**
         * Fragment by name map. Needs to be provided together with a [Builder.root] and [Builder.rootParentType]
         *
         * @param fragmentsByName the map of fragments
         *
         * @return this builder
         */
        fun fragmentsByName(fragmentsByName: Map<String, FragmentDefinition>) = apply { this.fragmentsByName = fragmentsByName }

        /**
         * Sets the options to use while traversing
         *
         * @param options the options to use
         * @return this builder
         */
        fun options(options: QueryTraversalOptions) = apply { this.options = options }

        /**
         * @return a built [ViaductQueryTraverser] object
         */
        fun build(): ViaductQueryTraverser {
            checkState()
            return when {
                document != null && rawVariables != null ->
                    fromDocumentWithRawVariables(
                        schema,
                        document!!,
                        operation,
                        rawVariables!!,
                        options
                    )

                document != null && rawVariables == null ->
                    fromDocument(
                        schema,
                        document!!,
                        operation,
                        coercedVariables,
                        options
                    )

                rawVariables != null ->
                    fromRoot(
                        schema,
                        root!!,
                        rootParentType!!,
                        fragmentsByName,
                        CoercedVariables.of(rawVariables!!.toMap()),
                        options
                    )

                else -> {
                    fromRoot(
                        schema,
                        root!!,
                        rootParentType!!,
                        fragmentsByName,
                        coercedVariables,
                        options
                    )
                }
            }
        }

        private fun checkState() {
            when {
                document != null || operation != null -> {
                    require(root == null && rootParentType == null && fragmentsByName.isEmpty()) {
                        "ambiguous builder"
                    }
                }
            }
        }
    }
}

class ViaductNodeVisitorWithTypeTracking(
    preOrderCallback: QueryVisitor,
    postOrderCallback: QueryVisitor,
    variables: Map<String, Any?>,
    schema: GraphQLSchema,
    fragmentsByName: Map<String, FragmentDefinition>,
    options: QueryTraversalOptions
) : NodeVisitorWithTypeTracking(preOrderCallback, postOrderCallback, variables, schema, fragmentsByName, options) {
    init {
        val superclass = this::class.java.superclass
        superclass.declaredFields.first { it.name == "conditionalNodes" }.let { field ->
            field.isAccessible = true
            field.set(this, includeAll)
        }
    }

    companion object {
        private val includeAll = object : ConditionalNodes() {
            override fun shouldInclude(
                element: DirectivesContainer<*>,
                variables: MutableMap<String, Any>?,
                schema: GraphQLSchema?,
                ctx: GraphQLContext?
            ): Boolean {
                return true
            }
        }
    }
}
