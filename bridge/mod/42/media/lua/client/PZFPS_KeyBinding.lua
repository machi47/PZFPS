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

local function PZFPS_WorldContextCoordinates(playerNum, object)
    if not object or not object:getSquare() then return false end
    if not ISContextManager then return false end

    local square = object:getSquare()
    local zoom = getCore():getZoom(playerNum)
    local pickX = isoToScreenX(playerNum, square:getX() + 0.5, square:getY() + 0.5, square:getZ()) / zoom
    local pickY = isoToScreenY(playerNum, square:getX() + 0.5, square:getY() + 0.5, square:getZ()) / zoom
    return pickX, pickY
end

-- B42's manager has a documented non-visible test path for controller target
-- discovery. Use the same path so a decorative ray hit cannot steal F7 from an
-- actionable appliance, curtain, switch, furniture object, or dropped item
-- behind it. PZ and other mods remain the authorities on which options exist.
function PZFPS_HasWorldContext(playerNum, object)
    local pickX, pickY = PZFPS_WorldContextCoordinates(playerNum, object)
    if not pickX then return false end
    return ISContextManager.getInstance().createWorldMenu(
        playerNum, object, { object }, pickX, pickY, true) == true
end

-- Open PZ's own complete B42 world menu for the exact live object selected by
-- the perspective reticle. The manager expands { object } to all objects on its
-- square before invoking vanilla and mod context providers.
function PZFPS_OpenWorldContext(playerNum, object)
    local pickX, pickY = PZFPS_WorldContextCoordinates(playerNum, object)
    if not pickX then return false end
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

-- Draw a fixed first-person reference through PZ's own UI pass. Reuse the
-- installed reticle asset rather than shipping a copied game texture, and hide
-- it whenever PZFPS has released the real cursor to menus or inventory.
local PZFPS_reticle = nil
function PZFPS_DrawReticle()
    if not getSpecificPlayer(0) or Mouse.isCursorVisible() then return end
    if not PZFPS_reticle then
        PZFPS_reticle = getTexture("media/ui/Reticle/crosshair00.png")
    end
    if not PZFPS_reticle then return end

    local size = 20
    local x = getPlayerScreenLeft(0) + (getPlayerScreenWidth(0) - size) / 2
    local y = getPlayerScreenTop(0) + (getPlayerScreenHeight(0) - size) / 2
    UIManager.DrawTexture(PZFPS_reticle, x, y, size, size, 0.85)
end

Events.OnPostUIDraw.Add(PZFPS_DrawReticle)
