class_name PZFPSBridgeClient
extends RefCounted

const MAGIC := 0x505A4650
const VERSION := 5
const MAX_PACKET_BYTES := 64 * 1024 * 1024

const HELLO := 1
const PLAYER := 2
const ENTITIES := 3
const CHUNK_UPSERT := 4
const CHUNK_REMOVE := 5
const RESNAPSHOT_DONE := 6
const INPUT := 100
const RESNAPSHOT_REQUEST := 101

var host := "127.0.0.1"
var port := 24872
var connection := StreamPeerTCP.new()
var receive_buffer := PackedByteArray()
var session_id := 0
var input_sequence := 0
var received_bytes := 0
var decoded_packets := 0
var _last_status := StreamPeerTCP.STATUS_NONE


func configure(new_host: String, new_port: int) -> void:
	host = new_host
	port = new_port


func connect_to_bridge() -> int:
	if connection.get_status() != StreamPeerTCP.STATUS_NONE:
		connection.disconnect_from_host()
	receive_buffer.clear()
	return connection.connect_to_host(host, port)


func status() -> int:
	return connection.get_status()


func poll() -> Array:
	connection.poll()
	var current_status := connection.get_status()
	if current_status != _last_status:
		_last_status = current_status
		print("[PZFPS renderer] bridge status=", current_status)
	if current_status != StreamPeerTCP.STATUS_CONNECTED:
		return []
	var available := connection.get_available_bytes()
	if available > 0:
		var result := connection.get_data(available)
		if result[0] != OK:
			push_error("PZFPS bridge read failed: %s" % result[0])
			connection.disconnect_from_host()
			return []
		receive_buffer.append_array(result[1])
		received_bytes += available
	return _decode_frames()


func send_input(strafe: float, forward: float, yaw: float, pitch: float, buttons: int) -> int:
	if connection.get_status() != StreamPeerTCP.STATUS_CONNECTED:
		return ERR_CONNECTION_ERROR
	input_sequence += 1
	var packet := _packet_header(INPUT, input_sequence)
	packet.put_float(clampf(strafe, -1.0, 1.0))
	packet.put_float(clampf(forward, -1.0, 1.0))
	packet.put_float(yaw)
	packet.put_float(clampf(pitch, -1.553343, 1.553343))
	packet.put_u32(buttons)
	return _send_packet(packet.data_array)


func request_resnapshot() -> int:
	if connection.get_status() != StreamPeerTCP.STATUS_CONNECTED:
		return ERR_CONNECTION_ERROR
	input_sequence += 1
	return _send_packet(_packet_header(RESNAPSHOT_REQUEST, input_sequence).data_array)


func _packet_header(kind: int, sequence: int) -> StreamPeerBuffer:
	var packet := StreamPeerBuffer.new()
	packet.big_endian = true
	packet.put_u32(MAGIC)
	packet.put_u16(VERSION)
	packet.put_u16(kind)
	packet.put_u64(sequence)
	packet.put_u64(session_id)
	return packet


func _send_packet(packet: PackedByteArray) -> int:
	var frame := StreamPeerBuffer.new()
	frame.big_endian = true
	frame.put_u32(packet.size())
	frame.put_data(packet)
	return connection.put_data(frame.data_array)


func _decode_frames() -> Array:
	var messages: Array = []
	while receive_buffer.size() >= 4:
		var frame_length := _read_u32_be(receive_buffer, 0)
		if frame_length < 24 or frame_length > MAX_PACKET_BYTES:
			push_error("invalid PZFPS frame length: %d" % frame_length)
			connection.disconnect_from_host()
			receive_buffer.clear()
			break
		if receive_buffer.size() < 4 + frame_length:
			break
		var bytes := receive_buffer.slice(4, 4 + frame_length)
		receive_buffer = receive_buffer.slice(4 + frame_length)
		var message := _decode_packet(bytes)
		if not message.is_empty():
			messages.append(message)
			decoded_packets += 1
	return messages


func _decode_packet(bytes: PackedByteArray) -> Dictionary:
	var packet := StreamPeerBuffer.new()
	packet.big_endian = true
	packet.data_array = bytes
	var magic := packet.get_u32()
	var version := packet.get_u16()
	var kind := packet.get_u16()
	var sequence := packet.get_u64()
	var packet_session := packet.get_u64()
	if magic != MAGIC:
		push_error("invalid PZFPS packet magic")
		return {}
	if version != VERSION:
		push_error("unsupported PZFPS protocol version: %d" % version)
		return {}
	var message := {"kind": kind, "sequence": sequence, "session": packet_session}
	match kind:
		HELLO:
			message["name"] = _read_string(packet)
			message["game_version"] = _read_string(packet)
			message["block_size"] = _signed32(packet.get_u32())
			session_id = packet_session
		PLAYER:
			message.merge(_read_player(packet))
		ENTITIES:
			message.merge(_read_entities(packet))
		CHUNK_UPSERT:
			message.merge(_read_chunk(packet))
		CHUNK_REMOVE:
			message["world_x"] = _signed32(packet.get_u32())
			message["world_y"] = _signed32(packet.get_u32())
		RESNAPSHOT_DONE:
			pass
		_:
			push_warning("ignoring unknown PZFPS packet kind %d" % kind)
	return message


func _read_player(packet: StreamPeerBuffer) -> Dictionary:
	return {
		"capture_nanos": packet.get_u64(),
		"capture_epoch_millis": packet.get_u64(),
		"accepted_input_sequence": packet.get_u64(),
		"x": packet.get_float(),
		"y": packet.get_float(),
		"z": packet.get_float(),
		"forward_x": packet.get_float(),
		"forward_y": packet.get_float(),
		"vertical_aim": packet.get_float(),
		"action_state": _read_string(packet),
		"aiming": packet.get_u8() != 0,
		"attacking": packet.get_u8() != 0,
		"in_vehicle": packet.get_u8() != 0,
		"eye_height": packet.get_float(),
	}


func _read_entities(packet: StreamPeerBuffer) -> Dictionary:
	var capture_nanos := packet.get_u64()
	var capture_epoch_millis := packet.get_u64()
	var count := packet.get_u32()
	var values: Array = []
	values.resize(count)
	for index in count:
		var value := {
			"id": _signed32(packet.get_u32()),
			"uid": _read_string(packet),
			"entity_kind": _read_string(packet),
			"subtype": _read_string(packet),
			"x": packet.get_float(),
			"y": packet.get_float(),
			"z": packet.get_float(),
			"forward_x": packet.get_float(),
			"forward_y": packet.get_float(),
			"state": _read_string(packet),
			"on_floor": packet.get_u8() != 0,
			"crawling": packet.get_u8() != 0,
		}
		value["pose"] = _read_pose(packet)
		values[index] = value
	return {"capture_nanos": capture_nanos, "capture_epoch_millis": capture_epoch_millis, "entities": values}


func _read_pose(packet: StreamPeerBuffer) -> Dictionary:
	var available := packet.get_u8() != 0
	if not available:
		return {"available": false, "bones": [], "model_parts": []}
	var pose := {
		"available": true,
		"model": _read_string(packet),
		"model_parts": [],
	}
	var part_count := packet.get_u16()
	var parts: Array[String] = []
	parts.resize(part_count)
	for part_index in part_count:
		parts[part_index] = _read_string(packet)
	pose["model_parts"] = parts
	pose["animation"] = _read_string(packet)
	pose["animation_time"] = packet.get_float()
	pose["animation_weight"] = packet.get_float()
	var bone_count := packet.get_u16()
	var bones: Array = []
	bones.resize(bone_count)
	for bone_index in bone_count:
		var matrix: Array[float] = []
		matrix.resize(16)
		var index := packet.get_u16()
		var parent := packet.get_u16()
		if parent >= 0x8000:
			parent -= 0x10000
		var name := _read_string(packet)
		for component in 16:
			matrix[component] = packet.get_float()
		bones[bone_index] = {
			"index": index,
			"parent": parent,
			"name": name,
			"matrix": matrix,
			"world_x": packet.get_float(),
			"world_y": packet.get_float(),
			"world_z": packet.get_float(),
		}
	pose["bones"] = bones
	return pose


func _read_chunk(packet: StreamPeerBuffer) -> Dictionary:
	var world_x := _signed32(packet.get_u32())
	var world_y := _signed32(packet.get_u32())
	var source_revision := packet.get_u64()
	var fingerprint := packet.get_u64()
	var square_count := packet.get_u32()
	var squares: Array = []
	squares.resize(square_count)
	for square_index in square_count:
		var square := {
			"local_x": packet.get_u8(),
			"local_y": packet.get_u8(),
			"z": packet.get_u8(),
			"room_id": packet.get_u64(),
			"visibility": packet.get_u8(),
			"light_r": packet.get_u16(),
			"light_g": packet.get_u16(),
			"light_b": packet.get_u16(),
		}
		var flags := packet.get_u8()
		square["solid_floor"] = (flags & 1) != 0
		square["exterior"] = (flags & 2) != 0
		square["roof"] = (flags & 4) != 0
		square["stairs"] = (flags & 8) != 0
		square["stairs_below"] = (flags & 16) != 0
		square["stair_top"] = (flags & 32) != 0
		var object_count := packet.get_u16()
		var objects: Array = []
		objects.resize(object_count)
		for object_index in object_count:
			var object := {
				"index": packet.get_u16(),
				"java_type": _read_string(packet),
				"object_type": _read_string(packet),
				"sprite": _read_string(packet),
			}
			var object_flags := packet.get_u16()
			object["door"] = (object_flags & 1) != 0
			object["window"] = (object_flags & 2) != 0
			object["north"] = (object_flags & 4) != 0
			object["open"] = (object_flags & 8) != 0
			object["hoppable"] = (object_flags & 16) != 0
			object["edge_north"] = (object_flags & 32) != 0
			object["edge_west"] = (object_flags & 64) != 0
			object["container"] = (object_flags & 128) != 0
			object["solid"] = (object_flags & 256) != 0
			object["solid_trans"] = (object_flags & 512) != 0
			object["blocks_sight"] = (object_flags & 1024) != 0
			object["floor"] = (object_flags & 2048) != 0
			object["world_item"] = _read_world_item(packet)
			objects[object_index] = object
		square["objects"] = objects
		squares[square_index] = square
	return {
		"world_x": world_x,
		"world_y": world_y,
		"source_revision": source_revision,
		"fingerprint": fingerprint,
		"squares": squares,
	}


func _read_world_item(packet: StreamPeerBuffer) -> Dictionary:
	var present := packet.get_u8() != 0
	if not present:
		return {"present": false}
	return {
		"present": true,
		"item_id": _signed32(packet.get_u32()),
		"full_type": _read_string(packet),
		"static_model": _read_string(packet),
		"world_static_model": _read_string(packet),
		"world_object_sprite": _read_string(packet),
		"world_texture": _read_string(packet),
		"world_x": packet.get_float(),
		"world_y": packet.get_float(),
		"world_z": packet.get_float(),
		"rotation_x": packet.get_float(),
		"rotation_y": packet.get_float(),
		"rotation_z": packet.get_float(),
		"scale": packet.get_float(),
		"extended_placement": packet.get_u8() != 0,
	}


func _read_string(packet: StreamPeerBuffer) -> String:
	var length := packet.get_u32()
	if length > 16 * 1024 * 1024:
		push_error("PZFPS string exceeds limit: %d" % length)
		return ""
	return packet.get_utf8_string(length)


func _read_u32_be(bytes: PackedByteArray, offset: int) -> int:
	return (int(bytes[offset]) << 24) | (int(bytes[offset + 1]) << 16) | (int(bytes[offset + 2]) << 8) | int(bytes[offset + 3])


func _signed32(value: int) -> int:
	return value - 0x100000000 if value >= 0x80000000 else value
