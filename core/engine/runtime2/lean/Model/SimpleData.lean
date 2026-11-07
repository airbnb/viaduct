import Std

set_option autoImplicit false

namespace Model

/-- The simple data domain contains integers and strings. The constructors tag
their payloads, so integers and strings remain distinguishable. Input and
output data share these tagged scalar payloads. -/
inductive EngineSimpleData where
  | integer (data : Int)
  | string (data : String)
  deriving Repr, DecidableEq

/-- Order integers before strings, then compare their payloads. -/
def EngineSimpleData.compare : EngineSimpleData → EngineSimpleData → Ordering
  | .integer a, .integer b => Ord.compare a b
  | .integer _, .string _ => .lt
  | .string _, .integer _ => .gt
  | .string a, .string b => Ord.compare a b

/-- Expose comparison of simple data through Lean's ordering interface. -/
instance : Ord EngineSimpleData := ⟨EngineSimpleData.compare⟩

/-- Comparison of simple data is oriented and transitive. -/
instance : Std.TransOrd EngineSimpleData where
  eq_swap := by
    intro a b
    cases a <;> cases b <;>
      simp only [show compare (α := EngineSimpleData) = EngineSimpleData.compare from rfl,
        EngineSimpleData.compare]
    all_goals first | rfl | exact Std.OrientedOrd.eq_swap
  isLE_trans := by
    intro a b c hab hbc
    cases a <;> cases b <;> cases c <;>
      simp only [show compare (α := EngineSimpleData) = EngineSimpleData.compare from rfl,
        EngineSimpleData.compare] at *
    all_goals first | contradiction | trivial | exact Std.TransOrd.isLE_trans hab hbc

/-- Comparing simple data as equal agrees with ordinary equality. -/
instance : Std.LawfulEqOrd EngineSimpleData where
  compare_self := by
    intro a
    cases a <;> exact Std.ReflCmp.compare_self
  eq_of_compare := by
    intro a b h
    cases a <;> cases b <;>
      simp only [show compare (α := EngineSimpleData) = EngineSimpleData.compare from rfl,
        EngineSimpleData.compare] at h
    all_goals first | contradiction | (congr 1; exact Std.LawfulEqOrd.eq_of_compare h)

end Model
