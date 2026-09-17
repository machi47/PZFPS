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

-- Draw a fixed first-person reference through PZ's own UI pass. crosshair00 is
-- one animated fragment (and appeared as a literal parenthesis when drawn as a
-- complete reticle), so assemble four symmetric ticks from PZ's installed white
-- texture. Hide them whenever PZFPS releases the real cursor to UI.
local PZFPS_reticlePixel = nil
function PZFPS_DrawReticle()
    if not getSpecificPlayer(0) or Mouse.isCursorVisible() then return end
    if not PZFPS_reticlePixel then
        PZFPS_reticlePixel = getTexture("media/white.png")
    end
    if not PZFPS_reticlePixel then return end

    -- The empty centre remains fixed on the shared optical ray. Gap/length changes
    -- report tracking state only; PZ still owns reach, action and combat validity.
    local targetKind = PZFPS_ReticleTargetKind or "none"
    local gap = 5
    local arm = 4
    local thickness = 2
    local alpha = 0.48
    if targetKind == "world" then
        gap = 5
        arm = 5
        alpha = 0.66
    elseif targetKind == "interact" then
        gap = 4
        arm = 6
        alpha = 0.92
    elseif targetKind == "combat" then
        gap = 3
        arm = 7
        alpha = 1.0
    end
    local centreX = getPlayerScreenLeft(0) + getPlayerScreenWidth(0) / 2
    local centreY = getPlayerScreenTop(0) + getPlayerScreenHeight(0) / 2
    local halfThickness = thickness / 2
    UIManager.DrawTexture(PZFPS_reticlePixel,
        centreX - gap - arm, centreY - halfThickness, arm, thickness, alpha)
    UIManager.DrawTexture(PZFPS_reticlePixel,
        centreX + gap, centreY - halfThickness, arm, thickness, alpha)
    UIManager.DrawTexture(PZFPS_reticlePixel,
        centreX - halfThickness, centreY - gap - arm, thickness, arm, alpha)
    UIManager.DrawTexture(PZFPS_reticlePixel,
        centreX - halfThickness, centreY + gap, thickness, arm, alpha)
end

Events.OnPostUIDraw.Add(PZFPS_DrawReticle)
