package viaduct.api.bootstrap.test.grts

import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject

class TestNode : NodeObject {
    object Reflection : Type<TestNode> {
        override val name: String = "TestNode"
        override val kcls = TestNode::class
    }
}

class TestBatchNode : NodeObject {
    object Reflection : Type<TestBatchNode> {
        override val name: String = "TestBatchNode"
        override val kcls = TestBatchNode::class
    }
}
