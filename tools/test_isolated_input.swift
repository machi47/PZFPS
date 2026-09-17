// Bounded native input fallback for the project-owned disposable client only.
// Usage: swift ... PID key macKeyCode milliseconds [secondMacKeyCode]
//        swift ... PID look dx dy frames
import AppKit
import CoreGraphics

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8)); exit(1)
}
let args = CommandLine.arguments
guard args.count >= 5, let pid = Int32(args[1]),
      let app = NSRunningApplication(processIdentifier: pid),
      app.bundleURL?.standardizedFileURL.path == URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent(".local/pz-runtime/PZFPS Isolated.app").standardizedFileURL.path
else { fail("Expected this project's isolated game PID; no input sent") }
func foreground() -> Bool { NSWorkspace.shared.frontmostApplication?.processIdentifier == pid }
guard foreground() else { fail("Isolated game is not foreground; no input sent") }
if args[2] == "key" {
    guard let key = UInt16(args[3]), let milliseconds = Double(args[4]),
          milliseconds >= 50 && milliseconds <= 5000, args.count <= 6
    else { fail("Expected key code and duration 50..5000ms") }
    var keys = [key]
    if args.count == 6 {
        guard let second = UInt16(args[5]) else { fail("Invalid second key") }
        keys.append(second)
    }
    for code in keys { CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true)?.post(tap: .cghidEventTap) }
    let end = Date().addingTimeInterval(milliseconds / 1000)
    while Date() < end && foreground() { Thread.sleep(forTimeInterval: 0.01) }
    // Always release our held keys, including if focus changes during the test.
    for code in keys.reversed() { CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false)?.post(tap: .cghidEventTap) }
    print("Posted key test keys=\(keys) requestedMs=\(milliseconds) foregroundAtEnd=\(foreground())")
} else if args[2] == "look" {
    guard args.count == 6, let dx = Int64(args[3]), let dy = Int64(args[4]),
          abs(dx) <= 10 && abs(dy) <= 10, let frames = Int(args[5]), frames > 0 && frames <= 300
    else { fail("Expected dx/dy within +/-10 and 1..300 frames") }
    for _ in 0..<frames {
        guard foreground(), let current = CGEvent(source: nil)?.location else { fail("Focus changed; stopped mouse test") }
        let point = CGPoint(x: current.x + CGFloat(dx), y: current.y + CGFloat(dy))
        guard let event = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved,
                                 mouseCursorPosition: point, mouseButton: .left) else { fail("Cannot allocate mouse event") }
        event.setIntegerValueField(.mouseEventDeltaX, value: dx)
        event.setIntegerValueField(.mouseEventDeltaY, value: dy)
        event.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 1.0/60.0)
    }
    print("Posted mouse test dx=\(dx) dy=\(dy) frames=\(frames); verify actual camera response separately")
} else { fail("Unknown input test") }
