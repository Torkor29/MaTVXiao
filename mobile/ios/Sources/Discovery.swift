import Foundation
import Network
import Darwin

/// A TV on the Wi-Fi.
struct FoundTV: Identifiable, Equatable {
    var ip: String
    var name: String = ""
    var model: String = ""
    var ready = false        // port 5555 answers: its debugging is on
    var androidTV = false    // it announced the Android TV remote service (a Cast speaker doesn't)
    var id: String { ip }
    var title: String { !name.isEmpty ? name : (!model.isEmpty ? model : "TV Android") }
}

/// Finds the TVs on the phone's Wi-Fi, two ways at once:
///  - Bonjour: every Android TV / Google TV announces itself (_androidtvremote2._tcp, _googlecast._tcp) with its name,
///    even before debugging is on, so the list can say "Salon" and guide you;
///  - a scan of port 5555 on the /24: the TVs whose debugging is on.
final class Discovery {
    static let adbPort: UInt16 = 5555

    /// The phone's IPv4 address on Wi-Fi (en0), or nil.
    static func localAddress() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var found: String?
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if name == "en0" { return ip }
                if found == nil { found = ip }
            }
        }
        return found
    }

    private let queue = DispatchQueue(label: "matv.discovery", attributes: .concurrent)
    private let lock = NSLock()
    private var tvs: [String: FoundTV] = [:]
    private var browsers: [NWBrowser] = []
    private var cancelled = false
    private let onUpdate: (FoundTV) -> Void

    init(onUpdate: @escaping (FoundTV) -> Void) { self.onUpdate = onUpdate }

    /// Runs both searches for about `seconds`, reporting each TV as it shows up (on a background queue), then `done`.
    func start(from me: String, seconds: Double = 5, done: @escaping () -> Void) {
        browse("_androidtvremote2._tcp", androidTV: true)
        browse("_googlecast._tcp", androidTV: false)
        let prefix = me.split(separator: ".").prefix(3).joined(separator: ".") + "."
        let group = DispatchGroup()
        let gate = DispatchSemaphore(value: 48)
        for i in 1..<255 {
            let ip = prefix + String(i)
            if ip == me { continue }
            group.enter()
            queue.async {
                gate.wait()
                defer { gate.signal(); group.leave() }
                if self.isCancelled || !Socket.isOpen(host: ip, port: Self.adbPort, timeout: 0.6) { return }
                self.merge(ip) { $0.ready = true }
            }
        }
        queue.asyncAfter(deadline: .now() + seconds) {
            group.wait()
            self.stopBrowsing()
            done()
        }
    }

    func cancel() {
        lock.lock(); cancelled = true; lock.unlock()
        stopBrowsing()
    }

    private var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }

    private func stopBrowsing() {
        lock.lock(); let b = browsers; browsers = []; lock.unlock()
        b.forEach { $0.cancel() }
    }

    /// Adds what we learned about a TV and tells the screen (only TVs: an announce from a speaker is kept quiet).
    private func merge(_ ip: String, _ change: (inout FoundTV) -> Void) {
        lock.lock()
        var tv = tvs[ip] ?? FoundTV(ip: ip)
        let before = tv
        change(&tv)
        tvs[ip] = tv
        let cancelled = self.cancelled
        lock.unlock()
        if cancelled || tv == before || !(tv.androidTV || tv.ready) { return }
        onUpdate(tv)
    }

    private func browse(_ type: String, androidTV: Bool) {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: type, domain: "local."), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            for r in results { self?.resolve(r, androidTV: androidTV) }
        }
        browser.start(queue: queue)
        lock.lock(); browsers.append(browser); lock.unlock()
    }

    /// Bonjour gives a name; a short connection to the service gives its address.
    private func resolve(_ r: NWBrowser.Result, androidTV: Bool) {
        guard case let .service(name, _, _, _) = r.endpoint else { return }
        var friendly = "", model = ""
        if case let .bonjour(txt) = r.metadata {
            friendly = txt["fn"] ?? ""
            model = txt["md"] ?? ""
        }
        let c = NWConnection(to: r.endpoint, using: .tcp)
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if case let .hostPort(host, _)? = c.currentPath?.remoteEndpoint, case let .ipv4(v4) = host {
                    let ip = "\(v4)".components(separatedBy: "%").first ?? "\(v4)"
                    let open = Socket.isOpen(host: ip, port: Self.adbPort, timeout: 0.8)
                    self?.merge(ip) { tv in
                        if androidTV { tv.androidTV = true; if tv.name.isEmpty { tv.name = name } }
                        if !friendly.isEmpty { tv.name = friendly }
                        if !model.isEmpty && tv.model.isEmpty { tv.model = model }
                        if open { tv.ready = true }
                    }
                }
                c.cancel()
            case .failed, .waiting:
                c.cancel()
            default:
                break
            }
        }
        c.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 4) { c.cancel() }
    }
}
