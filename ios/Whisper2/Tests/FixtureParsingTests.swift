import XCTest
@testable import Whisper2

/// Tests that verify iOS models correctly parse the golden fixtures.
/// These fixtures represent the exact JSON format the server sends.
/// If any test fails, the iOS model is out of sync with the server.
final class FixtureParsingTests: XCTestCase {

    private let decoder = JSONDecoder()

    // MARK: - Helper

    private func loadFixture(_ filename: String, subdirectory: String) throws -> Data {
        let bundle = Bundle(for: type(of: self))
        guard let url = bundle.url(forResource: filename, withExtension: "json", subdirectory: "Fixtures/\(subdirectory)") else {
            // Try loading from file system directly for SPM
            let fixturesPath = URL(fileURLWithPath: #file)
                .deletingLastPathComponent()
                .appendingPathComponent("Fixtures")
                .appendingPathComponent(subdirectory)
                .appendingPathComponent("\(filename).json")
            return try Data(contentsOf: fixturesPath)
        }
        return try Data(contentsOf: url)
    }

    // MARK: - WebSocket Fixtures

    func testParseRegisterChallenge() throws {
        let data = try loadFixture("register_challenge", subdirectory: "ws")
        let frame = try decoder.decode(WSFrame<RegisterChallengePayload>.self, from: data)

        XCTAssertEqual(frame.type, "register_challenge")
        XCTAssertEqual(frame.payload.challengeId, "550e8400-e29b-41d4-a716-446655440000")
        XCTAssertFalse(frame.payload.challenge.isEmpty)
        XCTAssertGreaterThan(frame.payload.expiresAt, 0)
    }

    func testParseRegisterAck() throws {
        let data = try loadFixture("register_ack", subdirectory: "ws")
        let frame = try decoder.decode(WSFrame<RegisterAckPayload>.self, from: data)

        XCTAssertEqual(frame.type, "register_ack")
        XCTAssertTrue(frame.payload.success)
        XCTAssertEqual(frame.payload.whisperId, "WSP-ABCD-EFGH-IJKL")
        XCTAssertFalse(frame.payload.sessionToken.isEmpty)
        XCTAssertGreaterThan(frame.payload.sessionExpiresAt, 0)
        XCTAssertGreaterThan(frame.payload.serverTime, 0)
    }

    func testParseMessageReceived() throws {
        let data = try loadFixture("message_received", subdirectory: "ws")
        let frame = try decoder.decode(WSFrame<MessageReceivedPayload>.self, from: data)

        XCTAssertEqual(frame.type, "message_received")
        XCTAssertFalse(frame.payload.messageId.isEmpty)
        XCTAssertEqual(frame.payload.from, "WSP-ABCD-EFGH-IJKL")
        XCTAssertEqual(frame.payload.to, "WSP-MNOP-QRST-UVWX")
        XCTAssertEqual(frame.payload.msgType, "text")
        XCTAssertGreaterThan(frame.payload.timestamp, 0)
        XCTAssertFalse(frame.payload.nonce.isEmpty)
        XCTAssertFalse(frame.payload.ciphertext.isEmpty)
        XCTAssertFalse(frame.payload.sig.isEmpty)
    }

    func testParseGroupEvent() throws {
        let data = try loadFixture("group_event", subdirectory: "ws")
        let frame = try decoder.decode(WSFrame<GroupEventPayload>.self, from: data)

        XCTAssertEqual(frame.type, "group_event")
        XCTAssertEqual(frame.payload.event, "member_added")
        XCTAssertFalse(frame.payload.group.groupId.isEmpty)
        XCTAssertEqual(frame.payload.group.title, "Family Group")
        XCTAssertEqual(frame.payload.group.ownerId, "WSP-ABCD-EFGH-IJKL")
        XCTAssertGreaterThan(frame.payload.group.members.count, 0)
        XCTAssertNotNil(frame.payload.affectedMembers)
    }

    func testParseCallIncoming() throws {
        let data = try loadFixture("call_incoming", subdirectory: "ws")
        let frame = try decoder.decode(WSFrame<CallIncomingPayload>.self, from: data)

        XCTAssertEqual(frame.type, "call_incoming")
        XCTAssertFalse(frame.payload.callId.isEmpty)
        XCTAssertEqual(frame.payload.from, "WSP-ABCD-EFGH-IJKL")
        XCTAssertFalse(frame.payload.isVideo)
        XCTAssertGreaterThan(frame.payload.timestamp, 0)
        XCTAssertFalse(frame.payload.nonce.isEmpty)
        XCTAssertFalse(frame.payload.ciphertext.isEmpty)
        XCTAssertFalse(frame.payload.sig.isEmpty)
    }

    // MARK: - HTTP Fixtures

    func testParseUsersKeys() throws {
        let data = try loadFixture("users_keys", subdirectory: "http")
        let response = try decoder.decode(KeyLookupResponse.self, from: data)

        XCTAssertEqual(response.whisperId, "WSP-ABCD-EFGH-IJKL")
        XCTAssertFalse(response.encPublicKey.isEmpty)
        XCTAssertFalse(response.signPublicKey.isEmpty)
        XCTAssertEqual(response.status, "active")
    }

    func testParseBackupContactsGet() throws {
        let data = try loadFixture("backup_contacts_get", subdirectory: "http")
        let response = try decoder.decode(ContactsBackupDownloadResponse.self, from: data)

        XCTAssertFalse(response.nonce.isEmpty)
        XCTAssertFalse(response.ciphertext.isEmpty)
        XCTAssertGreaterThan(response.sizeBytes, 0)
        XCTAssertGreaterThan(response.createdAt, 0)
        XCTAssertGreaterThan(response.updatedAt, 0)
    }

    func testParseAttachmentsPresignUpload() throws {
        let data = try loadFixture("attachments_presign_upload", subdirectory: "http")
        let response = try decoder.decode(AttachmentPresignUploadResponse.self, from: data)

        XCTAssertTrue(response.objectKey.hasPrefix("whisper/att/"))
        XCTAssertTrue(response.uploadUrl.contains("digitaloceanspaces.com"))
        XCTAssertGreaterThan(response.expiresAtMs, 0)
        XCTAssertNotNil(response.headers["Content-Type"])
    }

    func testParseAttachmentsPresignDownload() throws {
        let data = try loadFixture("attachments_presign_download", subdirectory: "http")
        let response = try decoder.decode(AttachmentPresignDownloadResponse.self, from: data)

        XCTAssertTrue(response.objectKey.hasPrefix("whisper/att/"))
        XCTAssertTrue(response.downloadUrl.contains("digitaloceanspaces.com"))
        XCTAssertGreaterThan(response.expiresAtMs, 0)
        XCTAssertGreaterThan(response.sizeBytes, 0)
        XCTAssertEqual(response.contentType, "image/jpeg")
    }

    // MARK: - WhisperID Format Validation

    func testWhisperIdFormat() {
        // All WhisperIDs should match: WSP-XXXX-XXXX-XXXX
        let pattern = #"^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$"#
        let regex = try! NSRegularExpression(pattern: pattern)

        let validIds = [
            "WSP-ABCD-EFGH-IJKL",
            "WSP-MNOP-QRST-UVWX",
            "WSP-2345-6789-ABCD"
        ]

        for id in validIds {
            let range = NSRange(id.startIndex..<id.endIndex, in: id)
            let match = regex.firstMatch(in: id, options: [], range: range)
            XCTAssertNotNil(match, "WhisperID '\(id)' should match format")
        }

        // Invalid formats that should NOT match
        let invalidIds = [
            "WH2-ABCD-EFGH",      // Wrong prefix
            "WSP-ABC-DEFG-HIJK",   // Wrong segment length
            "WSP-ABCD-EFGH",       // Missing segment
            "wsp-abcd-efgh-ijkl"   // Lowercase
        ]

        for id in invalidIds {
            let range = NSRange(id.startIndex..<id.endIndex, in: id)
            let match = regex.firstMatch(in: id, options: [], range: range)
            XCTAssertNil(match, "WhisperID '\(id)' should NOT match format")
        }
    }
}
