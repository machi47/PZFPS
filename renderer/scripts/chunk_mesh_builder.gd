class_name PZFPSChunkMeshBuilder
extends RefCounted

const LEVEL_HEIGHT := 3.0
const CYLINDER_SEGMENTS := 10

var registry: PZFPSAssetRegistry
var vertices := PackedVector3Array()
var normals := PackedVector3Array()
var colors := PackedColorArray()
var indices := PackedInt32Array()
var textured_vertices := PackedVector3Array()
var textured_normals := PackedVector3Array()
var textured_colors := PackedColorArray()
var textured_uvs := PackedVector2Array()
var textured_indices := PackedInt32Array()
var current_appearance: Dictionary = {}
var textured_material: Material
var missing_sprites := 0
var primitive_count := 0


func _init(asset_registry: PZFPSAssetRegistry) -> void:
	registry = asset_registry


func build(chunk: Dictionary) -> ArrayMesh:
	vertices.clear()
	normals.clear()
	colors.clear()
	indices.clear()
	textured_vertices.clear()
	textured_normals.clear()
	textured_colors.clear()
	textured_uvs.clear()
	textured_indices.clear()
	current_appearance = {}
	textured_material = null
	missing_sprites = 0
	primitive_count = 0
	for square in chunk.get("squares", []):
		# Never expose squares PZ says the player has not discovered.
		if (int(square.get("visibility", 0)) & 1) == 0:
			continue
		var base := Vector3(float(square["local_x"]), float(square["z"]) * LEVEL_HEIGHT, float(square["local_y"]))
		var light := _square_light(square)
		if square.get("solid_floor", false):
			_add_floor(base, light)
		for object in square.get("objects", []):
			_add_object(base, object, light)
	var mesh := ArrayMesh.new()
	if vertices.is_empty() and textured_vertices.is_empty():
		return mesh
	if not vertices.is_empty():
		var arrays: Array = []
		arrays.resize(Mesh.ARRAY_MAX)
		arrays[Mesh.ARRAY_VERTEX] = vertices
		arrays[Mesh.ARRAY_NORMAL] = normals
		arrays[Mesh.ARRAY_COLOR] = colors
		arrays[Mesh.ARRAY_INDEX] = indices
		mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays)
	if not textured_vertices.is_empty():
		var textured_arrays: Array = []
		textured_arrays.resize(Mesh.ARRAY_MAX)
		textured_arrays[Mesh.ARRAY_VERTEX] = textured_vertices
		textured_arrays[Mesh.ARRAY_NORMAL] = textured_normals
		textured_arrays[Mesh.ARRAY_COLOR] = textured_colors
		textured_arrays[Mesh.ARRAY_TEX_UV] = textured_uvs
		textured_arrays[Mesh.ARRAY_INDEX] = textured_indices
		mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, textured_arrays)
		mesh.surface_set_material(mesh.get_surface_count() - 1, textured_material)
	return mesh


func _add_object(base: Vector3, object: Dictionary, light: Color) -> void:
	var sprite := str(object.get("sprite", ""))
	var tile := registry.tile(sprite)
	current_appearance = registry.appearance(sprite)
	if not current_appearance.is_empty():
		textured_material = current_appearance["material"]
	var identity_color := _identity_color(sprite) * light
	var geometry: Array = tile.get("geometry", [])
	if geometry.is_empty():
		missing_sprites += 1
		_add_conservative_fallback(base, object, identity_color)
		return
	for primitive in geometry:
		primitive_count += 1
		var transform := _primitive_transform(base, primitive)
		match str(primitive.get("kind", "")):
			"box":
				_add_box(transform, _vec3(primitive["min"]), _vec3(primitive["max"]), identity_color)
			"cylinder":
				_add_cylinder(transform, float(primitive["radius1"]), float(primitive["radius2"]), float(primitive["height"]), identity_color)
			"polygon":
				_add_polygon(transform, str(primitive["plane"]), primitive["points"], identity_color)
	current_appearance = {}


func _primitive_transform(base: Vector3, primitive: Dictionary) -> Transform3D:
	var translate := _vec3(primitive.get("translate", [0.0, 0.0, 0.0]))
	var rotate := _vec3(primitive.get("rotate_degrees", [0.0, 0.0, 0.0]))
	var basis := Basis.from_euler(Vector3(deg_to_rad(rotate.x), deg_to_rad(rotate.y), deg_to_rad(rotate.z)))
	return Transform3D(basis, base + Vector3(0.5, 0.0, 0.5) + translate)


func _add_floor(base: Vector3, color: Color) -> void:
	current_appearance = {}
	var y := base.y
	_add_quad(
		Vector3(base.x, y, base.z),
		Vector3(base.x, y, base.z + 1.0),
		Vector3(base.x + 1.0, y, base.z + 1.0),
		Vector3(base.x + 1.0, y, base.z),
		Vector3.UP,
		color * Color(0.72, 0.78, 0.67, 1.0)
	)


func _add_box(transform: Transform3D, minimum: Vector3, maximum: Vector3, color: Color) -> void:
	var local := [
		Vector3(minimum.x, minimum.y, minimum.z),
		Vector3(maximum.x, minimum.y, minimum.z),
		Vector3(maximum.x, maximum.y, minimum.z),
		Vector3(minimum.x, maximum.y, minimum.z),
		Vector3(minimum.x, minimum.y, maximum.z),
		Vector3(maximum.x, minimum.y, maximum.z),
		Vector3(maximum.x, maximum.y, maximum.z),
		Vector3(minimum.x, maximum.y, maximum.z),
	]
	var p: Array[Vector3] = []
	for point in local:
		p.append(transform * point)
	_add_quad(p[0], p[3], p[2], p[1], transform.basis * Vector3.BACK, color, _quad_uv(local[0], local[3], local[2], local[1]))
	_add_quad(p[4], p[5], p[6], p[7], transform.basis * Vector3.FORWARD, color, _quad_uv(local[4], local[5], local[6], local[7]))
	_add_quad(p[0], p[4], p[7], p[3], transform.basis * Vector3.LEFT, color, _quad_uv(local[0], local[4], local[7], local[3]))
	_add_quad(p[1], p[2], p[6], p[5], transform.basis * Vector3.RIGHT, color, _quad_uv(local[1], local[2], local[6], local[5]))
	_add_quad(p[3], p[7], p[6], p[2], transform.basis * Vector3.UP, color, _quad_uv(local[3], local[7], local[6], local[2]))
	_add_quad(p[0], p[1], p[5], p[4], transform.basis * Vector3.DOWN, color, _quad_uv(local[0], local[1], local[5], local[4]))


func _add_cylinder(transform: Transform3D, radius_bottom: float, radius_top: float, height: float, color: Color) -> void:
	for segment in CYLINDER_SEGMENTS:
		var a0 := TAU * float(segment) / CYLINDER_SEGMENTS
		var a1 := TAU * float(segment + 1) / CYLINDER_SEGMENTS
		var b0 := transform * Vector3(cos(a0) * radius_bottom, 0.0, sin(a0) * radius_bottom)
		var b1 := transform * Vector3(cos(a1) * radius_bottom, 0.0, sin(a1) * radius_bottom)
		var t0 := transform * Vector3(cos(a0) * radius_top, height, sin(a0) * radius_top)
		var t1 := transform * Vector3(cos(a1) * radius_top, height, sin(a1) * radius_top)
		var normal := (transform.basis * Vector3(cos((a0 + a1) * 0.5), 0.0, sin((a0 + a1) * 0.5))).normalized()
		var lb0 := Vector3(cos(a0) * radius_bottom, 0.0, sin(a0) * radius_bottom)
		var lb1 := Vector3(cos(a1) * radius_bottom, 0.0, sin(a1) * radius_bottom)
		var lt0 := Vector3(cos(a0) * radius_top, height, sin(a0) * radius_top)
		var lt1 := Vector3(cos(a1) * radius_top, height, sin(a1) * radius_top)
		_add_quad(b0, b1, t1, t0, normal, color, _quad_uv(lb0, lb1, lt1, lt0))
		_add_triangle(transform.origin, b1, b0, transform.basis * Vector3.DOWN, color)
		_add_triangle(transform * Vector3(0.0, height, 0.0), t0, t1, transform.basis * Vector3.UP, color)


func _add_polygon(transform: Transform3D, plane: String, points: Array, color: Color) -> void:
	if points.size() < 3:
		return
	var polygon: Array[Vector3] = []
	for point in points:
		var a := float(point[0])
		var b := float(point[1])
		match plane:
			"XY": polygon.append(transform * Vector3(a, b, 0.0))
			"XZ": polygon.append(transform * Vector3(a, 0.0, b))
			"YZ": polygon.append(transform * Vector3(0.0, a, b))
			_: return
	var normal := (polygon[1] - polygon[0]).cross(polygon[2] - polygon[0]).normalized()
	for index in range(1, polygon.size() - 1):
		_add_triangle(polygon[0], polygon[index], polygon[index + 1], normal, color)


func _add_conservative_fallback(base: Vector3, object: Dictionary, color: Color) -> void:
	var is_door: bool = bool(object.get("door", false))
	var is_window: bool = bool(object.get("window", false))
	var object_type := str(object.get("object_type", "")).to_lower()
	if is_door or is_window or "wall" in object_type:
		var thickness := 0.08
		var height := 2.15 if is_door else 1.45 if is_window else 2.7
		var north: bool = bool(object.get("north", false))
		var minimum := Vector3(-0.5, 0.0, -thickness) if north else Vector3(-thickness, 0.0, -0.5)
		var maximum := Vector3(0.5, height, thickness) if north else Vector3(thickness, height, 0.5)
		_add_box(Transform3D(Basis.IDENTITY, base + Vector3(0.5, 0.0, 0.5)), minimum, maximum, color)
	elif not str(object.get("sprite", "")).is_empty():
		# Unknown art remains an explicit, identity-stable proxy instead of being hallucinated.
		_add_box(Transform3D(Basis.IDENTITY, base + Vector3(0.5, 0.0, 0.5)), Vector3(-0.18, 0.0, -0.18), Vector3(0.18, 0.55, 0.18), color * 0.65)


func _add_quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3, normal: Vector3, color: Color, uvs: Array[Vector2] = []) -> void:
	if not current_appearance.is_empty() and uvs.size() == 4:
		var textured_start := textured_vertices.size()
		textured_vertices.append_array(PackedVector3Array([a, b, c, d]))
		textured_uvs.append_array(PackedVector2Array(uvs))
		for ignored in 4:
			textured_normals.append(normal.normalized())
			textured_colors.append(color)
		textured_indices.append_array(PackedInt32Array([textured_start, textured_start + 1, textured_start + 2, textured_start, textured_start + 2, textured_start + 3]))
		return
	var start := vertices.size()
	vertices.append_array(PackedVector3Array([a, b, c, d]))
	for ignored in 4:
		normals.append(normal.normalized())
		colors.append(color)
	indices.append_array(PackedInt32Array([start, start + 1, start + 2, start, start + 2, start + 3]))


func _add_triangle(a: Vector3, b: Vector3, c: Vector3, normal: Vector3, color: Color) -> void:
	var start := vertices.size()
	vertices.append_array(PackedVector3Array([a, b, c]))
	for ignored in 3:
		normals.append(normal.normalized())
		colors.append(color)
	indices.append_array(PackedInt32Array([start, start + 1, start + 2]))


func _quad_uv(a: Vector3, b: Vector3, c: Vector3, d: Vector3) -> Array[Vector2]:
	if current_appearance.is_empty():
		return []
	return [_appearance_uv(a), _appearance_uv(b), _appearance_uv(c), _appearance_uv(d)]


func _appearance_uv(point: Vector3) -> Vector2:
	var region: Dictionary = current_appearance["region"]
	var page_size: Vector2 = current_appearance["page_size"]
	# This is the installed B42 2x isometric projection from IsoUtils:
	# x=(world_x-world_y)*64, y=(world_x+world_y)*32-height*192.
	var original_x := 64.0 + (point.x - point.z) * 64.0
	var original_y := 256.0 + (point.x + point.z) * 32.0 - point.y * 192.0
	var page_x := float(region["x"]) + original_x - float(region["offset_x"])
	var page_y := float(region["y"]) + original_y - float(region["offset_y"])
	return Vector2(page_x / page_size.x, page_y / page_size.y)


func _square_light(square: Dictionary) -> Color:
	var visibility := int(square.get("visibility", 0))
	var live_visibility := 1.0 if (visibility & 4) != 0 else 0.42
	return Color(
		clampf(float(square.get("light_r", 255)) / 255.0, 0.08, 1.0) * live_visibility,
		clampf(float(square.get("light_g", 255)) / 255.0, 0.08, 1.0) * live_visibility,
		clampf(float(square.get("light_b", 255)) / 255.0, 0.08, 1.0) * live_visibility,
		1.0
	)


func _identity_color(value: String) -> Color:
	var hash := value.hash()
	var hue := float(abs(hash) % 1000) / 1000.0
	return Color.from_hsv(hue, 0.28, 0.78, 1.0)


func _vec3(value: Array) -> Vector3:
	return Vector3(float(value[0]), float(value[1]), float(value[2]))
