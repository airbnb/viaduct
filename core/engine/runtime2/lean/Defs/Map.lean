import Std.Data.ExtTreeSet

set_option autoImplicit false

-- Universes let keys and values belong to types of different sizes.
universe u v

namespace Defs

/-- A map contains a finite set of keys and a value for every stored key.
The subtype pairs a key with proof of membership. The proof concerns only
keys, so the values can belong to a recursively defined type. Ord supplies
key comparison; TransOrd states that this comparison is transitive. -/
structure Map (Key : Type u) (Value : Type v)
    [Ord Key] [Std.TransOrd Key] where
  keys : Std.ExtTreeSet Key
  valueAt : {key : Key // key ∈ keys} → Value

/-- Optional lookup accepts any key. In the present branch, Lean supplies the
membership proof needed by valueAt. None means absent; when Value is itself
optional, some none represents a present null rather than an absent field. -/
def Map.get {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key]
    (map : Map Key Value) (key : Key) : Option Value :=
  if present : key ∈ map.keys then
    some (map.valueAt ⟨key, present⟩)
  else none

/-- Key membership and successful lookup describe exactly the same domain.
This is a theorem about every map, without storing another invariant field. -/
theorem Map.mem_keys_iff_get_ne_none {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key]
    (map : Map Key Value) (key : Key) :
    key ∈ map.keys ↔ map.get key ≠ none := by
  by_cases present : key ∈ map.keys <;>
    simp [Map.get, present]

/-- Maps with the same lookup results are equal using ordinary Lean equality.
Set extensionality identifies their keys; function extensionality identifies
their stored values. LawfulEqOrd connects comparison equality to ordinary =. -/
@[ext] theorem Map.ext {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key] [Std.LawfulEqOrd Key]
    (left right : Map Key Value)
    (same : ∀ key, left.get key = right.get key) :
    left = right := by
  cases left with
  | mk leftKeys leftValues =>
    cases right with
    | mk rightKeys rightValues =>
      have sameKeys : leftKeys = rightKeys := by
        apply Std.ExtTreeSet.ext_mem
        intro key
        have equality := same key
        by_cases leftPresent : key ∈ leftKeys <;>
          by_cases rightPresent : key ∈ rightKeys <;>
          simp_all [Map.get]
      cases sameKeys
      have sameValues : leftValues = rightValues := by
        funext key
        simpa [Map.get, key.property] using same key.val
      cases sameValues
      rfl

/-- Build a map from distinct key/value pairs. The uniqueness proof preserves
the construction contract: conflicting duplicate entries are rejected.
The set records the keys; the function retrieves values from the pair list.
Membership proves that retrieval succeeds, allowing Option.get to unwrap it. -/
def Map.ofList {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key]
    [Std.LawfulEqOrd Key] [DecidableEq Key]
    (pairs : List (Key × Value))
    (_uniqueKeys : (pairs.map Prod.fst).Nodup) :
    Map Key Value :=
  { keys := .ofList (pairs.map Prod.fst)
    valueAt := fun key =>
      (pairs.lookup key.val).get (by
        apply List.lookup_isSome_iff.mpr
        simpa [Std.ExtTreeSet.mem_ofList] using key.property) }

/-- Print the finite entries in key order. Enumerating the keys avoids trying
to print a function directly and keeps recursive values inspectable. -/
instance {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key]
    [Repr Key] [Repr Value] : Repr (Map Key Value) where
  reprPrec map prec := Repr.addAppParen
    ("Defs.Map.ofList " ++ repr (map.keys.toList.filterMap fun key =>
      (map.get key).map fun value => (key, value))) prec

/-- Enumerate entries in ascending key order. -/
def Map.entries {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key] [Std.LawfulEqOrd Key]
    (map : Map Key Value) : List (Key × Value) :=
  map.keys.toList.attach.map fun key =>
    (key.val, map.valueAt ⟨key.val, Std.ExtTreeSet.mem_toList.mp key.property⟩)

/-- An enumerated entry records exactly a successful lookup. -/
theorem Map.mem_entries {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key] [Std.LawfulEqOrd Key]
    (map : Map Key Value) (key : Key) (value : Value) :
    (key, value) ∈ map.entries ↔ map.get key = some value := by
  constructor
  · intro h
    obtain ⟨k, _, same⟩ := List.mem_map.mp (show _ ∈ List.map _ _ from h)
    cases same
    simp [Map.get, Std.ExtTreeSet.mem_toList.mp k.property]
  · intro h
    unfold Map.get at h
    split at h
    · rename_i present
      have inList := Std.ExtTreeSet.mem_toList.mpr present
      change (key, value) ∈ List.map _ map.keys.toList.attach
      apply List.mem_map.mpr
      refine ⟨⟨key, inList⟩, by simp, ?_⟩
      simp only [Option.some.injEq] at h
      exact congrArg (fun v => (key, v)) h
    · cases h

/-- The sorted entry list uniquely determines the map. -/
theorem Map.entries_injective {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key] [Std.LawfulEqOrd Key]
    {left right : Map Key Value} (same : left.entries = right.entries) : left = right := by
  apply Map.ext
  intro key
  have lookup : ∀ value, left.get key = some value ↔ right.get key = some value := by
    intro value
    rw [← Map.mem_entries, ← Map.mem_entries, same]
  cases hleft : left.get key with
  | some value => exact (lookup value).mp hleft |>.symm
  | none =>
    cases hright : right.get key with
    | none => rfl
    | some value =>
      have := (lookup value).mpr hright
      simp [hleft] at this

-- Products compare by key first, then value, within this module.
attribute [local instance] lexOrd

/-- Compare canonical entry lists; values need an order only for this operation. -/
instance {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key] [Std.LawfulEqOrd Key]
    [Ord Value] : Ord (Map Key Value) where
  compare := compareOn Map.entries

/-- Lexicographic map comparison inherits transitivity from keys and values. -/
instance {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key] [Std.LawfulEqOrd Key]
    [Ord Value] [Std.TransOrd Value] : Std.TransOrd (Map Key Value) :=
  inferInstanceAs (Std.TransCmp (compareOn (Map.entries (Key := Key) (Value := Value))))

/-- Comparison equality agrees with ordinary, extensional map equality. -/
instance {Key : Type u} {Value : Type v} [Ord Key] [Std.TransOrd Key]
    [Std.LawfulEqOrd Key] [Ord Value] [Std.LawfulEqOrd Value] :
    Std.LawfulEqOrd (Map Key Value) where
  compare_self := by
    intro a
    exact Std.ReflCmp.compare_self (cmp := compare (α := List (Key × Value)))
  eq_of_compare h := Map.entries_injective (Std.LawfulEqOrd.eq_of_compare h)

end Defs
