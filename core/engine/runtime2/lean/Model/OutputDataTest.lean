import Model.InputData
import Model.OutputData

set_option autoImplicit false

namespace Model

/-- This EOD contains another typed EOD, whose own field contains an error. -/
private def outputObject : EngineObjectData :=
  ⟨"User", Defs.Map.ofList
    [("name", some (.simple (.string "Ada"))),
     ("friend", some (.object
       ⟨"User", Defs.Map.ofList
         [("failed", some (.error ⟨3⟩))]
         (by decide)⟩))]
    (by decide)⟩

/-- A minimal input object exercises rejection of input data in output positions. -/
private def inputFields : EngineInputObjectData :=
  Defs.Map.ofList [("items", some (.list []))] (by decide)

-- For every simple datum, extracting its output payload and wrapping it as
-- input preserves the original payload. rfl proves this by reducing definitions.
example (data : EngineSimpleData) :
    (match EngineOutputData.simple data with
     | .simple copied => some (EngineInputData.simple copied)
     | _ => none) = some (EngineInputData.simple data) :=
  rfl

-- Output object lookup preserves a scalar payload and the concrete object type.
#guard outputObject.typeName == "User" &&
  match outputObject.fields.get "name" with
  | some (some (.simple (.string "Ada"))) => true
  | _ => false

-- Typed output objects can recursively contain objects with identifiable errors.
#guard match outputObject.fields.get "friend" with
  | some (some (.object friend)) =>
      friend.typeName == "User" &&
      match friend.fields.get "failed" with
      | some (some (.error error)) => error.identity == 3
      | _ => false
  | _ => false

-- Different error tokens remain distinguishable after embedding in output data.
example : EngineOutputData.error ⟨3⟩ ≠
    EngineOutputData.error ⟨4⟩ := by
  intro equal
  cases equal

-- check_failure succeeds only when Lean rejects the expression. Output lists
-- have no constructor, at any depth in the recursive output domain.
#guard_msgs (drop info) in
#check_failure (EngineOutputData.list [])

-- A resolver error cannot be smuggled into an input list.
#guard_msgs (drop info) in
#check_failure (EngineInputData.list [some (EngineOutputData.error ⟨3⟩)])

-- An EOD object cannot be used as an input-object map.
#guard_msgs (drop info) in
#check_failure (EngineInputData.object outputObject)

-- An input map cannot be used as a typed output object.
#guard_msgs (drop info) in
#check_failure (EngineOutputData.object inputFields)

-- Even a list already represented as input data cannot inhabit an output field.
#guard_msgs (drop info) in
#check_failure (EngineObjectData.mk "User"
  (Defs.Map.ofList [("items", some (EngineInputData.list []))]
    (by decide)))

end Model
