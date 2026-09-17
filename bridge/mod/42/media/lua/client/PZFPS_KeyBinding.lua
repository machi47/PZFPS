-- Give FPS-only actions normal PZ-configurable bindings instead of stealing an
-- existing gameplay action. F7/F8 are otherwise unbound by the installed B42
-- scripts and remain editable in Options > Key Bindings.
local function addBinding(action, key)
    local present = false
    for _, binding in ipairs(keyBinding) do
        if binding.value == action then
            present = true
            break
        end
    end

    if not present then
        table.insert(keyBinding, { value = action, key = key })
    end

    if getCore():getKeyBinding(action) == nil then
        getCore():addKeyBinding(action, key, 0, false, false, false)
    end
end

local headerPresent = false
for _, binding in ipairs(keyBinding) do
    if binding.value == "[PZFPS]" then
        headerPresent = true
        break
    end
end
if not headerPresent then
    table.insert(keyBinding, { value = "[PZFPS]" })
end

addBinding("PZFPS Context Menu", Keyboard.KEY_F7)
addBinding("PZFPS Mouse Capture", Keyboard.KEY_F8)

-- Open PZ's own complete B42 world menu for the exact live object selected by
-- the perspective reticle. Supplying its isometric screen coordinate keeps the
-- existing menu builder's square expansion correct; the visible menu is then
-- moved to the centre of this player's viewport for first-person use.
function PZFPS_OpenWorldContext(playerNum, object)
    if not object or not object:getSquare() then return false end
    if not ISContextManager then return false end

    local square = object:getSquare()
    local zoom = getCore():getZoom(playerNum)
    local pickX = isoToScreenX(playerNum, square:getX() + 0.5, square:getY() + 0.5, square:getZ()) / zoom
    local pickY = isoToScreenY(playerNum, square:getX() + 0.5, square:getY() + 0.5, square:getZ()) / zoom
    local context = ISContextManager.getInstance().createWorldMenu(
        playerNum, object, { object }, pickX, pickY)
    if not context or context:isEmpty() then return false end

    local centreX = getPlayerScreenLeft(playerNum) + getPlayerScreenWidth(playerNum) / 2
    local centreY = getPlayerScreenTop(playerNum) + getPlayerScreenHeight(playerNum) / 2
    context:setX(centreX)
    context:setY(centreY)
    context:setVisible(true)
    return true
end

-- B42's mouse inventory pages are normally visible as collapsed title strips.
-- A generic "mouse is over UI" test therefore cannot decide who owns the
-- cursor. Mark the inventory/loot pair as force-cursor only after the normal
-- Toggle Inventory handler has made it visible. Closing or hiding the pages
-- removes ownership; PZFPS then returns to relative mouse look.
local function PZFPS_SyncInventoryCursor(key)
    if not getCore():isKey("Toggle Inventory", key) then return end
    local inventory = getPlayerInventory(0)
    local loot = getPlayerLoot(0)
    if not inventory or not loot then return end

    local visible = inventory:getIsVisible()
    inventory:setForceCursorVisible(visible)
    loot:setForceCursorVisible(visible)
end

Events.OnKeyPressed.Add(PZFPS_SyncInventoryCursor)
