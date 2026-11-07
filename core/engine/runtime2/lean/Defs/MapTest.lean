import Defs.Map

set_option autoImplicit false

universe u v

namespace Defs

/-- Converting distinct pairs into a map preserves every association-list lookup,
including missing keys. This checks construction and get for arbitrary types. -/
private theorem get_ofList {Key : Type u} {Value : Type v}
    [Ord Key] [Std.TransOrd Key]
    [Std.LawfulEqOrd Key] [DecidableEq Key]
    (pairs : List (Key × Value))
    (uniqueKeys : (pairs.map Prod.fst).Nodup)
    (key : Key) :
    (Map.ofList pairs uniqueKeys).get key =
      List.lookup key pairs := by
  unfold Map.get Map.ofList
  split
  · exact Option.some_get _
  · rename_i absent
    symm
    apply List.lookup_eq_none_iff.mpr
    intro pair inPairs
    apply bne_iff_ne.mpr
    intro same
    apply absent
    have present : key ∈ pairs.map Prod.fst :=
      List.mem_map.mpr ⟨pair, inPairs, same.symm⟩
    simpa [Std.ExtTreeSet.mem_ofList] using present

/-- Natural-number keys and Boolean values demonstrate that neither strings nor
nullable values are built into the map abstraction. -/
private def switches : Map Nat Bool :=
  Map.ofList [(2, false), (7, true)] (by decide)

-- The same entries in opposite orders produce equal maps using ordinary =.
example : switches = Map.ofList [(7, true), (2, false)] (by decide) := by
  apply Map.ext
  intro key
  simp only [switches, get_ofList]
  by_cases first : key = 2
  · subst key
    rfl
  · by_cases second : key = 7
    · subst key
      rfl
    · have firstFalse : (key == 2) = false :=
        beq_eq_false_iff_ne.mpr first
      have secondFalse : (key == 7) = false :=
        beq_eq_false_iff_ne.mpr second
      simp [List.lookup, firstFalse, secondFalse]

-- Lookup must find the second entry after skipping an unrelated first entry.
#guard switches.get 7 == some true

-- A missing key remains distinguishable from a stored Boolean false value.
#guard switches.get 2 == some false

-- A lookup for an absent key produces no value.
#guard switches.get 9 == none

-- Empty maps have no value for any requested key.
#guard (Map.ofList [] (by decide) : Map Nat Bool).get 2 == none

-- A duplicate key cannot satisfy the map constructor's uniqueness requirement.
#guard_msgs (drop info) in
#check_failure (Map.ofList [(2, false), (2, true)] (by decide))

-- Canonical entries are sorted even when the construction list is reversed.
#guard (Map.ofList [(7, true), (2, false)] (by decide) : Map Nat Bool).entries ==
  [(2, false), (7, true)]

-- Map comparison ignores insertion order and compares matching keys by value.
#guard compare switches (Map.ofList [(7, true), (2, false)] (by decide)) == .eq
#guard compare switches (Map.ofList [(2, true), (7, true)] (by decide)) == .lt
#guard compare switches (Map.ofList [(3, false)] (by decide)) == .lt
#guard compare (Map.ofList [(2, false)] (by decide)) switches == .lt

-- Ordered maps themselves can be keys in an extensional finite set.
#guard (Std.ExtTreeSet.ofList
  [switches, Map.ofList [(7, true), (2, false)] (by decide)]).size == 1

-- Comparison equality agrees with ordinary equality for arbitrary ordered types.
example {Key : Type u} {Value : Type v} [Ord Key] [Std.TransOrd Key]
    [Std.LawfulEqOrd Key] [Ord Value] [Std.LawfulEqOrd Value]
    (left right : Map Key Value) : compare left right = .eq ↔ left = right :=
  Std.LawfulEqOrd.compare_eq_iff_eq

end Defs
