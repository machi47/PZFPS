-- Give mouse capture a normal PZ-configurable binding instead of stealing an existing action.
local action = "PZFPS Mouse Capture"
local present = false
for _, binding in ipairs(keyBinding) do
    if binding.value == action then
        present = true
        break
    end
end

if not present then
    table.insert(keyBinding, { value = "[PZFPS]" })
    table.insert(keyBinding, { value = action, key = Keyboard.KEY_F8 })
end

if getCore():getKeyBinding(action) == nil then
    getCore():addKeyBinding(action, Keyboard.KEY_F8, 0, false, false, false)
end
