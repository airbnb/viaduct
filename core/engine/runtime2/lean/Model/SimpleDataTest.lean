import Model.SimpleData

set_option autoImplicit false

namespace Model

-- Scalar payloads retain their existing orders, with integers before strings.
#guard compare (EngineSimpleData.integer (-7)) (.integer 0) == .lt
#guard compare (EngineSimpleData.string "Ada") (.string "Bob") == .lt
#guard compare (EngineSimpleData.integer 999) (.string "") == .lt

end Model
