import Foundation

@objc public class Geoscan: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
