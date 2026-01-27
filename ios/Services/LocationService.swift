import Foundation
import CoreLocation
import Combine

// MARK: - Location Data
struct LocationData: Codable, Equatable {
    let latitude: Double
    let longitude: Double
    let altitude: Double?
    let accuracy: Double?
    let timestamp: Date

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    init(location: CLLocation) {
        self.latitude = location.coordinate.latitude
        self.longitude = location.coordinate.longitude
        self.altitude = location.altitude
        self.accuracy = location.horizontalAccuracy
        self.timestamp = location.timestamp
    }

    init(latitude: Double, longitude: Double, altitude: Double? = nil, accuracy: Double? = nil, timestamp: Date = Date()) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitude = altitude
        self.accuracy = accuracy
        self.timestamp = timestamp
    }
}

// MARK: - Location Service
@MainActor
class LocationService: NSObject, ObservableObject {
    static let shared = LocationService()

    private let locationManager = CLLocationManager()

    @Published var currentLocation: LocationData?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var isUpdatingLocation = false
    @Published var error: Error?

    private var locationContinuation: CheckedContinuation<LocationData, Error>?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        authorizationStatus = locationManager.authorizationStatus
    }

    // MARK: - Authorization
    func requestAuthorization() {
        locationManager.requestWhenInUseAuthorization()
    }

    var isAuthorized: Bool {
        switch authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            return true
        default:
            return false
        }
    }

    // MARK: - Get Current Location
    func getCurrentLocation() async throws -> LocationData {
        guard isAuthorized else {
            requestAuthorization()
            throw LocationError.notAuthorized
        }

        return try await withCheckedThrowingContinuation { continuation in
            self.locationContinuation = continuation
            self.isUpdatingLocation = true
            self.locationManager.requestLocation()
        }
    }

    // MARK: - Start/Stop Updates
    func startUpdatingLocation() {
        guard isAuthorized else {
            requestAuthorization()
            return
        }
        isUpdatingLocation = true
        locationManager.startUpdatingLocation()
    }

    func stopUpdatingLocation() {
        isUpdatingLocation = false
        locationManager.stopUpdatingLocation()
    }

    // MARK: - Encode/Decode for Messages
    func encodeLocation(_ location: LocationData) throws -> Data {
        try JSONEncoder().encode(location)
    }

    func decodeLocation(_ data: Data) throws -> LocationData {
        try JSONDecoder().decode(LocationData.self, from: data)
    }

    // MARK: - Distance Calculation
    func distance(from: LocationData, to: LocationData) -> CLLocationDistance {
        let fromLocation = CLLocation(latitude: from.latitude, longitude: from.longitude)
        let toLocation = CLLocation(latitude: to.latitude, longitude: to.longitude)
        return fromLocation.distance(from: toLocation)
    }

    // MARK: - Format Distance
    func formatDistance(_ meters: CLLocationDistance) -> String {
        if meters < 1000 {
            return String(format: "%.0f m", meters)
        } else {
            return String(format: "%.1f km", meters / 1000)
        }
    }

    // MARK: - Google Maps / Apple Maps URL
    func mapsURL(for location: LocationData) -> URL? {
        URL(string: "https://maps.apple.com/?ll=\(location.latitude),\(location.longitude)&q=Shared%20Location")
    }

    func googleMapsURL(for location: LocationData) -> URL? {
        URL(string: "https://www.google.com/maps?q=\(location.latitude),\(location.longitude)")
    }
}

// MARK: - CLLocationManagerDelegate
extension LocationService: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let locationData = LocationData(location: location)

        Task { @MainActor in
            self.currentLocation = locationData
            self.isUpdatingLocation = false

            if let continuation = self.locationContinuation {
                self.locationContinuation = nil
                continuation.resume(returning: locationData)
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.error = error
            self.isUpdatingLocation = false

            if let continuation = self.locationContinuation {
                self.locationContinuation = nil
                continuation.resume(throwing: error)
            }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            self.authorizationStatus = manager.authorizationStatus
        }
    }
}

// MARK: - Errors
enum LocationError: LocalizedError {
    case notAuthorized
    case locationUnavailable

    var errorDescription: String? {
        switch self {
        case .notAuthorized:
            return "Location access not authorized"
        case .locationUnavailable:
            return "Location unavailable"
        }
    }
}
