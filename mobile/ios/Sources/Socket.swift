import Foundation
import Darwin

/// A blocking TCP socket to an IPv4 address on the local network, with timeouts. iOS asks once for the "local
/// network" permission the first time one connects (NSLocalNetworkUsageDescription).
final class Socket {
    enum Failure: Error { case timeout, closed, refused, system(Int32) }

    private var fd: Int32
    private let lock = NSLock()

    /// Seconds a read may wait.
    var timeout: Double = 10 {
        didSet { applyTimeout() }
    }

    private init(fd: Int32) {
        self.fd = fd
        applyTimeout()
    }

    deinit { close() }

    static func connect(host: String, port: UInt16, timeout: Double) throws -> Socket {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { throw Failure.refused }
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { throw Failure.system(errno) }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))   // a closed TV must not kill the app
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if r != 0 {
            guard errno == EINPROGRESS else { Darwin.close(fd); throw Failure.refused }
            var p = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            let ready = poll(&p, 1, Int32(timeout * 1000))
            guard ready > 0 else { Darwin.close(fd); throw ready == 0 ? Failure.timeout : Failure.refused }
            var err: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
            guard err == 0 else { Darwin.close(fd); throw Failure.refused }
        }
        _ = fcntl(fd, F_SETFL, flags & ~O_NONBLOCK)
        return Socket(fd: fd)
    }

    /// True when something answers on host:port within `timeout` seconds.
    static func isOpen(host: String, port: UInt16, timeout: Double) -> Bool {
        guard let s = try? connect(host: host, port: port, timeout: timeout) else { return false }
        s.close()
        return true
    }

    private func applyTimeout() {
        lock.lock(); defer { lock.unlock() }
        guard fd >= 0 else { return }
        var tv = timeval(tv_sec: Int(timeout), tv_usec: Int32((timeout - Double(Int(timeout))) * 1_000_000))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var send = timeval(tv_sec: 60, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &send, socklen_t(MemoryLayout<timeval>.size))
    }

    func read(exactly count: Int) throws -> Data {
        var out = Data(count: count)
        var got = 0
        while got < count {
            let n = out.withUnsafeMutableBytes { buf -> Int in
                Darwin.recv(fd, buf.baseAddress! + got, count - got, 0)
            }
            if n > 0 { got += n; continue }
            if n == 0 { throw Failure.closed }
            if errno == EAGAIN || errno == EWOULDBLOCK { throw Failure.timeout }
            if errno == EINTR { continue }
            throw Failure.closed
        }
        return out
    }

    func write(_ data: Data) throws {
        var sent = 0
        while sent < data.count {
            let n = data.withUnsafeBytes { buf -> Int in
                Darwin.send(fd, buf.baseAddress! + sent, data.count - sent, 0)
            }
            if n > 0 { sent += n; continue }
            if n < 0 && errno == EINTR { continue }
            throw Failure.closed
        }
    }

    func close() {
        lock.lock(); defer { lock.unlock() }
        if fd >= 0 { Darwin.close(fd); fd = -1 }
    }
}
