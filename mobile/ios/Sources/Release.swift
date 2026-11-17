import Foundation
import CryptoKit

/// One app to put on the TV, from MaTV's latest GitHub release.
struct AppItem: Identifiable {
    let key: String, title: String, about: String, package: String
    /// SHA-256 of the certificate the APK must be signed with (MaTV's keys).
    let pin: String
    var version = "", url = "", digest = "", installed = ""
    var size: Int64 = 0
    var wanted = false, done = false, failed = false
    var status = ""
    var fraction: Double = 0
    var id: String { key }

    var upToDate: Bool { !installed.isEmpty && !version.isEmpty && Release.compare(installed, version) >= 0 }

    static func home() -> AppItem {
        AppItem(key: "home", title: "Écran d'accueil MaTV", about: "Accueil sans pub, suggestions, fonds d'écran",
                package: "io.freemytv", pin: "517bb5aee53452b043aa056467fc9d6e11c631c363b9452f5199535eff61f57e")
    }
    static func live() -> AppItem {
        AppItem(key: "live", title: "MaTV Live", about: "Lecteur vidéo pour tes abonnements",
                package: "io.matv.iptv", pin: "a8af55f531d524d1f9ec214dde7ac8097f9b56f0b19a38b87dbb1eb1f0be756f")
    }
}

enum Release {
    static let latestURL = URL(string: "https://api.github.com/repos/Torkor29/MaTVXiao/releases/latest")!
    static let assetNames = ["home": "MaTV-Home.apk", "live": "MaTV-Live.apk"]

    /// Fills each item's latest version, address, size and SHA-256 from the release.
    static func fill(_ items: inout [AppItem]) async throws {
        var req = URLRequest(url: latestURL, timeoutInterval: 20)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("MaTV-iOS", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code == 403 { throw AdbClient.Failure.message("GitHub limite les requêtes : réessaie dans quelques minutes.") }
        guard code == 200, let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AdbClient.Failure.message("GitHub ne répond pas (\(code)).")
        }
        let version = (json["tag_name"] as? String ?? "").replacingOccurrences(of: "^v", with: "", options: .regularExpression)
        let assets = json["assets"] as? [[String: Any]] ?? []
        for i in items.indices {
            guard let want = assetNames[items[i].key], let a = assets.first(where: { $0["name"] as? String == want }) else { continue }
            items[i].version = version
            items[i].url = a["browser_download_url"] as? String ?? ""
            items[i].size = (a["size"] as? NSNumber)?.int64Value ?? 0
            items[i].digest = (a["digest"] as? String ?? "").replacingOccurrences(of: "sha256:", with: "")
        }
        if items.contains(where: { $0.url.isEmpty }) { throw AdbClient.Failure.message("La dernière version de MaTV est incomplète.") }
    }

    /// Downloads, then checks the file is GitHub's (SHA-256) and signed with MaTV's key, before it may go to the TV.
    static func download(_ item: AppItem, progress: @escaping (Int64, Int64) -> Void) async throws -> URL {
        guard let url = URL(string: item.url) else { throw AdbClient.Failure.message("Adresse invalide.") }
        let file = try await Downloader(progress: progress, expected: item.size).run(url)
        let data = try Data(contentsOf: file, options: .alwaysMapped)
        if !item.digest.isEmpty {
            let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard sha == item.digest.lowercased() else { throw AdbClient.Failure.message("Fichier \(item.title) abîmé : réessaie.") }
        }
        guard let cert = ApkCert.firstCertificate(data) else { throw AdbClient.Failure.message("Fichier \(item.title) illisible.") }
        let pin = SHA256.hash(data: cert).map { String(format: "%02x", $0) }.joined()
        guard pin == item.pin else { throw AdbClient.Failure.message("\(item.title) n'est pas signé avec la clé de MaTV : installation annulée.") }
        return file
    }

    /// "2.10.0" > "2.9.1", number by number.
    static func compare(_ a: String, _ b: String) -> Int {
        func parts(_ s: String) -> [Int] { s.split(separator: ".").map { Int($0.filter(\.isNumber)) ?? 0 } }
        let x = parts(a), y = parts(b)
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p < q ? -1 : 1 }
        }
        return 0
    }
}

/// A download with progress, into the caches folder.
private final class Downloader: NSObject, URLSessionDownloadDelegate {
    private let progress: (Int64, Int64) -> Void
    private let expected: Int64
    private var continuation: CheckedContinuation<URL, Error>?
    private var result: URL?

    init(progress: @escaping (Int64, Int64) -> Void, expected: Int64) {
        self.progress = progress
        self.expected = expected
    }

    func run(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { c in
            continuation = c
            let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
            session.downloadTask(with: url).resume()
            session.finishTasksAndInvalidate()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        progress(totalBytesWritten, totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : expected)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".apk")
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            result = dest
        } catch {
            result = nil
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let code = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        if let error = error {
            continuation?.resume(throwing: error)
        } else if code != 200 || result == nil {
            continuation?.resume(throwing: AdbClient.Failure.message("Téléchargement refusé (\(code))."))
        } else {
            continuation?.resume(returning: result!)
        }
        continuation = nil
    }
}

/// The signing certificate of an APK, read from its APK Signing Block (signature scheme v2 or v3).
enum ApkCert {
    static func firstCertificate(_ d: Data) -> Data? {
        let b = [UInt8](d)
        func u32(_ i: Int) -> Int { Int(b[i]) | Int(b[i + 1]) << 8 | Int(b[i + 2]) << 16 | Int(b[i + 3]) << 24 }
        func u64(_ i: Int) -> Int { u32(i) | u32(i + 4) << 32 }
        // End of central directory, then the central directory's offset.
        var eocd = -1
        var i = b.count - 22
        while i >= max(0, b.count - 22 - 65_535) {
            if b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x05 && b[i + 3] == 0x06 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { return nil }
        let cd = u32(eocd + 16)
        guard cd >= 32, cd <= b.count, String(decoding: b[(cd - 16)..<cd], as: UTF8.self) == "APK Sig Block 42" else { return nil }
        let size = u64(cd - 24)
        let start = cd - size - 8
        guard start >= 0 else { return nil }
        var p = start + 8
        let end = cd - 24
        while p + 12 <= end {
            let len = u64(p), id = UInt32(truncatingIfNeeded: u32(p + 8))
            let value = p + 12, valueEnd = p + 8 + len
            if valueEnd > end || len < 4 { return nil }
            if id == 0x7109871a || id == 0xf05368c0 {
                // signers → first signer → signed data → digests (skipped) → certificates → first certificate
                var q = value + 4             // the signers sequence's length
                q += 4                        // the first signer's length
                q += 4                        // its signed data's length
                let digests = u32(q); q += 4 + digests
                q += 4                        // the certificates sequence's length
                let certLen = u32(q); q += 4
                guard q + certLen <= valueEnd else { return nil }
                return Data(b[q..<(q + certLen)])
            }
            p = valueEnd
        }
        return nil
    }
}
