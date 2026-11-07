import Defs.Map
import Model.SimpleData

set_option autoImplicit false

namespace Model

/-- An error carries an identity token. Different tokens represent different
errors; this model does not describe error messages or propagation yet. -/
structure EngineErrorData where
  identity : Nat
  deriving Repr, DecidableEq

-- Output data and output objects refer to each other, so mutual declares them
-- together. Output lists are deliberately absent, including inside objects.
mutual

  /-- The output data domain contains shared simple data, typed output objects,
  and errors. Each constructor embeds its payload; no domain-membership proof is needed. -/
  inductive EngineOutputData where
    | simple (data : EngineSimpleData)
    | object (data : EngineObjectData)
    | error (data : EngineErrorData)
    deriving Repr

  /-- An EOD object records its concrete type and uniquely named nullable output
  fields. Those fields can recursively contain other typed output objects. -/
  structure EngineObjectData where
    typeName : String
    fields : Defs.Map String (Option EngineOutputData)
    deriving Repr

end

end Model
