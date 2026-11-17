import SwiftUI
import WebKit

/// Piloting a TV: MaTV Connect, the web app the TV itself serves (remote, wallpapers, suggestions), in the app.
/// The demo runs the same web app from the app's bundle, with a TV that lives in the page.
struct ControlScreen: View {
    @EnvironmentObject var m: AppModel
    let name: String
    let url: URL

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { m.back() } label: {
                    Text("‹  Mes TV").font(.system(size: 16, weight: .bold)).foregroundStyle(Palette.ink).padding(.vertical, 10)
                }
                Spacer()
                Text(name).font(.system(size: 15)).foregroundStyle(Palette.dim).lineLimit(1)
            }
            .padding(.horizontal, 18).frame(height: 48)
            WebView(url: url).ignoresSafeArea(edges: .bottom)
        }
        .background(Palette.night.ignoresSafeArea())
    }
}

struct WebView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(home: url) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        let web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 11 / 255, green: 16 / 255, blue: 32 / 255, alpha: 1)
        web.scrollView.backgroundColor = web.backgroundColor
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = false
        if url.isFileURL {
            web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        } else {
            web.load(URLRequest(url: url))
        }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let home: URL
        init(home: URL) { self.home = home }

        /// Links that leave the TV (Wikimedia's pages) open in Safari.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let u = action.request.url else { return decisionHandler(.allow) }
            let same = home.isFileURL ? u.isFileURL : u.host == home.host
            if same || u.scheme == "about" || u.scheme == "blob" || u.scheme == "data" { return decisionHandler(.allow) }
            if action.navigationType == .linkActivated || action.targetFrame == nil {
                UIApplication.shared.open(u)
                return decisionHandler(.cancel)
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let u = action.request.url { UIApplication.shared.open(u) }
            return nil
        }

        // The web app asks with alert(), confirm() and prompt() (delete, rename): WKWebView shows none of them itself.
        func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping () -> Void) {
            let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
            present(a, from: webView) ?? completionHandler()
        }

        func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo,
                     completionHandler: @escaping (Bool) -> Void) {
            let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "Annuler", style: .cancel) { _ in completionHandler(false) })
            a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
            present(a, from: webView) ?? completionHandler(false)
        }

        func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                     initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
            let a = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
            a.addTextField { $0.text = defaultText }
            a.addAction(UIAlertAction(title: "Annuler", style: .cancel) { _ in completionHandler(nil) })
            a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(a.textFields?.first?.text) })
            present(a, from: webView) ?? completionHandler(nil)
        }

        /// Presents from the view controller on top; nil when there is none.
        private func present(_ a: UIAlertController, from view: UIView) -> Void? {
            var top = view.window?.rootViewController
            while let next = top?.presentedViewController { top = next }
            guard let vc = top else { return nil }
            vc.present(a, animated: true)
            return ()
        }
    }
}
