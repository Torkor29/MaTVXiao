import SwiftUI

@main
struct MaTVApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onAppear {
                    // Screenshots for the App Store come from UI tests: -shot <screen>, with made-up TVs.
                    let args = ProcessInfo.processInfo.arguments
                    if let i = args.firstIndex(of: "-shot"), i + 1 < args.count { model.preset(args[i + 1]) }
                }
        }
    }
}
