// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "ScreenKitCore",
    platforms: [.macOS(.v13), .iOS(.v17)],
    products: [.library(name: "ScreenKitCore", targets: ["ScreenKitCore"])],
    targets: [.target(name: "ScreenKitCore"), .testTarget(name: "ScreenKitCoreTests", dependencies: ["ScreenKitCore"])],
    swiftLanguageModes: [.v5]
)
