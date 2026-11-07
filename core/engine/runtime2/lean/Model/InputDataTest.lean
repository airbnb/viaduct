import Model.InputData

set_option autoImplicit false

namespace Model

/-- This input object exercises both scalar cases, null, and nested input lists.
The tactic decide checks the concrete proposition that its keys are distinct. -/
private def inputFields : EngineInputObjectData :=
  Defs.Map.ofList
    [("optional", none),
     ("count", some (.simple (.integer (-7)))),
     ("items", some (.list
       [some (.simple (.string "hello")),
        some (.list [none])]))]
    (by decide)

-- A missing field must differ observably from a field explicitly containing null.
#guard match inputFields.get "absent" with
  | none => true
  | _ => false

-- A present null still occupies the map.
#guard match inputFields.get "optional" with
  | some none => true
  | _ => false

-- Negative integers survive storage and lookup without conversion.
#guard match inputFields.get "count" with
  | some (some (.simple (.integer (-7)))) => true
  | _ => false

-- Input objects can contain lists whose elements include strings and more lists.
#guard match inputFields.get "items" with
  | some (some (.list
      [some (.simple (.string "hello")),
       some (.list [none])])) => true
  | _ => false

/-- The same nested object as inputFields, with its field construction order reversed. -/
private def reorderedInputFields : EngineInputObjectData :=
  Defs.Map.ofList
    [("items", some (.list
       [some (.simple (.string "hello")), some (.list [none])])),
     ("count", some (.simple (.integer (-7)))),
     ("optional", none)]
    (by decide)

/-- Wrap the representative nested object as input data. -/
private def inputObject : EngineInputData := .object inputFields

/-- Wrap the object built in reverse field order as input data. -/
private def reorderedInputObject : EngineInputData := .object reorderedInputFields

/-- An object with a different nested list element must remain a distinct key. -/
private def changedInputObject : EngineInputData :=
  .object (Defs.Map.ofList
    [("optional", none),
     ("count", some (.simple (.integer (-7)))),
     ("items", some (.list
       [some (.simple (.string "hello")), some (.list [])]))]
    (by decide))

-- The input constructor order does not depend on the contained payloads.
#guard compare (EngineInputData.simple (.string "z")) (.list []) == .lt
#guard compare (EngineInputData.list [some inputObject]) inputObject == .lt
#guard compare inputObject (EngineInputData.simple (.integer 0)) == .gt

-- Lists compare by their first differing element; an equal prefix sorts first.
#guard compare (EngineInputData.list []) (.list [none]) == .lt
#guard compare (EngineInputData.list [none]) (.list [some (.simple (.integer (-999)))]) == .lt
#guard compare (EngineInputData.list [some (.simple (.integer 1)), none])
  (.list [some (.simple (.integer 2))]) == .lt
#guard compare (EngineInputData.list [some (.simple (.integer 1))])
  (.list [some (.simple (.integer 1)), none]) == .lt

-- Object construction order has no effect on comparison or ordinary equality.
#guard compare inputObject reorderedInputObject == .eq
example : inputObject = reorderedInputObject := by decide

-- Recursive comparison descends through object fields and their nested lists.
#guard compare changedInputObject inputObject == .lt
#guard compare inputObject changedInputObject == .gt
#guard compare (EngineInputData.list [some inputObject])
  (.list [some reorderedInputObject]) == .eq
#guard compare
  (EngineInputData.object (Defs.Map.ofList [("child", some changedInputObject)] (by decide)))
  (.object (Defs.Map.ofList [("child", some inputObject)] (by decide))) == .lt

-- Object entries compare by field name before data, then by remaining entries.
#guard compare
  (EngineInputData.object (Defs.Map.ofList [("a", some (.simple (.integer 999)))] (by decide)))
  (.object (Defs.Map.ofList [("b", none)] (by decide))) == .lt
#guard compare
  (EngineInputData.object (Defs.Map.ofList [("a", some (.simple (.integer 1)))] (by decide)))
  (.object (Defs.Map.ofList [("a", some (.simple (.integer 2)))] (by decide))) == .lt
#guard compare
  (EngineInputData.object (Defs.Map.ofList [("a", none)] (by decide)))
  (.object (Defs.Map.ofList [("a", none), ("b", none)] (by decide))) == .lt

-- An absent field, a present null, and a present nonnull datum stay distinct.
#guard compare (EngineInputData.object (Defs.Map.ofList [] (by decide)))
  (.object (Defs.Map.ofList [("a", none)] (by decide))) == .lt
#guard compare (EngineInputData.object (Defs.Map.ofList [("a", none)] (by decide)))
  (.object (Defs.Map.ofList [("a", some (.list []))] (by decide))) == .lt

/-- Input data can now serve as finite-set keys, including nested objects and duplicates. -/
private def inputKeys : Std.ExtTreeSet EngineInputData :=
  .ofList [inputObject, changedInputObject, reorderedInputObject, .list []]

-- Equivalent objects collapse to one key; changed nested data remains distinct.
#guard inputKeys.size == 3
#guard inputKeys.contains reorderedInputObject
#guard !inputKeys.contains (.simple (.integer 42))
#guard inputKeys.toList = [.list [], changedInputObject, inputObject]

-- Extensional set equality uses the proved order laws for arbitrary inputs.
example : inputKeys = Std.ExtTreeSet.ofList [.list [], changedInputObject, inputObject] := by
  apply Std.ExtTreeSet.ext_mem
  intro key
  simp only [inputKeys, Std.ExtTreeSet.mem_ofList]
  have same : inputObject = reorderedInputObject := by decide
  rw [← same]
  grind

/-- Input data can also key maps, as argument-bearing runtime keys eventually will. -/
private def inputKeyMap : Defs.Map EngineInputData String :=
  Defs.Map.ofList [(inputObject, "object"), (.list [], "list")] (by decide)

-- Map lookup recognizes an equivalent object constructed in another field order.
#guard inputKeyMap.get reorderedInputObject == some "object"
#guard inputKeyMap.get (.list []) == some "list"
#guard inputKeyMap.get changedInputObject == none

-- Input-object maps inherit the same lawful order for use as set keys themselves.
#guard (Std.ExtTreeSet.ofList [inputFields, reorderedInputFields]).size == 1

-- The law instances apply to all inputs, beyond the concrete examples above.
example (left right : EngineInputData) : compare left right = .eq ↔ left = right :=
  Std.LawfulEqOrd.compare_eq_iff_eq

example (a b c : EngineInputData)
    (ab : (compare a b).isLE) (bc : (compare b c).isLE) : (compare a c).isLE :=
  Std.TransOrd.isLE_trans ab bc

end Model
