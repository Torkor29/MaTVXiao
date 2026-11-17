import XCTest

/// The App Store screenshots: each screen with made-up TVs (-shot), plus the demo. The CI exports the attachments.
final class ShotsUITests: XCTestCase {
    func testScreenshots() throws {
        for shot in ["list", "guide", "approve", "choose", "installing", "done", "demo"] {
            let app = XCUIApplication()
            app.launchArguments = ["-shot", shot]
            app.launch()
            sleep(shot == "demo" ? 10 : 2)
            let a = XCTAttachment(screenshot: app.screenshot())
            a.name = shot
            a.lifetime = .keepAlways
            add(a)   // the next launch closes this one
        }
    }

    func testDemoOpensFromTheList() throws {
        let app = XCUIApplication()
        app.launch()
        let demo = app.buttons["demo"]
        XCTAssertTrue(demo.waitForExistence(timeout: 10))
        demo.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.webViews.staticTexts["Ma TV"].waitForExistence(timeout: 20))
    }
}
