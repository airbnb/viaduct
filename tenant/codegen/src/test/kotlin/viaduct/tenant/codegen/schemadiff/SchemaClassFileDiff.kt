package viaduct.tenant.codegen.schemadiff

import viaduct.tenant.codegen.bytecode.AbstractClassfileDiffTest
import viaduct.tenant.codegen.bytecode.Args

class SchemaClassFileDiff : AbstractClassfileDiffTest(Args.v2_0(schemaResourcePaths = listOf("default_schema.graphqls", "graphql/schema.graphqls")))
