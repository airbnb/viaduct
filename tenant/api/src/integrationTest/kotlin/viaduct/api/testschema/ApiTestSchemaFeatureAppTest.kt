
package viaduct.api.testschema

class ApiTestSchemaFeatureAppTest {
    val sdl = """
#START_SCHEMA
    | extend type Query {
    |   foo: String
    | }
    | type O1 implements Node {
    |   id: ID!
    |   stringField: String
    |   listField: [[O2]]
    |   objectField: O2
    |   enumField: E1
    |   interfaceField: I0
    |   listFieldNonNullBaseType: [[O2!]!]
    |   backingDataList: [BackingData] @backingData(class: "java.lang.Integer")
    | }
    | type O2 implements Node {
    |   id: ID!
    |   intField: Int!
    |   objectField: O1
    |   dateTimeField: DateTime
    |   argumentedField(
    |     stringArg: String!,
    |     intArgWithDefault: Int = 1,
    |     inputArg: Input1,
    |     idArg: ID @idOf(type: "O1")
    |   ): String
    |   backingDataField: BackingData @backingData(class: "java.lang.String")
    |   invalidBackingData: BackingData
    | }
    | enum E1 {
    |   A
    |   B
    | }
    | interface I0 {
    |   commonField: String
    | }
    | type I1 implements I0 {
    |   commonField: String
    | }
    | input Input1 {
    |   enumFieldWithDefault: E1 = A
    |   nonNullEnumFieldWithDefault: E1! = A
    |   stringField: String
    |   intField: Int
    |   nonNullStringField: String!
    |   listField: [E1!]
    |   nestedListField: [[E1!]]
    |   inputField: Input2
    | }
    | input Input2 {
    |   stringField: String
    |   id1: ID
    |   id2: ID @idOf(type: "TestUser")
    |   dateTimeField: DateTime
    | }
    | input Input3 {
    |   inputField: Input2 = { stringField: "defaultStringField" }
    | }
    |
    | input InputWithGlobalIDs {
    |   id: ID!
    |   id2: ID! @idOf(type: "O1")
    |   id3: [ID] @idOf(type: "O1")
    |   ids: [[ID]!] @idOf(type: "O2")
    | }

    | type ObjectWithGlobalIds {
    |   # this field is named "id" so that it looks like a Node.id field,
    |   # though the containing type intentionally does not implement Node.
    |   id: ID!
    |
    |   id1: ID @idOf(type: "TestUser")
    |   id2: ID! @idOf(type: "TestUser")
    |   id4: [ID] @idOf(type: "TestUser")
    |   id5: [[ID!]] @idOf(type: "TestUser")
    |
    |   id6: ID
    |   id7: ID!
    |   id8: [ID]
    |   id9: [[ID!]]
    | }
    |
    | type TestUser implements Node {
    |   id: ID!
    |   id2: ID!
    |   id3: [ID] @idOf(type: "TestUser")
    |   id4: [ID]
    | }
    |
    | type TestType {
    |   id: ID!
    | }
    |
    | union U1 = I1 | TestType
    |
    | type Scalars {
    |   boolean: Boolean
    |   byte: Byte
    |   short: Short
    |   int: Int
    |   long: Long
    |   float: Float
    |   json: JSON
    |   string: String
    |   id: ID
    |   backingData: BackingData
    |   date: Date
    |   dateTime: DateTime
    |   time: Time
    | }
    |
    | type HasAbstractField { u2: U2 }
    | union U2 = Concrete
    | type Concrete { x:Int }
    |
    | type RecursiveObject {
    |    int: Int
    |    nested: RecursiveObject
    | }
    |
#END_SCHEMA
    """
}
