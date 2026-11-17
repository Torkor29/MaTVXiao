import XCTest
import Security
@testable import MaTV

/// Talks to the fake TV (mobile/fake_tv.py on 127.0.0.1:5555, started by the CI), which checks what a real adbd
/// checks: the key's format, the RSA signature, one acknowledged write at a time, and every byte of an install.
final class AdbTests: XCTestCase {
    private let host = ProcessInfo.processInfo.environment["FAKE_TV"] ?? "127.0.0.1"

    func testConnectInstallAndPair() throws {
        guard Socket.isOpen(host: host, port: 5555, timeout: 1) else { throw XCTSkip("No fake TV on \(host):5555") }
        var prompted = false
        let adb = try AdbClient.connect(host: host, keyName: "matv@ci", approvalSeconds: 30) { prompted = true }
        XCTAssertEqual(try adb.shell("getprop ro.product.brand").trimmingCharacters(in: .whitespacesAndNewlines), "Xiaomi")
        XCTAssertTrue(try adb.shell("dumpsys package io.freemytv | grep -m1 versionName").contains("versionName=2.7.1"))

        var bytes = [UInt8](repeating: 0, count: 3_000_000)
        XCTAssertEqual(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes), errSecSuccess)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("blob.apk")
        try Data(bytes).write(to: file)
        var last: Int64 = 0
        XCTAssertEqual(try adb.install(file: file) { done, _ in last = done }, "Success")
        XCTAssertEqual(last, 3_000_000)

        let pair = try adb.shell("am broadcast -n io.freemytv/.PairReceiver")
        XCTAssertTrue(pair.contains("matv-connect faketoken_0123456789abcdef 8765"), pair)
        adb.close()

        // Known now: the next connection signs in without asking.
        var askedAgain = false
        let again = try AdbClient.connect(host: host, keyName: "matv@ci", approvalSeconds: 5) { askedAgain = true }
        XCTAssertFalse(askedAgain)
        again.close()
        print("first connection prompted: \(prompted)")
    }

    func testPublicKeyBlobShape() throws {
        let blob = try AdbKeys.load().publicKeyBlob(name: "matv@ci")
        let text = String(decoding: blob, as: UTF8.self)
        XCTAssertTrue(text.hasSuffix(" matv@ci\0"))
        let raw = Data(base64Encoded: String(text.split(separator: " ")[0]))!
        XCTAssertEqual(raw.count, 524)
        XCTAssertEqual(raw[0], 64)
    }

    func testReleaseDownloadIsCheckedAndPinned() async throws {
        var items = [AppItem.home(), AppItem.live()]
        do { try await Release.fill(&items) } catch { throw XCTSkip("GitHub: \(error.localizedDescription)") }
        XCTAssertFalse(items[1].url.isEmpty)
        let file = try await Release.download(items[1]) { _, _ in }   // MaTV Live is small
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        var wrong = items[1]
        wrong = AppItem(key: wrong.key, title: "Faux", about: "", package: wrong.package, pin: String(repeating: "0", count: 64),
                        version: wrong.version, url: wrong.url, digest: wrong.digest, installed: "", size: wrong.size)
        do {
            _ = try await Release.download(wrong) { _, _ in }
            XCTFail("a wrong certificate must be refused")
        } catch {}
    }

    func testVersions() {
        XCTAssertEqual(Release.compare("2.10.0", "2.9.1"), 1)
        XCTAssertEqual(Release.compare("2.9.0", "2.9"), 0)
        XCTAssertEqual(Release.compare("1.2", "1.10"), -1)
    }
}
