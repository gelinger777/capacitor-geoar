import Foundation
import Capacitor
import WebKit

@objc(GeoscanPlugin)
public class GeoscanPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "GeoscanPlugin"
    public let jsName = "Geoscan"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkCapabilities", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseSession", returnType: CAPPluginReturnPromise)
    ]

    private var implementation: Geoscan?
    private var lastStart: Geoscan.StartResult?

    override public func load() {
        guard let bridge = self.bridge,
              let viewController = bridge.viewController,
              let webView = bridge.webView as? WKWebView else { return }

        implementation = Geoscan(hostView: viewController.view, webView: webView)
        implementation?.orientationHandler = { [weak self] heading, pitch, roll, accuracy, ts in
            self?.notifyListeners("orientation", data: [
                "heading": heading,
                "pitch": pitch,
                "roll": roll,
                "accuracy": accuracy,
                "timestamp": ts
            ])
        }
    }

    @objc func checkCapabilities(_ call: CAPPluginCall) {
        guard let impl = implementation else {
            call.reject("not_initialized")
            return
        }
        let caps = impl.checkCapabilities()
        let cameraPerm = impl.cameraPermissionState()
        let locationPerm = impl.locationPermissionState()

        let reason = firstBlocker(caps: caps, cameraPerm: cameraPerm, locationPerm: locationPerm)
        var result: [String: Any] = [
            "ready": reason == nil,
            "camera": [
                "hardware": caps.cameraHardware,
                "permission": cameraPerm
            ],
            "location": [
                "hardware": caps.locationHardware,
                "serviceEnabled": caps.locationServiceEnabled,
                "permission": locationPerm
            ],
            "accelerometer": caps.accelerometer,
            "gyroscope": caps.gyroscope,
            "magnetometer": caps.magnetometer,
            "fusedOrientation": caps.fusedOrientation,
            "trueHeading": caps.trueHeading
        ]
        if let reason = reason { result["reason"] = reason }
        call.resolve(result as PluginCallResultData)
    }

    @objc func startSession(_ call: CAPPluginCall) {
        guard let impl = implementation else {
            call.reject("not_initialized")
            return
        }
        if impl.isSessionActive, let r = lastStart {
            call.resolve(startResultToJs(r))
            return
        }

        var config = Geoscan.StartConfig()
        if let hz = call.getInt("orientationHz") { config.orientationHz = hz }
        if let cam = call.getString("camera") { config.camera = cam }
        if let t = call.getBool("transparentWebView") { config.transparentWebView = t }

        impl.start(config: config) { [weak self] result in
            switch result {
            case .success(let r):
                self?.lastStart = r
                call.resolve(self?.startResultToJs(r) ?? [:])
            case .failure(let e):
                call.reject(e.localizedDescription, "\(e.code)", e)
            }
        }
    }

    @objc func stopSession(_ call: CAPPluginCall) {
        implementation?.stop()
        lastStart = nil
        call.resolve()
    }

    @objc func pauseSession(_ call: CAPPluginCall) {
        implementation?.pause()
        call.resolve()
    }

    private func startResultToJs(_ r: Geoscan.StartResult) -> PluginCallResultData {
        return [
            "horizontalFovDeg": r.horizontalFovDeg,
            "verticalFovDeg": r.verticalFovDeg,
            "orientationHz": r.orientationHz
        ]
    }

    private func firstBlocker(caps: Geoscan.Capabilities,
                              cameraPerm: String,
                              locationPerm: String) -> String? {
        if !caps.cameraHardware { return "no_camera" }
        if !caps.accelerometer { return "no_accelerometer" }
        if !caps.gyroscope { return "no_gyroscope" }
        if !caps.magnetometer { return "no_magnetometer" }
        if !caps.fusedOrientation { return "no_fused_orientation" }
        if !caps.locationServiceEnabled { return "gps_disabled" }
        if cameraPerm == "denied" || locationPerm == "denied" { return "permission_denied" }
        return nil
    }
}
