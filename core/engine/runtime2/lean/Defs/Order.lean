import Std

set_option autoImplicit false

universe u v

namespace Defs

/-- The comparison laws at one left operand, used to prove laws by structural induction. -/
structure At {α : Type u} (cmp : α → α → Ordering) (a : α) : Prop where
  refl : cmp a a = .eq
  eq : ∀ b, cmp a b = .eq → a = b
  swap : ∀ b, cmp a b = (cmp b a).swap
  trans : ∀ b c, (cmp a b).isLE → (cmp b c).isLE → (cmp a c).isLE

variable {α : Type u} {β : Type v} {cmp : α → α → Ordering} {a : α}

/-- Existing lawful orders supply the induction base cases. -/
theorem At.ofOrd [Ord α] [Std.TransOrd α] [Std.LawfulEqOrd α] (a : α) :
    At compare a :=
  ⟨Std.ReflCmp.compare_self, fun _ => Std.LawfulEqCmp.eq_of_compare,
    fun _ => Std.OrientedCmp.eq_swap, fun _ _ => Std.TransCmp.isLE_trans⟩

/-- A strict comparison followed by a non-strict comparison stays strict. -/
theorem At.lt_of_lt_of_le (ha : At cmp a) {b c : α}
    (hab : cmp a b = .lt) (hbc : (cmp b c).isLE) : cmp a c = .lt := by
  have hac := ha.trans b c (by simp [hab]) hbc
  cases h : cmp a c with
  | lt => rfl
  | gt => simp [h] at hac
  | eq =>
    have same := ha.eq c h
    subst c
    have back : cmp b a = .gt := by
      have hs := ha.swap b
      rw [hab] at hs
      cases hba : cmp b a <;> simp only [hba, Ordering.swap] at hs
      · cases hs
      · cases hs
      · rfl
    simp [back] at hbc

/-- A non-strict comparison followed by a strict comparison stays strict. -/
theorem At.lt_of_le_of_lt (ha : At cmp a) {b c : α}
    (hab : (cmp a b).isLE) (hbc : cmp b c = .lt) : cmp a c = .lt := by
  cases h : cmp a b with
  | lt => exact ha.lt_of_lt_of_le h (by simp [hbc])
  | gt => simp [h] at hab
  | eq =>
    have same := ha.eq b h
    subst b
    exact hbc

/-- Compare pairs lexicographically, using an explicit comparator for each component. -/
def pair (ca : α → α → Ordering) (cb : β → β → Ordering)
    (a b : α × β) : Ordering :=
  (ca a.1 b.1).then (cb a.2 b.2)

/-- Lift the component comparison laws to a pair. -/
theorem At.pair {ca : α → α → Ordering} {cb : β → β → Ordering}
    {a : α} {b : β} (ha : At ca a) (hb : At cb b) :
    At (pair ca cb) (a, b) where
  refl := by simp [Defs.pair, ha.refl, hb.refl]
  eq := by
    intro other h
    simp only [Defs.pair, Ordering.then_eq_eq] at h
    exact Prod.ext (ha.eq _ h.1) (hb.eq _ h.2)
  swap := by
    intro other
    simp only [Defs.pair, Ordering.swap_then, ha.swap other.1, hb.swap other.2]
  trans := by
    intro other third hab hbc
    simp only [Defs.pair, Ordering.isLE_then_iff_and] at *
    refine ⟨ha.trans _ _ hab.1 hbc.1, ?_⟩
    rcases hab.2 with h | h
    · exact Or.inl (ha.lt_of_lt_of_le h hbc.1)
    · rcases hbc.2 with h' | h'
      · exact Or.inl (ha.lt_of_le_of_lt hab.1 h')
      · exact Or.inr (hb.trans _ _ h h')

/-- Lift laws for every element to lexicographic list comparison. -/
theorem At.list (xs : List α) (good : ∀ x ∈ xs, At cmp x) :
    At (List.compareLex cmp) xs := by
  induction xs with
  | nil =>
    constructor
    · rfl
    · intro b h; simpa using (List.compareLex_nil_left_eq_eq.mp h).symm
    · intro b; cases b <;> rfl
    · intro b c _ _; exact List.isLE_compareLex_nil_left
  | cons x xs ih =>
    have hx := good x (by simp)
    have hxs := ih (fun y hy => good y (by simp [hy]))
    constructor
    · simp [List.compareLex_cons_cons, hx.refl, hxs.refl]
    · intro b h
      cases b with
      | nil => simp [List.compareLex_cons_nil] at h
      | cons y ys =>
        simp only [List.compareLex_cons_cons, Ordering.then_eq_eq] at h
        rw [hx.eq y h.1, hxs.eq ys h.2]
    · intro b
      cases b with
      | nil => rfl
      | cons y ys =>
        simp only [List.compareLex_cons_cons, Ordering.swap_then, hx.swap y, hxs.swap ys]
    · intro b c hab hbc
      cases b <;> cases c <;> (try simp [List.compareLex_cons_nil] at *; done)
      simp only [List.compareLex_cons_cons, Ordering.isLE_then_iff_and] at *
      refine ⟨hx.trans _ _ hab.1 hbc.1, ?_⟩
      rcases hab.2 with h | h
      · exact Or.inl (hx.lt_of_lt_of_le h hbc.1)
      · rcases hbc.2 with h' | h'
        · exact Or.inl (hx.lt_of_le_of_lt hab.1 h')
        · exact Or.inr (hxs.trans _ _ h h')

end Defs
