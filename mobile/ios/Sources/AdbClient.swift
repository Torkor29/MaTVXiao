import Foundation
import Security

/// ADB over Wi-Fi, from the iPhone to a TV (port 5555, open while the TV's USB debugging is on). The same protocol as
/// the computer's adb: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
/// The first time, the TV asks "Allow USB debugging?" for this phone's key; "Always allow" remembers it.
/// Blocking calls on a plain socket: run them off the main thread.
final class AdbClient {
    enum Failure: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
    }

    static let CNXN: UInt32 = 0x4e584e43, AUTH: UInt32 = 0x48545541, OPEN: UInt32 = 0x4e45504f, OKAY: UInt32 = 0x59414b4f,
               CLSE: UInt32 = 0x45534c43, WRTE: UInt32 = 0x45545257, STLS: UInt32 = 0x534c5453
    private static let version: UInt32 = 0x01000001, maxPayload: UInt32 = 256 * 1024
    private static let authToken: UInt32 = 1, authSignature: UInt32 = 2, authPublicKey: UInt32 = 3

    private struct Message { var command: UInt32; var arg0: UInt32; var arg1: UInt32; var data: Data }

    private let socket: Socket
    private var nextLocalId: UInt32 = 1
    private var maxData = 4096

    private init(socket: Socket) { self.socket = socket }

    deinit { socket.close() }

    func close() { socket.close() }

    /// Connects and signs in. If the TV doesn't know this phone yet, calls `waiting` and gives the TV up to
    /// `approvalSeconds` for "Allow".
    static func connect(host: String, port: UInt16 = 5555, keyName: String, approvalSeconds: Double = 120,
                        waiting: (() -> Void)? = nil) throws -> AdbClient {
        let keys = try AdbKeys.load()
        let adb = try open(host: host, port: port, timeout: 4)
        do {
            try adb.hello()
            var signed = false
            while true {
                let m: Message
                do { m = try adb.read() } catch { throw Failure.message("La TV ne répond pas.") }
                if m.command == CNXN { adb.connected(m); return adb }
                if m.command == STLS {
                    throw Failure.message("Cette TV utilise le débogage sans fil avec code d'appairage. Active plutôt « Débogage USB » dans les options pour les développeurs.")
                }
                guard m.command == AUTH, m.arg0 == authToken else { throw Failure.message("Réponse inattendue de la TV.") }
                if !signed {
                    try adb.send(AUTH, authSignature, 0, try keys.sign(m.data))
                    signed = true
                    continue
                }
                // Unknown key: offer it; the TV shows "Allow USB debugging?".
                try adb.send(AUTH, authPublicKey, 0, keys.publicKeyBlob(name: keyName))
                waiting?()
                let until = Date().addingTimeInterval(approvalSeconds)
                adb.socket.timeout = 2
                while true {
                    do {
                        let r = try adb.read()
                        if r.command == CNXN { adb.connected(r); return adb }
                        throw Failure.message("La TV a refusé ce téléphone.")
                    } catch Socket.Failure.timeout {
                        // Android 8 to 10 answer on another connection: one that only signs gets in once allowed.
                        if Date() > until { throw Failure.message("La TV n'a pas autorisé ce téléphone.") }
                        if let fresh = signedIn(host: host, port: port, keys: keys) { adb.close(); return fresh }
                    } catch Socket.Failure.closed {
                        for _ in 0..<3 {
                            if let fresh = signedIn(host: host, port: port, keys: keys) { adb.close(); return fresh }
                            Thread.sleep(forTimeInterval: 2)
                        }
                        throw Failure.message("La TV a refusé ce téléphone.")
                    }
                }
            }
        } catch {
            adb.close()
            throw error
        }
    }

    private static func open(host: String, port: UInt16, timeout: Double) throws -> AdbClient {
        do {
            let s = try Socket.connect(host: host, port: port, timeout: timeout)
            s.timeout = 10
            return AdbClient(socket: s)
        } catch {
            throw Failure.message("Débogage USB désactivé sur la TV (port \(port) fermé).")
        }
    }

    private func hello() throws {
        try send(Self.CNXN, Self.version, Self.maxPayload, Data("host::\0".utf8))
    }

    private func connected(_ cnxn: Message) {
        maxData = max(1024, min(Int(cnxn.arg1), Int(Self.maxPayload)))
        socket.timeout = 30
    }

    /// A new connection that only signs (never offers the key, so no second prompt); nil while the TV doesn't know it.
    private static func signedIn(host: String, port: UInt16, keys: AdbKeys) -> AdbClient? {
        guard let adb = try? open(host: host, port: port, timeout: 2) else { return nil }
        adb.socket.timeout = 4
        do {
            try adb.hello()
            var m = try adb.read()
            if m.command == AUTH && m.arg0 == authToken {
                try adb.send(AUTH, authSignature, 0, try keys.sign(m.data))
                m = try adb.read()
            }
            if m.command == CNXN { adb.connected(m); return adb }
        } catch {}
        adb.close()
        return nil
    }

    /// Runs a command on the TV and returns what it printed.
    func shell(_ command: String) throws -> String {
        try exec("shell:" + command, body: nil, size: 0, progress: nil)
    }

    /// Installs an APK by streaming it to `cmd package install -S` (Android 7+). Returns the TV's answer ("Success",
    /// or why it refused). Play Protect may ask on the TV meanwhile, so the answer can take a while.
    func install(file: URL, progress: ((Int64, Int64) -> Void)?) throws -> String {
        let size = (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        let old = socket.timeout
        socket.timeout = 240
        defer { socket.timeout = old }
        return try exec("exec:cmd package install -r -S \(size)", body: handle, size: size, progress: progress)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func exec(_ service: String, body: FileHandle?, size: Int64, progress: ((Int64, Int64) -> Void)?) throws -> String {
        let local = nextLocalId
        nextLocalId += 1
        try send(Self.OPEN, local, 0, Data((service + "\0").utf8))
        var result = Data()
        var remote: UInt32 = 0
        while remote == 0 {
            let m = try read()
            if m.arg1 != local { continue }
            if m.command == Self.OKAY { remote = m.arg0 }
            else if m.command == Self.CLSE { throw Failure.message("La TV a refusé la commande.") }
        }
        if let body = body {
            let chunk = min(maxData, 64 * 1024)
            var sent: Int64 = 0
            while sent < size {
                let want = Int(min(Int64(chunk), size - sent))
                guard let piece = try body.read(upToCount: want), !piece.isEmpty else { throw Failure.message("Fichier incomplet.") }
                try send(Self.WRTE, local, remote, piece)
                sent += Int64(piece.count)
                progress?(sent, size)
                // One write at a time: the TV says OKAY before the next (and may answer early, on an error).
                var acked = false
                while !acked {
                    let m = try read()
                    if m.arg1 != local { continue }
                    if m.command == Self.OKAY { acked = true }
                    else if m.command == Self.WRTE { result.append(m.data); try send(Self.OKAY, local, remote, nil) }
                    else if m.command == Self.CLSE { try send(Self.CLSE, local, remote, nil); return String(decoding: result, as: UTF8.self) }
                }
            }
        }
        while true {
            let m = try read()
            if m.arg1 != local { continue }
            if m.command == Self.WRTE {
                result.append(m.data)
                try send(Self.OKAY, local, remote, nil)
            } else if m.command == Self.CLSE {
                try send(Self.CLSE, local, remote, nil)
                return String(decoding: result, as: UTF8.self)
            }
        }
    }

    // MARK: - Messages

    private func read() throws -> Message {
        let h = try socket.read(exactly: 24)
        func u32(_ at: Int) -> UInt32 {
            UInt32(h[at]) | UInt32(h[at + 1]) << 8 | UInt32(h[at + 2]) << 16 | UInt32(h[at + 3]) << 24
        }
        let command = u32(0), arg0 = u32(4), arg1 = u32(8), length = u32(12), magic = u32(20)
        guard command ^ 0xffffffff == magic else { throw Failure.message("Message ADB invalide.") }
        guard length <= 1024 * 1024 else { throw Failure.message("Message ADB trop grand.") }
        let data = length > 0 ? try socket.read(exactly: Int(length)) : Data()
        return Message(command: command, arg0: arg0, arg1: arg1, data: data)
    }

    private func send(_ command: UInt32, _ arg0: UInt32, _ arg1: UInt32, _ data: Data?) throws {
        let payload = data ?? Data()
        var sum: UInt32 = 0
        for b in payload { sum &+= UInt32(b) }
        var out = Data(capacity: 24 + payload.count)
        for v in [command, arg0, arg1, UInt32(payload.count), sum, command ^ 0xffffffff] {
            withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) }
        }
        out.append(payload)
        try socket.write(out)
    }
}

/// This phone's ADB key: RSA 2048, kept in the Keychain (this device only).
struct AdbKeys {
    let privateKey: SecKey
    let publicKey: SecKey

    private static let account = "adbkey"
    private static let service = "io.matv.app.adb"

    static func load() throws -> AdbKeys {
        if let data = keychainData() ?? (try? Data(contentsOf: fileURL)), let key = SecKeyCreateWithData(data as CFData, [
            kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
        ] as CFDictionary, nil), let pub = SecKeyCopyPublicKey(key) {
            return AdbKeys(privateKey: key, publicKey: pub)
        }
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048] as CFDictionary, &error),
              let pub = SecKeyCopyPublicKey(key),
              let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw AdbClient.Failure.message("Impossible de créer la clé de ce téléphone.")
        }
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData] = data
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        if SecItemAdd(add as CFDictionary, nil) != errSecSuccess {
            // No Keychain (an unsigned build): the app's private files, so the TV still knows this phone next time.
            try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        return AdbKeys(privateKey: key, publicKey: pub)
    }

    private static var fileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("adbkey")
    }

    private static func keychainData() -> Data? {
        var out: CFTypeRef?
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account,
                                  kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    /// adbd treats the 20-byte token as a SHA-1 digest and checks a PKCS#1 v1.5 signature of it.
    func sign(_ token: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(privateKey, .rsaSignatureDigestPKCS1v15SHA1, token as CFData, &error) as Data? else {
            throw AdbClient.Failure.message("Signature impossible.")
        }
        return sig
    }

    /// The public key in adbd's format: its RSAPublicKey struct (little-endian words) in base64, then a name.
    func publicKeyBlob(name: String) -> Data {
        var error: Unmanaged<CFError>?
        let der = (SecKeyCopyExternalRepresentation(publicKey, &error) as Data?) ?? Data()
        let (modulus, exponent) = Self.parseRSAPublicKey(der)
        let n = BigWords(bigEndian: modulus, words: 64)
        // n0inv = -(n^-1) mod 2^32, by Newton's iteration on the lowest word.
        let n0 = n.words[0]
        var inv: UInt32 = 1
        for _ in 0..<5 { inv = inv &* (2 &- n0 &* inv) }
        let n0inv = 0 &- inv
        let rr = BigWords.powerOfTwoMod(4096, n)
        var blob = Data()
        func put(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { blob.append(contentsOf: $0) } }
        put(64)
        put(n0inv)
        n.words.forEach(put)
        rr.words.forEach(put)
        put(exponent)
        return Data((blob.base64EncodedString() + " " + name + "\0").utf8)
    }

    /// PKCS#1 RSAPublicKey: SEQUENCE { INTEGER modulus, INTEGER publicExponent }.
    static func parseRSAPublicKey(_ der: Data) -> (Data, UInt32) {
        let b = [UInt8](der)
        var i = 0
        func length() -> Int {
            var l = Int(b[i]); i += 1
            if l & 0x80 != 0 {
                let count = l & 0x7f
                l = 0
                for _ in 0..<count { l = (l << 8) | Int(b[i]); i += 1 }
            }
            return l
        }
        guard b.count > 10, b[0] == 0x30 else { return (Data(), 65537) }
        i = 1; _ = length()
        guard b[i] == 0x02 else { return (Data(), 65537) }
        i += 1
        let nl = length()
        var n = Array(b[i..<(i + nl)]); i += nl
        while n.first == 0 { n.removeFirst() }
        guard i < b.count, b[i] == 0x02 else { return (Data(n), 65537) }
        i += 1
        let el = length()
        var e: UInt32 = 0
        for k in 0..<el { e = (e << 8) | UInt32(b[i + k]) }
        return (Data(n), e)
    }
}

/// Just enough unsigned big-number arithmetic for adbd's key format (little-endian 32-bit words).
struct BigWords {
    var words: [UInt32]

    init(words: [UInt32]) { self.words = words }

    init(bigEndian bytes: Data, words count: Int) {
        var w = [UInt32](repeating: 0, count: count)
        let b = [UInt8](bytes)
        for (k, byte) in b.reversed().enumerated() where k / 4 < count {
            w[k / 4] |= UInt32(byte) << (8 * UInt32(k % 4))
        }
        words = w
    }

    /// 2^power mod n, by doubling and subtracting (n is 2048 bits, so each step needs at most one subtraction).
    static func powerOfTwoMod(_ power: Int, _ n: BigWords) -> BigWords {
        var r = BigWords(words: [UInt32](repeating: 0, count: n.words.count + 1))
        r.words[0] = 1
        let wide = BigWords(words: n.words + [0])
        for _ in 0..<power {
            var carry: UInt32 = 0
            for k in 0..<r.words.count {
                let v = r.words[k]
                r.words[k] = (v << 1) | carry
                carry = v >> 31
            }
            if !r.less(than: wide) { r.subtract(wide) }
        }
        return BigWords(words: Array(r.words.prefix(n.words.count)))
    }

    func less(than o: BigWords) -> Bool {
        for k in stride(from: words.count - 1, through: 0, by: -1) where words[k] != o.words[k] {
            return words[k] < o.words[k]
        }
        return false
    }

    mutating func subtract(_ o: BigWords) {
        var borrow: UInt64 = 0
        for k in 0..<words.count {
            let a = UInt64(words[k]), b = UInt64(o.words[k]) + borrow
            if a >= b { words[k] = UInt32(a - b); borrow = 0 }
            else { words[k] = UInt32(a + (1 << 32) - b); borrow = 1 }
        }
    }
}
