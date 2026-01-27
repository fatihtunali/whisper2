import SwiftUI
import MapKit
import CoreLocation

// MARK: - Location Message Bubble
struct LocationBubble: View {
    let location: LocationData
    let isFromMe: Bool
    let timestamp: Date

    @State private var region: MKCoordinateRegion

    init(location: LocationData, isFromMe: Bool, timestamp: Date) {
        self.location = location
        self.isFromMe = isFromMe
        self.timestamp = timestamp
        self._region = State(initialValue: MKCoordinateRegion(
            center: location.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        ))
    }

    var body: some View {
        VStack(alignment: isFromMe ? .trailing : .leading, spacing: 4) {
            // Map Preview
            Map(coordinateRegion: .constant(region), annotationItems: [LocationAnnotation(coordinate: location.coordinate)]) { item in
                MapMarker(coordinate: item.coordinate, tint: .red)
            }
            .frame(width: 200, height: 150)
            .cornerRadius(12)
            .allowsHitTesting(false)

            // Open in Maps button
            Button(action: openInMaps) {
                HStack(spacing: 4) {
                    Image(systemName: "map.fill")
                        .font(.caption)
                    Text("Open in Maps")
                        .font(.caption)
                }
                .foregroundColor(isFromMe ? .white.opacity(0.9) : .blue)
            }

            // Timestamp
            Text(formatTime(timestamp))
                .font(.caption2)
                .foregroundColor(isFromMe ? .white.opacity(0.7) : .gray)
        }
        .padding(8)
        .background(isFromMe ? Color.blue : Color(.systemGray5))
        .cornerRadius(16)
    }

    private func openInMaps() {
        if let url = LocationService.shared.mapsURL(for: location) {
            UIApplication.shared.open(url)
        }
    }

    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

// MARK: - Location Annotation
struct LocationAnnotation: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}

// MARK: - Location Picker Sheet
struct LocationPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var locationService = LocationService.shared

    let onLocationSelected: (LocationData) -> Void

    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 41.0082, longitude: 28.9784), // Istanbul default
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )
    @State private var selectedLocation: LocationData?
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationView {
            ZStack {
                // Map
                Map(coordinateRegion: $region, showsUserLocation: true, annotationItems: selectedLocation.map { [LocationAnnotation(coordinate: $0.coordinate)] } ?? []) { item in
                    MapMarker(coordinate: item.coordinate, tint: .red)
                }
                .ignoresSafeArea(edges: .bottom)
                .onTapGesture { location in
                    // Convert tap to coordinate would require UIViewRepresentable
                    // For now, use current location button
                }

                // Center crosshair
                Image(systemName: "plus")
                    .font(.title)
                    .foregroundColor(.gray)

                // Bottom controls
                VStack {
                    Spacer()

                    VStack(spacing: 12) {
                        // Current location button
                        Button(action: getCurrentLocation) {
                            HStack {
                                if isLoading {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    Image(systemName: "location.fill")
                                }
                                Text("Use Current Location")
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                        }
                        .disabled(isLoading)

                        // Send map center button
                        Button(action: sendMapCenter) {
                            HStack {
                                Image(systemName: "mappin.and.ellipse")
                                Text("Send This Location")
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color(.systemGray5))
                            .foregroundColor(.primary)
                            .cornerRadius(12)
                        }

                        if let error = errorMessage {
                            Text(error)
                                .font(.caption)
                                .foregroundColor(.red)
                        }
                    }
                    .padding()
                    .background(.ultraThinMaterial)
                }
            }
            .navigationTitle("Share Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            locationService.requestAuthorization()
            if locationService.isAuthorized {
                getCurrentLocation()
            }
        }
    }

    private func getCurrentLocation() {
        isLoading = true
        errorMessage = nil

        Task {
            do {
                let location = try await locationService.getCurrentLocation()
                selectedLocation = location
                region = MKCoordinateRegion(
                    center: location.coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                )
                isLoading = false

                // Auto-send current location
                onLocationSelected(location)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
                isLoading = false
            }
        }
    }

    private func sendMapCenter() {
        let location = LocationData(
            latitude: region.center.latitude,
            longitude: region.center.longitude,
            timestamp: Date()
        )
        onLocationSelected(location)
        dismiss()
    }
}

// MARK: - Location Preview (for chat input)
struct LocationPreview: View {
    let location: LocationData
    let onRemove: () -> Void

    var body: some View {
        HStack {
            Image(systemName: "mappin.circle.fill")
                .foregroundColor(.red)
                .font(.title2)

            VStack(alignment: .leading) {
                Text("Location")
                    .font(.caption.bold())
                Text("\(location.latitude, specifier: "%.4f"), \(location.longitude, specifier: "%.4f")")
                    .font(.caption2)
                    .foregroundColor(.gray)
            }

            Spacer()

            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.gray)
            }
        }
        .padding(8)
        .background(Color(.systemGray6))
        .cornerRadius(8)
    }
}

// MARK: - Full Screen Map View
struct FullScreenMapView: View {
    let location: LocationData
    @Environment(\.dismiss) private var dismiss

    @State private var region: MKCoordinateRegion

    init(location: LocationData) {
        self.location = location
        self._region = State(initialValue: MKCoordinateRegion(
            center: location.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        ))
    }

    var body: some View {
        NavigationView {
            Map(coordinateRegion: $region, annotationItems: [LocationAnnotation(coordinate: location.coordinate)]) { item in
                MapMarker(coordinate: item.coordinate, tint: .red)
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Shared Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(action: openInMaps) {
                        Image(systemName: "arrow.up.right.square")
                    }
                }
            }
        }
    }

    private func openInMaps() {
        if let url = LocationService.shared.mapsURL(for: location) {
            UIApplication.shared.open(url)
        }
    }
}

#Preview {
    LocationBubble(
        location: LocationData(latitude: 41.0082, longitude: 28.9784),
        isFromMe: true,
        timestamp: Date()
    )
    .padding()
}
