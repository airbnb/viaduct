import Defs.Map
import Defs.Order
import Model.SimpleData

set_option autoImplicit false

namespace Model

/-- The input data domain contains simple data, lists, and input objects. It is
recursive: list elements and object fields contain input data again. Option
permits null children. Errors and output objects have no input constructors. -/
inductive EngineInputData where
  | simple (data : EngineSimpleData)
  | list (items : List (Option EngineInputData))
  | object (fields : Defs.Map String (Option EngineInputData))
  deriving Repr

/-- An input list contains nullable input data. This alias adds no constructor. -/
abbrev EngineInputListData := List (Option EngineInputData)

/-- An input object maps distinct names to nullable input data. It carries no
concrete GraphQL object type name, unlike an output object. -/
abbrev EngineInputObjectData := Defs.Map String (Option EngineInputData)

/-- Compare sorted field entries by name, data, and remaining entries. -/
private def compareEntries {Data : Type} :
    List (String × (Data → Ordering)) → List (String × Data) → Ordering
  | [], [] => .eq
  | [], _ :: _ => .lt
  | _ :: _, [] => .gt
  | (leftKey, compareData) :: leftRest, (rightKey, rightData) :: rightRest =>
      (compare leftKey rightKey).then
        ((compareData rightData).then (compareEntries leftRest rightRest))

mutual
  /-- Compare constructor tags, then the corresponding payloads. -/
  private def compareInput (left right : EngineInputData) : Ordering :=
    match left, right with
    | .simple a, .simple b => compare a b
    | .simple _, _ => .lt
    | _, .simple _ => .gt
    | .list a, .list b => compareInputList a b
    | .list _, .object _ => .lt
    | .object _, .list _ => .gt
    | .object a, .object b => compareInputMap a b
  termination_by structural left

  /-- Compare nullable list elements lexicographically. -/
  private def compareInputList (left right : List (Option EngineInputData)) : Ordering :=
    match left, right with
    | [], [] => .eq
    | [], _ :: _ => .lt
    | _ :: _, [] => .gt
    | a :: restA, b :: restB =>
        (compareNullable a b).then (compareInputList restA restB)
  termination_by structural left

  /-- Compare objects through their entries in ascending field-name order.
  Partially applying comparison to each left datum exposes structural recursion
  through `Defs.Map.valueAt` to Lean; `compareInputMap_eq` proves the usual
  lexicographic behavior. -/
  private def compareInputMap (left right : Defs.Map String (Option EngineInputData)) : Ordering :=
    match left with
    | ⟨keys, dataAt⟩ =>
      let leftEntries := keys.toList.attach.map (fun key =>
        (key.val, compareNullable (dataAt ⟨key.val, Std.ExtTreeSet.mem_toList.mp key.property⟩)))
      let rightEntries := right.keys.toList.attach.map (fun key =>
        (key.val, right.valueAt ⟨key.val, Std.ExtTreeSet.mem_toList.mp key.property⟩))
      compareEntries leftEntries rightEntries
  termination_by structural left

  /-- Order null before every nonnull input datum. -/
  private def compareNullable (left right : Option EngineInputData) : Ordering :=
    match left, right with
    | none, none => .eq
    | none, some _ => .lt
    | some _, none => .gt
    | some a, some b => compareInput a b
  termination_by structural left
end

/-- The recursive list implementation is ordinary lexicographic comparison. -/
private theorem compareInputList_eq :
    compareInputList = List.compareLex compareNullable := by
  funext left right
  induction left generalizing right with
  | nil => cases right <;> rfl
  | cons head tail ih =>
    cases right with
    | nil => rfl
    | cons other rest => simp only [compareInputList, List.compareLex_cons_cons, ih]

/-- Partially applying comparison to left data preserves lexicographic entry comparison. -/
private theorem compareEntries_eq {Data : Type} (cmp : Data → Data → Ordering)
    (left right : List (String × Data)) :
    compareEntries (left.map fun entry => (entry.1, cmp entry.2)) right =
      List.compareLex (Defs.pair compare cmp) left right := by
  induction left generalizing right with
  | nil => cases right <;> rfl
  | cons entry rest ih =>
    cases right with
    | nil => rfl
    | cons other tail =>
      simp only [List.map_cons, compareEntries, List.compareLex_cons_cons,
        Defs.pair, ih, Ordering.then_assoc]

/-- Object comparison uses exactly the canonical, sorted map entry lists. -/
private theorem compareInputMap_eq (left right : EngineInputObjectData) :
    compareInputMap left right =
      List.compareLex (Defs.pair compare compareNullable) left.entries right.entries := by
  cases left with
  | mk keys dataAt =>
    simpa only [compareInputMap, Defs.Map.entries, List.map_map, Function.comp_def] using
      compareEntries_eq compareNullable
        (Defs.Map.entries (Defs.Map.mk keys dataAt)) right.entries

/-- Lift the scalar comparison laws through the input constructor. -/
private theorem lawfulSimple (data : EngineSimpleData) : Defs.At compareInput (.simple data) := by
  have good := Defs.At.ofOrd data
  constructor
  · exact good.refl
  · intro other same
    cases other with
    | simple other => exact congrArg EngineInputData.simple (good.eq other same)
    | list _ => cases same
    | object _ => cases same
  · intro other
    cases other <;> first | rfl | exact good.swap _
  · intro other third h₁ h₂
    cases other <;> cases third <;> simp only [compareInput] at *
    all_goals first | contradiction | trivial | exact good.trans _ _ h₁ h₂

/-- Lift nullable-list comparison laws through the input constructor. -/
private theorem lawfulList (items : EngineInputListData) (good : Defs.At compareInputList items) :
    Defs.At compareInput (.list items) := by
  constructor
  · exact good.refl
  · intro other same
    cases other with
    | list other => exact congrArg EngineInputData.list (good.eq other same)
    | simple _ => cases same
    | object _ => cases same
  · intro other
    cases other <;> first | rfl | exact good.swap _
  · intro other third h₁ h₂
    cases other <;> cases third <;> simp only [compareInput] at *
    all_goals first | contradiction | trivial | exact good.trans _ _ h₁ h₂

/-- Lift object comparison laws through the input constructor. -/
private theorem lawfulObject (fields : EngineInputObjectData) (good : Defs.At compareInputMap fields) :
    Defs.At compareInput (.object fields) := by
  constructor
  · exact good.refl
  · intro other same
    cases other with
    | object other => exact congrArg EngineInputData.object (good.eq other same)
    | simple _ => cases same
    | list _ => cases same
  · intro other
    cases other <;> first | rfl | exact good.swap _
  · intro other third h₁ h₂
    cases other <;> cases third <;> simp only [compareInput] at *
    all_goals first | contradiction | trivial | exact good.trans _ _ h₁ h₂

/-- Null is the least nullable input datum and compares equal only to itself. -/
private theorem lawfulNone : Defs.At compareNullable none where
  refl := rfl
  eq other same := by cases other <;> first | rfl | cases same
  swap other := by cases other <;> rfl
  trans other third _ _ := by cases third <;> trivial

/-- Lift the recursive input laws through the nonnull constructor. -/
private theorem lawfulSome (data : EngineInputData) (good : Defs.At compareInput data) :
    Defs.At compareNullable (some data) := by
  constructor
  · exact good.refl
  · intro other same
    cases other with
    | none => cases same
    | some other => exact congrArg some (good.eq other same)
  · intro other
    cases other <;> first | rfl | exact good.swap _
  · intro other third h₁ h₂
    cases other <;> cases third <;> simp only [compareNullable] at *
    all_goals first | contradiction | trivial | exact good.trans _ _ h₁ h₂

/-- Sorted object entries inherit laws from the comparison of each stored datum. -/
private theorem lawfulMap (keys : Std.ExtTreeSet String)
    (dataAt : {key // key ∈ keys} → Option EngineInputData)
    (good : ∀ key, Defs.At compareNullable (dataAt key)) :
    Defs.At compareInputMap (Defs.Map.mk keys dataAt) := by
  let fields := Defs.Map.mk keys dataAt
  have entriesGood : Defs.At (List.compareLex (Defs.pair compare compareNullable))
      fields.entries := by
    apply Defs.At.list
    intro entry member
    obtain ⟨key, _, same⟩ := List.mem_map.mp (show _ ∈ List.map _ _ from member)
    cases same
    exact (Defs.At.ofOrd key.val).pair (good _)
  constructor
  · simpa only [compareInputMap_eq] using entriesGood.refl
  · intro other same
    apply Defs.Map.entries_injective
    exact entriesGood.eq _ (by simpa only [compareInputMap_eq] using same)
  · intro other
    simpa only [compareInputMap_eq] using entriesGood.swap other.entries
  · intro other third h₁ h₂
    simp only [compareInputMap_eq] at *
    exact entriesGood.trans other.entries third.entries h₁ h₂

/-- Structural induction proves all comparison laws for every finite input datum. -/
private theorem lawfulInput (data : EngineInputData) : Defs.At compareInput data := by
  apply EngineInputData.rec
    (motive_1 := Defs.At compareInput)
    (motive_2 := fun items => ∀ data ∈ items, Defs.At compareNullable data)
    (motive_3 := Defs.At compareInputMap)
    (motive_4 := Defs.At compareNullable)
    lawfulSimple
    (fun items good => lawfulList items (by rw [compareInputList_eq]; exact Defs.At.list items good))
    lawfulObject
    (by simp)
    (by
      intro head tail headGood tailGood data member
      rcases List.mem_cons.mp member with same | member
      · subst data; exact headGood
      · exact tailGood data member)
    lawfulMap
    lawfulNone
    lawfulSome

/-- Order input data structurally: simple data, then lists, then objects. -/
instance : Ord EngineInputData where
  compare := compareInput

/-- Input comparison is oriented and transitive, including through nested maps. -/
instance : Std.TransOrd EngineInputData where
  eq_swap {a b} := (lawfulInput a).swap b
  isLE_trans {a b c} := (lawfulInput a).trans b c

/-- Two inputs compare equal exactly when they are equal as Lean terms. -/
instance : Std.LawfulEqOrd EngineInputData where
  compare_self {a} := (lawfulInput a).refl
  eq_of_compare {a b} := (lawfulInput a).eq b

/-- Decide ordinary input equality using the proved lawful comparison. -/
instance : DecidableEq EngineInputData := fun left right =>
  decidable_of_iff (compare left right = .eq) Std.LawfulEqOrd.compare_eq_iff_eq

end Model
