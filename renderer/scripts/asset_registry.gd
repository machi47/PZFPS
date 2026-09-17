class_name PZFPSAssetRegistry
extends RefCounted

var path := ""
var game_version := ""
var source_sha256 := ""
var tiles: Dictionary = {}
var appearances: Dictionary = {}


func load_registry(registry_path: String) -> int:
	path = registry_path
	if not FileAccess.file_exists(path):
		push_error("PZFPS asset registry is absent: %s" % path)
		return ERR_FILE_NOT_FOUND
	var file := FileAccess.open(path, FileAccess.READ)
	if file == null:
		push_error("cannot open PZFPS asset registry: %s" % path)
		return FileAccess.get_open_error()
	var parsed = JSON.parse_string(file.get_as_text())
	if not parsed is Dictionary:
		push_error("invalid PZFPS asset registry JSON: %s" % path)
		return ERR_PARSE_ERROR
	if int(parsed.get("schema_version", 0)) != 1:
		push_error("unsupported PZFPS asset registry schema")
		return ERR_PARSE_ERROR
	game_version = str(parsed.get("game_version", ""))
	source_sha256 = str(parsed.get("source_sha256", ""))
	tiles = parsed.get("tiles", {})
	print("[PZFPS renderer] asset registry loaded tiles=", tiles.size(), " game=", game_version, " sha256=", source_sha256)
	return OK


func tile(sprite_name: String) -> Dictionary:
	return tiles.get(sprite_name, {})


func load_appearance_manifest(manifest_path: String) -> int:
	if not FileAccess.file_exists(manifest_path):
		push_warning("PZFPS appearance manifest is absent: %s" % manifest_path)
		return ERR_FILE_NOT_FOUND
	var file := FileAccess.open(manifest_path, FileAccess.READ)
	if file == null:
		return FileAccess.get_open_error()
	var parsed = JSON.parse_string(file.get_as_text())
	if not parsed is Dictionary or int(parsed.get("schema_version", 0)) != 1:
		push_error("invalid PZFPS appearance manifest: %s" % manifest_path)
		return ERR_PARSE_ERROR
	var page_path := str(parsed.get("page_path", ""))
	if not FileAccess.file_exists(page_path):
		push_error("indexed PZ appearance page is absent: %s" % page_path)
		return ERR_FILE_NOT_FOUND
	var image := Image.new()
	var image_result := image.load(page_path)
	if image_result != OK:
		push_error("cannot decode PZ appearance page: %s" % page_path)
		return image_result
	var texture := ImageTexture.create_from_image(image)
	var material := StandardMaterial3D.new()
	material.albedo_texture = texture
	material.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	material.roughness = 0.82
	material.cull_mode = BaseMaterial3D.CULL_DISABLED
	material.vertex_color_use_as_albedo = true
	var sprite := str(parsed.get("sprite", ""))
	appearances[sprite] = {
		"material": material,
		"page_size": Vector2(float(image.get_width()), float(image.get_height())),
		"region": parsed.get("region", {}),
		"source_pack_sha256": str(parsed.get("source_pack_sha256", "")),
		"page_sha256": str(parsed.get("page_sha256", "")),
	}
	print("[PZFPS renderer] source appearance loaded sprite=", sprite, " page=", parsed.get("page", ""), " size=", image.get_width(), "x", image.get_height())
	return OK


func appearance(sprite_name: String) -> Dictionary:
	return appearances.get(sprite_name, {})
