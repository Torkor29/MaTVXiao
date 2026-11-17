import Foundation
import SwiftUI
import UIKit

/// A TV this phone pilots: MaTV Connect's address and secret (got over ADB, never typed).
struct SavedTV: Codable, Identifiable, Equatable {
    var key: String, name: String, ip: String, port: Int, token: String
    var id: String { key }
    var url: URL { URL(string: "http://\(ip):\(port)/#k=\(token)")! }
}

enum Screen: Equatable {
    case list, guide, install
    case control(name: String, url: URL)
}

enum Phase: String { case connecting, approve, reading, choose, installing, done, error }

/// Everything the screens show, and the work behind them. Talking to the TV happens on one serial queue.
@MainActor
final class AppModel: ObservableObject {
    @Published var screen: Screen = .list
    @Published var found: [FoundTV] = []
    @Published var scanning = false
    @Published var saved: [SavedTV] = []
    @Published var online: [String: Bool] = [:]
    @Published var localIP: String? = Discovery.localAddress()
    @Published var scannedOnce = false

    @Published var tv = FoundTV(ip: "")
    @Published var phase: Phase = .connecting
    @Published var error = ""
    @Published var tvInfo = ""
    @Published var items: [AppItem] = [AppItem.home(), AppItem.live()]
    @Published var playProtectHint = false
    @Published var pairing = false
    @Published var tvReady = false
    @Published var toast: String?

    private var discovery: Discovery?
    private var adb: AdbClient?
    private var serial = ""
    private var session = UUID()
    private let adbQueue = DispatchQueue(label: "matv.adb")
    private let keyName = "matv@" + UIDevice.current.name.replacingOccurrences(of: " ", with: "-")

    init() {
        if let d = UserDefaults.standard.data(forKey: "saved"), let s = try? JSONDecoder().decode([SavedTV].self, from: d) { saved = s }
    }

    // MARK: - TVs of the network

    /// Set by the screenshot presets: no real scan then.
    private var frozen = false

    func scan() {
        guard !scanning, !frozen else { return }
        localIP = Discovery.localAddress()
        guard let me = localIP else { return }
        scanning = true
        found = []
        discovery?.cancel()
        let d = Discovery { [weak self] tv in
            Task { @MainActor in
                guard let self = self else { return }
                if let i = self.found.firstIndex(where: { $0.ip == tv.ip }) { self.found[i] = tv } else { self.found.append(tv) }
            }
        }
        discovery = d
        for s in saved {
            DispatchQueue.global().async {
                let on = Socket.isOpen(host: s.ip, port: UInt16(s.port), timeout: 1.2)
                Task { @MainActor in self.online[s.ip] = on }
            }
        }
        d.start(from: me) {
            Task { @MainActor in
                self.scanning = false
                self.scannedOnce = true
            }
        }
    }

    var freshTVs: [FoundTV] { found.filter { f in !saved.contains(where: { $0.ip == f.ip }) } }

    func open(_ t: FoundTV) {
        discovery?.cancel()
        scanning = false
        tv = t
        if t.ready { startInstall() } else { showGuide() }
    }

    func openAddress(_ ip: String) {
        let ip = ip.trimmingCharacters(in: .whitespaces)
        guard ip.range(of: #"^\d{1,3}(\.\d{1,3}){3}$"#, options: .regularExpression) != nil else { return }
        DispatchQueue.global().async {
            let ready = Socket.isOpen(host: ip, port: Discovery.adbPort, timeout: 1.5)
            Task { @MainActor in
                var t = self.found.first(where: { $0.ip == ip }) ?? FoundTV(ip: ip)
                t.ready = ready
                self.open(t)
            }
        }
    }

    func back() {
        if screen == .install && phase == .installing { return }
        endSession()
        screen = .list
        if !scannedOnce || found.isEmpty { scan() }
    }

    // MARK: - Once per TV: debugging

    private func showGuide() {
        let mine = newSession()
        tvReady = false
        screen = .guide
        let ip = tv.ip
        Task {
            while session == mine {
                let open = await Task.detached { Socket.isOpen(host: ip, port: Discovery.adbPort, timeout: 1.2) }.value
                guard session == mine else { return }
                if open {
                    tvReady = true
                    tv.ready = true
                    try? await Task.sleep(nanoseconds: 700_000_000)
                    if session == mine { startInstall() }
                    return
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    // MARK: - Install, update, pair

    func startInstall() {
        let mine = newSession()
        screen = .install
        phase = .connecting
        error = ""
        tvInfo = ""
        playProtectHint = false
        pairing = false
        items = [AppItem.home(), AppItem.live()]
        let ip = tv.ip, name = keyName
        Task {
            do {
                let a = try await onAdb {
                    try AdbClient.connect(host: ip, keyName: name) {
                        Task { @MainActor in if self.session == mine { self.phase = .approve } }
                    }
                }
                guard session == mine else { a.close(); return }
                adb = a
                phase = .reading
                let (info, serial, home, live) = try await onAdb { () -> (String, String, String, String) in
                    let brand = try a.shell("getprop ro.product.brand").trimmingCharacters(in: .whitespacesAndNewlines)
                    let model = try a.shell("getprop ro.product.model").trimmingCharacters(in: .whitespacesAndNewlines)
                    let android = try a.shell("getprop ro.build.version.release").trimmingCharacters(in: .whitespacesAndNewlines)
                    let serial = try a.shell("getprop ro.serialno").trimmingCharacters(in: .whitespacesAndNewlines)
                    return ("\(brand) \(model)".trimmingCharacters(in: .whitespaces) + ", Android " + android, serial,
                            try Self.version(a, "io.freemytv"), try Self.version(a, "io.matv.iptv"))
                }
                self.serial = serial.isEmpty ? ip : serial
                var list = items
                list[0].installed = home
                list[1].installed = live
                try await Release.fill(&list)
                for i in list.indices { list[i].wanted = !list[i].upToDate }
                guard session == mine else { return }
                items = list
                tvInfo = info
                phase = .choose
            } catch {
                guard session == mine else { return }
                self.error = error.localizedDescription
                phase = .error
            }
        }
    }

    func toggle(_ item: AppItem) {
        guard phase == .choose, let i = items.firstIndex(where: { $0.key == item.key }), !items[i].url.isEmpty else { return }
        items[i].wanted.toggle()
    }

    func runInstall() {
        guard let a = adb else { return }
        let mine = session
        phase = .installing
        Task {
            var ok = 0
            for i in items.indices where items[i].wanted {
                guard session == mine else { return }
                do {
                    items[i].status = "Téléchargement…"
                    items[i].fraction = 0
                    let file = try await Release.download(items[i]) { done, total in
                        Task { @MainActor in
                            guard total > 0 else { return }
                            self.items[i].status = "Téléchargement \(Int(done * 100 / total)) %"
                            self.items[i].fraction = Double(done) / Double(total) * 0.45
                        }
                    }
                    items[i].status = "Envoi à la TV…"
                    items[i].fraction = 0.45
                    let isTCL = tvInfo.lowercased().hasPrefix("tcl")
                    let answer = try await onAdb { () -> String in
                        defer { try? FileManager.default.removeItem(at: file) }
                        let r = try a.install(file: file) { done, total in
                            Task { @MainActor in
                                if done < total {
                                    self.items[i].status = "Envoi à la TV \(Int(done * 100 / max(total, 1))) %"
                                    self.items[i].fraction = 0.45 + Double(done) / Double(max(total, 1)) * 0.5
                                } else {
                                    self.items[i].status = "Installation sur la TV…"
                                    self.items[i].fraction = 0.97
                                    self.playProtectHint = true
                                }
                            }
                        }
                        if r.contains("Success") && isTCL && self.isHome(i) {
                            _ = try? a.shell("appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow")
                        }
                        return r
                    }
                    guard answer.contains("Success") else { throw AdbClient.Failure.message(Self.explain(answer)) }
                    items[i].installed = items[i].version
                    items[i].done = true
                    items[i].status = "Installé"
                    items[i].fraction = 1
                    ok += 1
                } catch {
                    items[i].failed = true
                    items[i].status = error.localizedDescription
                }
            }
            guard session == mine else { return }
            playProtectHint = false
            let failed = items.contains { $0.wanted && $0.failed }
            phase = failed && ok == 0 ? .choose : .done
            for i in items.indices where items[i].done { items[i].wanted = false }
        }
    }

    nonisolated private func isHome(_ i: Int) -> Bool { i == 0 }

    /// Asks MaTV on the TV for MaTV Connect's secret (over ADB: no QR code), saves the TV, opens its control.
    func pairAndControl() {
        guard let a = adb, !pairing else { return }
        pairing = true
        let name = tv.title, ip = tv.ip, key = serial.isEmpty ? tv.ip : serial
        Task {
            let answer = (try? await onAdb { try a.shell("am broadcast -n io.freemytv/.PairReceiver") }) ?? ""
            pairing = false
            guard let m = answer.range(of: #"matv-connect ([A-Za-z0-9_-]{20,}) (\d+)"#, options: .regularExpression) else {
                toast = "MaTV sur la TV est trop ancienne pour être pilotée : mets-la à jour."
                return
            }
            let parts = answer[m].split(separator: " ")
            let s = SavedTV(key: key, name: name, ip: ip, port: Int(parts[2]) ?? 8765, token: String(parts[1]))
            save(s)
            endSession()
            screen = .control(name: name, url: s.url)
        }
    }

    func openOnTV() {
        guard let a = adb else { return }
        Task {
            do {
                _ = try await onAdb { () -> String in
                    _ = try a.shell("input keyevent KEYCODE_WAKEUP")
                    return try a.shell("am start -n io.freemytv/.MaTVSettingsActivity --es open home_mode")
                }
                toast = "C'est ouvert sur la TV."
            } catch {
                toast = "La TV ne répond plus : ouvre l'appli MaTV avec la télécommande."
            }
        }
    }

    func control(_ s: SavedTV) {
        discovery?.cancel()
        scanning = false
        screen = .control(name: s.name, url: s.url)
    }

    func demo() {
        discovery?.cancel()
        scanning = false
        guard let file = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "connect"),
              let url = URL(string: file.absoluteString + "#demo") else { return }
        screen = .control(name: "Démonstration", url: url)
    }

    func reinstall(_ s: SavedTV) {
        var t = FoundTV(ip: s.ip)
        t.name = s.name
        t.ready = true
        open(t)
    }

    // MARK: - Saved TVs

    func save(_ s: SavedTV) {
        saved.removeAll { $0.key == s.key || $0.ip == s.ip }
        saved.insert(s, at: 0)
        persist()
    }

    func forget(_ s: SavedTV) {
        saved.removeAll { $0.key == s.key }
        persist()
    }

    private func persist() {
        if let d = try? JSONEncoder().encode(saved) { UserDefaults.standard.set(d, forKey: "saved") }
    }

    // MARK: - Helpers

    private func newSession() -> UUID {
        endSession()
        session = UUID()
        return session
    }

    private func endSession() {
        session = UUID()
        if let a = adb { adbQueue.async { a.close() } }
        adb = nil
        pairing = false
    }

    /// Runs blocking ADB work on its own queue.
    private func onAdb<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { c in
            adbQueue.async {
                do { c.resume(returning: try work()) } catch { c.resume(throwing: error) }
            }
        }
    }

    nonisolated private static func version(_ a: AdbClient, _ pkg: String) throws -> String {
        let out = try a.shell("dumpsys package \(pkg) | grep -m1 versionName")
        guard let r = out.range(of: "versionName=") else { return "" }
        return out[r.upperBound...].split(whereSeparator: { $0.isWhitespace }).first.map(String.init) ?? ""
    }

    /// The TV's refusal in words (pm's codes are not for people).
    static func explain(_ answer: String) -> String {
        if answer.contains("UPDATE_INCOMPATIBLE") { return "Une autre version de cette appli, signée autrement, est déjà sur la TV : désinstalle-la, puis réessaie." }
        if answer.contains("INSUFFICIENT_STORAGE") { return "Plus assez de place sur la TV." }
        if answer.contains("VERIFICATION") || answer.contains("USER_RESTRICTED") || answer.contains("ABORTED") {
            return "Installation refusée sur la TV (Play Protect). Réessaie et choisis « Installer quand même »."
        }
        if answer.contains("OLDER_SDK") { return "Cette TV est trop ancienne pour cette appli." }
        let a = answer.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        return a.isEmpty ? "La TV n'a pas répondu." : "La TV a refusé : " + String(a.prefix(160))
    }

    // MARK: - Screenshots (UI tests: -shot <name>), with made-up TVs and no network

    func preset(_ shot: String) {
        frozen = true
        discovery?.cancel()
        scanning = false
        localIP = "192.168.1.23"
        scannedOnce = true
        saved = [SavedTV(key: "S1", name: "TV du salon", ip: "192.168.1.189", port: 8765, token: "token_abcdefghijklmnopqrstu")]
        online = ["192.168.1.189": true]
        var chambre = FoundTV(ip: "192.168.1.57"); chambre.name = "Chambre"; chambre.model = "Xiaomi TV A Pro 55"; chambre.androidTV = true
        var bureau = FoundTV(ip: "192.168.1.80"); bureau.name = "Bureau"; bureau.ready = true; bureau.androidTV = true
        found = [chambre, bureau]
        tvInfo = "Xiaomi MiTV-MOOR2, Android 11"
        items = [AppItem.home(), AppItem.live()]
        items[0].installed = "2.9.0"; items[0].version = "2.10.0"; items[0].url = "x"; items[0].wanted = true
        items[1].version = "2.10.0"; items[1].url = "x"; items[1].wanted = true
        switch shot {
        case "guide": tv = chambre; screen = .guide
        case "approve": tv = bureau; phase = .approve; screen = .install
        case "choose": tv = bureau; phase = .choose; screen = .install
        case "installing":
            tv = bureau; phase = .installing; screen = .install; playProtectHint = true
            items[0].status = "Envoi à la TV 62 %"; items[0].fraction = 0.76
            items[1].status = "Téléchargement 40 %"; items[1].fraction = 0.18
        case "done":
            tv = bureau; phase = .done; screen = .install
            for i in items.indices { items[i].installed = "2.10.0"; items[i].done = true; items[i].wanted = false; items[i].status = "Installé" }
        case "demo": demo()
        default: screen = .list
        }
    }
}
