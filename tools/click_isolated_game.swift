// Explicit, bounded native click fallback when the computer-use service is unavailable.
import AppKit
import CoreGraphics

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

guard CommandLine.arguments.count == 4,
      let pid = Int32(CommandLine.arguments[1]),
      let x = Double(CommandLine.arguments[2]), let y = Double(CommandLine.arguments[3]),
      NSWorkspace.shared.frontmostApplication?.processIdentifier == pid,
      let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]]
else { fail("Expected foreground game PID and two screen coordinates; no click sent") }
let point = CGPoint(x: x, y: y)
let valid = windows.contains { info in
    guard (info[kCGWindowOwnerPID as String] as? Int) == Int(pid),
          (info[kCGWindowLayer as String] as? Int) == 0,
          let bounds = info[kCGWindowBounds as String] as? [String: Any],
          let rect = CGRect(dictionaryRepresentation: bounds as CFDictionary) else { return false }
    return rect.contains(point)
}
guard valid else { fail("Click is not inside the foreground game's window; no click sent") }
print("Clicking foreground game \(pid) at \(point); display bounds \(CGDisplayBounds(CGMainDisplayID()))")
CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
Thread.sleep(forTimeInterval: 0.08)
CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
Thread.sleep(forTimeInterval: 0.12)
CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
