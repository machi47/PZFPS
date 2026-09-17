-- Disposable-profile automation only. This is inert unless pzfps-autotest.txt exists
-- in the active Zomboid cache, which the project-local launcher creates.
require "ISUI/ISPostDeathUI"
require "OptionScreens/CoopCharacterCreation"

local marker = getFileReader("pzfps-autotest.txt", false)
if not marker then
    return
end
marker:close()

local finished = false

local function advanceDisposableCharacter()
    if finished then return end

    local player = getSpecificPlayer(0)
    if player and not player:isDead() then
        print("[PZFPS] disposable autotest character is active")
        finished = true
        Events.OnTick.Remove(advanceDisposableCharacter)
        return
    end

    local creation = CoopCharacterCreation.instance
    if creation then
        if creation.mapSpawnSelect:isVisible() then
            if #creation.mapSpawnSelect.listbox.items == 0 then
                creation.mapSpawnSelect:fillList()
            end
            if #creation.mapSpawnSelect.listbox.items > 0 then
                creation.mapSpawnSelect.listbox.selected = 1
                creation.mapSpawnSelect:clickNext()
                print("[PZFPS] disposable autotest selected a spawn region")
            end
            return
        end
        if creation.charCreationProfession:isVisible() then
            creation.charCreationProfession:onOptionMouseDown({ internal = "NEXT" }, 0, 0)
            print("[PZFPS] disposable autotest accepted the default profession")
            return
        end
        if creation.charCreationMain:isVisible() then
            creation.charCreationMain:onOptionMouseDown({ internal = "RANDOM" }, 0, 0)
            creation.charCreationMain:onOptionMouseDown({ internal = "NEXT" }, 0, 0)
            print("[PZFPS] disposable autotest created a randomized character")
            return
        end
    end

    local postDeath = ISPostDeathUI.instance[0]
    if postDeath and postDeath.waitOver and postDeath.buttonRespawn:isVisible() then
        postDeath:onRespawn()
        print("[PZFPS] disposable autotest opened character creation")
    end
end

Events.OnTick.Add(advanceDisposableCharacter)
