import Foundation
import AVFoundation
import CoreMotion
import CoreLocation
import UIKit
import WebKit

@objc public class Geoscan: NSObject, CLLocationManagerDelegate {

    public struct Capabilities {
        public var cameraHardware: Bool = false
        public var locationHardware: Bool = true
        public var locationServiceEnabled: Bool = false
        public var accelerometer: Bool = false
        public var gyroscope: Bool = false
        public var magnetometer: Bool = false
        public var fusedOrientation: Bool = false
        public var trueHeading: Bool = false
    }

    public struct StartConfig {
        public var camera: String = "rear"
        public var orientationHz: Int = 30
        public var transparentWebView: Bool = true
    }

    public struct StartResult {
        public var horizontalFovDeg: Double = 60
        public var verticalFovDeg: Double = 45
        public var orientationHz: Int = 30
    }

    public typealias OrientationHandler = (_ heading: Double, _ pitch: Double, _ roll: Double, _ accuracy: String, _ timestamp: Int64) -> Void

    public var orientationHandler: OrientationHandler?

    private weak var hostView: UIView?
    private weak var webView: WKWebView?

    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var activeDevice: AVCaptureDevice?

    private let motionManager = CMMotionManager()
    private let locationManager = CLLocationManager()
    private var headingAvailable: Bool = false
    private var lastAccuracy: String = "unreliable"
    private var motionQueue = OperationQueue()

    private var originalWebViewBackgroundColor: UIColor?
    private var originalWebViewIsOpaque: Bool = true

    private(set) public var isSessionActive: Bool = false

    public init(hostView: UIView, webView: WKWebView) {
        self.hostView = hostView
        self.webView = webView
        super.init()
        motionQueue.qualityOfService = .userInteractive
        locationManager.delegate = self
    }

    // MARK: - Capabilities

    public func checkCapabilities() -> Capabilities {
        var caps = Capabilities()
        caps.cameraHardware = AVCaptureDevice.default(for: .video) != nil
        caps.locationServiceEnabled = CLLocationManager.locationServicesEnabled()
        caps.accelerometer = motionManager.isAccelerometerAvailable
        caps.gyroscope = motionManager.isGyroAvailable
        caps.magnetometer = motionManager.isMagnetometerAvailable
        caps.fusedOrientation = motionManager.isDeviceMotionAvailable
        caps.trueHeading = CLLocationManager.headingAvailable()
        return caps
    }

    public func cameraPermissionState() -> String {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return "granted"
        case .denied, .restricted: return "denied"
        case .notDetermined: return "prompt"
        @unknown default: return "unknown"
        }
    }

    public func locationPermissionState() -> String {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = locationManager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        switch status {
        case .authorizedAlways, .authorizedWhenInUse: return "granted"
        case .denied, .restricted: return "denied"
        case .notDetermined: return "prompt"
        @unknown default: return "unknown"
        }
    }

    // MARK: - Session

    public func start(config: StartConfig, completion: @escaping (Result<StartResult, NSError>) -> Void) {
        if isSessionActive {
            completion(.failure(NSError(domain: "Geoscan", code: 409,
                userInfo: [NSLocalizedDescriptionKey: "session_already_active"])))
            return
        }

        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            DispatchQueue.main.async {
                if !granted {
                    completion(.failure(NSError(domain: "Geoscan", code: 401,
                        userInfo: [NSLocalizedDescriptionKey: "permission_denied"])))
                    return
                }
                do {
                    let result = try self.startCaptureAndMotion(config: config)
                    if config.transparentWebView { self.applyWebViewTransparency() }
                    self.isSessionActive = true
                    completion(.success(result))
                } catch let error as NSError {
                    completion(.failure(error))
                }
            }
        }
    }

    private func startCaptureAndMotion(config: StartConfig) throws -> StartResult {
        let session = AVCaptureSession()
        session.sessionPreset = .high

        let position: AVCaptureDevice.Position = config.camera == "front" ? .front : .back
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
            ?? AVCaptureDevice.default(for: .video) else {
            throw NSError(domain: "Geoscan", code: 404,
                userInfo: [NSLocalizedDescriptionKey: "no_camera"])
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else {
            throw NSError(domain: "Geoscan", code: 500,
                userInfo: [NSLocalizedDescriptionKey: "camera_input_unavailable"])
        }
        session.addInput(input)

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        if let host = hostView {
            preview.frame = host.bounds
            host.layer.insertSublayer(preview, at: 0)
        }

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }

        self.captureSession = session
        self.previewLayer = preview
        self.activeDevice = device

        startMotionUpdates(hz: config.orientationHz)

        var result = StartResult()
        let fov = computeDisplayFov(device: device)
        result.horizontalFovDeg = fov.h
        result.verticalFovDeg = fov.v
        result.orientationHz = clampHz(config.orientationHz)
        return result
    }

    private func computeDisplayFov(device: AVCaptureDevice) -> (h: Double, v: Double) {
        // `videoFieldOfView` is the FOV across the sensor's longer axis.
        let sensorWideFov = Double(device.activeFormat.videoFieldOfView)
        guard sensorWideFov > 0 else { return (60.0, 45.0) }

        let dim = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let sw = Double(dim.width)
        let sh = Double(max(dim.height, 1))
        let sensorNarrowFov = 2 * atan(tan((sensorWideFov / 2) * .pi / 180) * (sh / sw)) * 180 / .pi

        let bounds = hostView?.bounds ?? UIScreen.main.bounds
        let vw = Double(bounds.width)
        let vh = Double(bounds.height)
        guard vw > 0, vh > 0 else { return (sensorWideFov, sensorNarrowFov) }

        // Phone camera sensors are physically in landscape. In a portrait host view the
        // preview is rotated 90° so the sensor's wide axis maps to the screen's height.
        let portrait = vh > vw
        let rotW = portrait ? sh : sw
        let rotH = portrait ? sw : sh
        let rotXFov = portrait ? sensorNarrowFov : sensorWideFov
        let rotYFov = portrait ? sensorWideFov : sensorNarrowFov

        // .resizeAspectFill scales until both dimensions cover the view; the longer dimension is cropped.
        let scale = max(vw / rotW, vh / rotH)
        let visW = min(rotW, vw / scale)
        let visH = min(rotH, vh / scale)

        let h = 2 * atan(tan((rotXFov / 2) * .pi / 180) * (visW / rotW)) * 180 / .pi
        let v = 2 * atan(tan((rotYFov / 2) * .pi / 180) * (visH / rotH)) * 180 / .pi
        return (abs(h), abs(v))
    }

    private func startMotionUpdates(hz: Int) {
        guard motionManager.isDeviceMotionAvailable else { return }
        motionManager.deviceMotionUpdateInterval = 1.0 / Double(clampHz(hz))

        let reference: CMAttitudeReferenceFrame =
            CLLocationManager.headingAvailable() ? .xTrueNorthZVertical : .xMagneticNorthZVertical

        if CLLocationManager.headingAvailable() {
            locationManager.startUpdatingHeading()
            headingAvailable = true
        }

        motionManager.startDeviceMotionUpdates(using: reference, to: motionQueue) { [weak self] motion, _ in
            guard let self = self, let motion = motion else { return }
            let attitude = motion.attitude

            var headingDeg = -attitude.yaw * 180 / .pi
            if headingDeg < 0 { headingDeg += 360 }
            let pitchDeg = attitude.pitch * 180 / .pi
            let rollDeg = attitude.roll * 180 / .pi

            let ts = Int64(motion.timestamp * 1000)
            let acc = self.lastAccuracy
            DispatchQueue.main.async {
                self.orientationHandler?(headingDeg, pitchDeg, rollDeg, acc, ts)
            }
        }
    }

    private func clampHz(_ requested: Int) -> Int {
        return max(5, min(100, requested))
    }

    public func pause() {
        motionManager.stopDeviceMotionUpdates()
        if headingAvailable { locationManager.stopUpdatingHeading() }
        captureSession?.stopRunning()
    }

    public func stop() {
        motionManager.stopDeviceMotionUpdates()
        if headingAvailable { locationManager.stopUpdatingHeading() }
        captureSession?.stopRunning()
        previewLayer?.removeFromSuperlayer()
        previewLayer = nil
        captureSession = nil
        activeDevice = nil
        restoreWebView()
        isSessionActive = false
    }

    // MARK: - WebView transparency

    private func applyWebViewTransparency() {
        guard let webView = webView else { return }
        originalWebViewBackgroundColor = webView.backgroundColor
        originalWebViewIsOpaque = webView.isOpaque
        webView.backgroundColor = .clear
        webView.isOpaque = false
        webView.scrollView.backgroundColor = .clear
    }

    private func restoreWebView() {
        guard let webView = webView else { return }
        webView.backgroundColor = originalWebViewBackgroundColor ?? .white
        webView.isOpaque = originalWebViewIsOpaque
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let acc = newHeading.headingAccuracy
        if acc < 0 {
            lastAccuracy = "unreliable"
        } else if acc < 15 {
            lastAccuracy = "high"
        } else if acc < 35 {
            lastAccuracy = "medium"
        } else {
            lastAccuracy = "low"
        }
    }
}
