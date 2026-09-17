extends Node3D

const LEVEL_HEIGHT := 3.0
const ORIGIN_GRID := 64.0
const MAX_PENDING_CHUNKS := 64

const BUTTON_AIM := 1
const BUTTON_PRIMARY := 2
const BUTTON_INTERACT := 4
const BUTTON_RUN := 8
const BUTTON_SPRINT := 16
const BUTTON_CROUCH := 32
const BUTTON_RELOAD := 64
const BUTTON_SHOUT := 128

var bridge := PZFPSBridgeClient.new()
var registry := PZFPSAssetRegistry.new()
var world_root := Node3D.new()
var entity_root := Node3D.new()
var camera := Camera3D.new()
var status_label := Label.new()
var material := StandardMaterial3D.new()
var chunks: Dictionary = {}
var entities: Dictionary = {}
var pending_chunks: Dictionary = {}
var pending_order: Array[String] = []
var player: Dictionary = {}
var world_origin := Vector2.ZERO
var block_size := 8
var yaw := 0.0
var pitch := 0.0
var input_accumulator := 0.0
var reconnect_accumulator := 0.0
var completed_chunk_builds := 0
var dropped_chunk_updates := 0
var last_resnapshot_sequence := 0
var test_exit_on_chunk := false
var completed_player_snapshots := 0
var completed_entity_snapshots := 0
var metrics_elapsed := 0.0
var metrics_previous_player := 0
var metrics_previous_entities := 0
var player_snapshots_per_second := 0.0
var entity_snapshots_per_second := 0.0
var latest_state_age_ms := -1.0


func _ready() -> void:
	_setup_scene()
	var registry_path := OS.get_environment("PZFPS_ASSET_REGISTRY")
	if registry_path.is_empty():
		registry_path = ProjectSettings.globalize_path("res://../.local/assets/pz-42.20/tile-geometry.json")
	var result := registry.load_registry(registry_path)
	if result != OK:
		get_tree().quit(result)
		return
	var appearance_path := OS.get_environment("PZFPS_APPEARANCE_MANIFEST")
	if appearance_path.is_empty():
		appearance_path = ProjectSettings.globalize_path("res://../.local/assets/pz-42.20/first-asset/furniture_bedding_01_0.json")
	registry.load_appearance_manifest(appearance_path)
	bridge.configure(
		OS.get_environment("PZFPS_BRIDGE_HOST") if not OS.get_environment("PZFPS_BRIDGE_HOST").is_empty() else "127.0.0.1",
		int(OS.get_environment("PZFPS_BRIDGE_PORT")) if not OS.get_environment("PZFPS_BRIDGE_PORT").is_empty() else 24872
	)
	bridge.connect_to_bridge()
	test_exit_on_chunk = OS.get_environment("PZFPS_TEST_EXIT_ON_CHUNK") == "1"
	if not OS.has_feature("headless"):
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _setup_scene() -> void:
	world_root.name = "World"
	entity_root.name = "Entities"
	add_child(world_root)
	add_child(entity_root)
	camera.name = "AuthoritativeCamera"
	camera.fov = 82.0
	camera.near = 0.035
	camera.far = 600.0
	add_child(camera)
	var environment := WorldEnvironment.new()
	var settings := Environment.new()
	settings.background_mode = Environment.BG_COLOR
	settings.background_color = Color(0.025, 0.03, 0.04)
	settings.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	settings.ambient_light_color = Color(0.72, 0.76, 0.82)
	settings.ambient_light_energy = 0.62
	settings.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	environment.environment = settings
	add_child(environment)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48.0, -32.0, 0.0)
	sun.light_energy = 1.15
	sun.shadow_enabled = true
	add_child(sun)
	material.vertex_color_use_as_albedo = true
	material.roughness = 0.86
	material.cull_mode = BaseMaterial3D.CULL_DISABLED
	var ui := CanvasLayer.new()
	add_child(ui)
	status_label.position = Vector2(16, 14)
	status_label.add_theme_font_size_override("font_size", 16)
	status_label.text = "PZFPS: waiting for authoritative bridge"
	ui.add_child(status_label)
	var crosshair := Label.new()
	crosshair.text = "+"
	crosshair.add_theme_font_size_override("font_size", 22)
	crosshair.set_anchors_preset(Control.PRESET_CENTER)
	crosshair.position = Vector2(-6, -14)
	ui.add_child(crosshair)


func _process(delta: float) -> void:
	for message in bridge.poll():
		_handle_message(message)
	_process_one_pending_chunk()
	_update_camera()
	_update_status()
	_update_metrics(delta)
	_reconnect_if_needed(delta)


func _physics_process(delta: float) -> void:
	input_accumulator += delta
	if input_accumulator < 1.0 / 60.0:
		return
	input_accumulator = fmod(input_accumulator, 1.0 / 60.0)
	bridge.send_input(_input_axis("move_left", "move_right", KEY_A, KEY_D), _input_axis("move_back", "move_forward", KEY_S, KEY_W), yaw, pitch, _buttons())


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		yaw = fmod(yaw - event.relative.x * 0.00225 + TAU, TAU)
		pitch = clampf(pitch - event.relative.y * 0.00225, -1.45, 1.45)
	elif event is InputEventKey and event.pressed and event.keycode == KEY_ESCAPE:
		Input.mouse_mode = Input.MOUSE_MODE_VISIBLE if Input.mouse_mode == Input.MOUSE_MODE_CAPTURED else Input.MOUSE_MODE_CAPTURED


func _handle_message(message: Dictionary) -> void:
	match int(message["kind"]):
		PZFPSBridgeClient.HELLO:
			block_size = int(message["block_size"])
			print("[PZFPS renderer] connected protocol=", PZFPSBridgeClient.VERSION, " game=", message["game_version"], " block=", block_size)
		PZFPSBridgeClient.PLAYER:
			var first_player := player.is_empty()
			player = message
			completed_player_snapshots += 1
			latest_state_age_ms = maxf(0.0, Time.get_unix_time_from_system() * 1000.0 - float(message["capture_epoch_millis"]))
			if first_player:
				yaw = atan2(float(player["forward_y"]), float(player["forward_x"]))
				pitch = float(player["vertical_aim"])
			_update_origin()
		PZFPSBridgeClient.ENTITIES:
			completed_entity_snapshots += 1
			_update_entities(message["entities"])
		PZFPSBridgeClient.CHUNK_UPSERT:
			_queue_chunk(message)
		PZFPSBridgeClient.CHUNK_REMOVE:
			_remove_chunk(_chunk_key(int(message["world_x"]), int(message["world_y"])))
		PZFPSBridgeClient.RESNAPSHOT_DONE:
			last_resnapshot_sequence = int(message["sequence"])


func _queue_chunk(chunk: Dictionary) -> void:
	var key := _chunk_key(int(chunk["world_x"]), int(chunk["world_y"]))
	if not pending_chunks.has(key):
		if pending_order.size() >= MAX_PENDING_CHUNKS:
			var discarded: String = pending_order.pop_front()
			pending_chunks.erase(discarded)
			dropped_chunk_updates += 1
		pending_order.append(key)
	pending_chunks[key] = chunk


func _process_one_pending_chunk() -> void:
	if pending_order.is_empty():
		return
	var key: String = pending_order.pop_front()
	var chunk: Dictionary = pending_chunks.get(key, {})
	pending_chunks.erase(key)
	if chunk.is_empty():
		return
	var builder := PZFPSChunkMeshBuilder.new(registry)
	var mesh := builder.build(chunk)
	var instance: MeshInstance3D
	if chunks.has(key):
		instance = chunks[key]
	else:
		instance = MeshInstance3D.new()
		instance.name = "Chunk_%s" % key
		world_root.add_child(instance)
		chunks[key] = instance
	instance.mesh = mesh
	if mesh.get_surface_count() > 0:
		instance.set_surface_override_material(0, material)
	instance.position = Vector3(float(chunk["world_x"] * block_size) - world_origin.x, 0.0, float(chunk["world_y"] * block_size) - world_origin.y)
	instance.set_meta("fingerprint", chunk["fingerprint"])
	instance.set_meta("missing_sprites", builder.missing_sprites)
	instance.set_meta("primitive_count", builder.primitive_count)
	completed_chunk_builds += 1
	print("[PZFPS renderer] chunk built key=", key, " vertices=", mesh.get_faces().size(), " surfaces=", mesh.get_surface_count(), " primitives=", builder.primitive_count, " unresolved=", builder.missing_sprites)
	if test_exit_on_chunk:
		if mesh.get_surface_count() < 2 or mesh.surface_get_material(1) == null:
			push_error("synthetic source-appearance surface was not built")
			get_tree().quit(20)
			return
		print("PZFPS_SYNTHETIC_TEST_OK chunks=", completed_chunk_builds, " entities=", entities.size(), " registry=", registry.tiles.size())
		get_tree().quit(0)


func _update_entities(values: Array) -> void:
	var live: Dictionary = {}
	for value in values:
		var key := str(value["id"])
		live[key] = true
		var instance: MeshInstance3D
		if entities.has(key):
			instance = entities[key]
		else:
			instance = MeshInstance3D.new()
			var entity_material := StandardMaterial3D.new()
			entity_material.albedo_color = Color(0.62, 0.12, 0.10) if value["entity_kind"] == "zombie" else Color(0.12, 0.38, 0.72)
			entity_material.vertex_color_use_as_albedo = true
			entity_material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
			instance.material_override = entity_material
			entity_root.add_child(instance)
			entities[key] = instance
		var pose: Dictionary = value.get("pose", {})
		if bool(pose.get("available", false)) and not pose.get("bones", []).is_empty():
			instance.mesh = _skeleton_mesh(pose["bones"])
			instance.position = Vector3.ZERO
			instance.set_meta("model", pose.get("model", ""))
			instance.set_meta("model_parts", pose.get("model_parts", []))
			instance.set_meta("animation", pose.get("animation", ""))
		else:
			if not instance.mesh is CapsuleMesh:
				var capsule := CapsuleMesh.new()
				capsule.radius = 0.28
				capsule.height = 1.72
				instance.mesh = capsule
			instance.position = _relative_position(float(value["x"]), float(value["y"]), float(value["z"]), 0.86)
	for key in entities.keys():
		if not live.has(key):
			entities[key].queue_free()
			entities.erase(key)


func _skeleton_mesh(bones: Array) -> ImmediateMesh:
	var by_index: Dictionary = {}
	for bone in bones:
		by_index[int(bone["index"])] = bone
	var mesh := ImmediateMesh.new()
	mesh.surface_begin(Mesh.PRIMITIVE_LINES)
	for bone in bones:
		var parent_index := int(bone["parent"])
		if parent_index < 0 or not by_index.has(parent_index):
			continue
		var parent: Dictionary = by_index[parent_index]
		mesh.surface_set_color(Color(0.2, 0.95, 0.8))
		mesh.surface_add_vertex(_relative_position(float(parent["world_x"]), float(parent["world_y"]), float(parent["world_z"]), 0.0))
		mesh.surface_set_color(Color(0.95, 0.7, 0.12))
		mesh.surface_add_vertex(_relative_position(float(bone["world_x"]), float(bone["world_y"]), float(bone["world_z"]), 0.0))
	mesh.surface_end()
	return mesh


func _update_camera() -> void:
	if player.is_empty():
		return
	camera.position = _relative_position(float(player["x"]), float(player["y"]), float(player["z"]), 1.68)
	var forward := Vector3(cos(yaw) * cos(pitch), sin(pitch), sin(yaw) * cos(pitch))
	camera.look_at(camera.position + forward, Vector3.UP)


func _update_origin() -> void:
	var new_origin := Vector2(
		floor(float(player["x"]) / ORIGIN_GRID) * ORIGIN_GRID,
		floor(float(player["y"]) / ORIGIN_GRID) * ORIGIN_GRID
	)
	if new_origin == world_origin:
		return
	world_origin = new_origin
	for key in chunks:
		var parts := str(key).split(":")
		chunks[key].position = Vector3(float(int(parts[0]) * block_size) - world_origin.x, 0.0, float(int(parts[1]) * block_size) - world_origin.y)


func _relative_position(x: float, y: float, z: float, height: float) -> Vector3:
	return Vector3(x - world_origin.x, z * LEVEL_HEIGHT + height, y - world_origin.y)


func _remove_chunk(key: String) -> void:
	pending_chunks.erase(key)
	pending_order.erase(key)
	if chunks.has(key):
		chunks[key].queue_free()
		chunks.erase(key)


func _update_status() -> void:
	var state := bridge.status()
	var position_text := "waiting"
	var action := ""
	var input_ack := 0
	if not player.is_empty():
		position_text = "%.2f %.2f z%.1f" % [player["x"], player["y"], player["z"]]
		action = str(player["action_state"])
		input_ack = int(player["accepted_input_sequence"])
	status_label.text = "PZFPS renderer %.1f fps | completed state %.1f/s entities %.1f/s age %.1f ms | bridge %d | pos %s | chunks %d queued %d built %d | actors %d | input ack %d | %s" % [Engine.get_frames_per_second(), player_snapshots_per_second, entity_snapshots_per_second, latest_state_age_ms, state, position_text, chunks.size(), pending_order.size(), completed_chunk_builds, entities.size(), input_ack, action]


func _update_metrics(delta: float) -> void:
	metrics_elapsed += delta
	if metrics_elapsed < 5.0:
		return
	player_snapshots_per_second = float(completed_player_snapshots - metrics_previous_player) / metrics_elapsed
	entity_snapshots_per_second = float(completed_entity_snapshots - metrics_previous_entities) / metrics_elapsed
	metrics_previous_player = completed_player_snapshots
	metrics_previous_entities = completed_entity_snapshots
	print("[PZFPS metrics] renderer_fps=%.2f completed_player_hz=%.2f completed_entity_hz=%.2f state_age_ms=%.2f bytes=%d packets=%d chunks_built=%d chunks_dropped=%d" % [Engine.get_frames_per_second(), player_snapshots_per_second, entity_snapshots_per_second, latest_state_age_ms, bridge.received_bytes, bridge.decoded_packets, completed_chunk_builds, dropped_chunk_updates])
	metrics_elapsed = 0.0


func _reconnect_if_needed(delta: float) -> void:
	if bridge.status() == StreamPeerTCP.STATUS_CONNECTED or bridge.status() == StreamPeerTCP.STATUS_CONNECTING:
		reconnect_accumulator = 0.0
		return
	reconnect_accumulator += delta
	if reconnect_accumulator >= 2.0:
		reconnect_accumulator = 0.0
		bridge.connect_to_bridge()


func _input_axis(negative_action: String, positive_action: String, negative_key: Key, positive_key: Key) -> float:
	var value := Input.get_axis(negative_action, positive_action) if InputMap.has_action(negative_action) and InputMap.has_action(positive_action) else 0.0
	if Input.is_key_pressed(negative_key): value -= 1.0
	if Input.is_key_pressed(positive_key): value += 1.0
	return clampf(value, -1.0, 1.0)


func _buttons() -> int:
	var result := 0
	if Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT): result |= BUTTON_AIM
	if Input.is_mouse_button_pressed(MOUSE_BUTTON_LEFT): result |= BUTTON_PRIMARY
	if Input.is_key_pressed(KEY_E): result |= BUTTON_INTERACT
	if Input.is_key_pressed(KEY_SHIFT): result |= BUTTON_RUN
	if Input.is_key_pressed(KEY_ALT): result |= BUTTON_SPRINT
	if Input.is_key_pressed(KEY_C): result |= BUTTON_CROUCH
	if Input.is_key_pressed(KEY_R): result |= BUTTON_RELOAD
	if Input.is_key_pressed(KEY_Q): result |= BUTTON_SHOUT
	return result


func _chunk_key(world_x: int, world_y: int) -> String:
	return "%d:%d" % [world_x, world_y]
