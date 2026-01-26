package com.whisper2.app.ws

import com.google.gson.JsonParser
import com.whisper2.app.network.ws.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 1: JSON decode/encode for pending messages
 */
class WsPendingModelsTest {

    // ==========================================================================
    // fetch_pending serialize
    // ==========================================================================

    @Test
    fun `gate1 fetch_pending serializes correctly`() {
        val payload = FetchPendingPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "sess_test_token",
            cursor = null,
            limit = 50
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.FETCH_PENDING,
            payload = payload,
            requestId = "req-123"
        )

        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals("fetch_pending", parsed.get("type").asString)
        assertEquals("req-123", parsed.get("requestId").asString)

        val payloadObj = parsed.getAsJsonObject("payload")
        assertEquals(1, payloadObj.get("protocolVersion").asInt)
        assertEquals(1, payloadObj.get("cryptoVersion").asInt)
        assertEquals("sess_test_token", payloadObj.get("sessionToken").asString)
        assertEquals(50, payloadObj.get("limit").asInt)
        // cursor is null so Gson omits it or serializes as null
        val cursorElement = payloadObj.get("cursor")
        assertTrue("cursor should be null or absent", cursorElement == null || cursorElement.isJsonNull)
    }

    @Test
    fun `gate1 fetch_pending with cursor serializes correctly`() {
        val payload = FetchPendingPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "sess_token",
            cursor = "cursor-abc-123",
            limit = 50
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.FETCH_PENDING,
            payload = payload,
            requestId = "req-456"
        )

        val parsed = JsonParser.parseString(json).asJsonObject
        val payloadObj = parsed.getAsJsonObject("payload")

        assertEquals("cursor-abc-123", payloadObj.get("cursor").asString)
    }

    // ==========================================================================
    // pending_messages parse
    // ==========================================================================

    @Test
    fun `gate1 pending_messages parses correctly with 2 messages`() {
        val json = """
            {
                "type": "pending_messages",
                "payload": {
                    "messages": [
                        {
                            "messageId": "msg-1",
                            "from": "WSP-SENDER-AAAA",
                            "to": "WSP-RECIPIENT-BBBB",
                            "msgType": "text",
                            "timestamp": 1700000000000,
                            "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                            "ciphertext": "encrypted_content_1",
                            "sig": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                        },
                        {
                            "messageId": "msg-2",
                            "from": "WSP-SENDER-CCCC",
                            "to": "WSP-RECIPIENT-BBBB",
                            "msgType": "text",
                            "timestamp": 1700000001000,
                            "nonce": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                            "ciphertext": "encrypted_content_2",
                            "sig": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
                        }
                    ],
                    "nextCursor": "cursor-next-page"
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)

        assertEquals("pending_messages", envelope.type)
        assertNotNull(envelope.payload)

        val payload = WsParser.parsePayload<PendingMessagesPayload>(envelope.payload)
        assertNotNull(payload)
        assertEquals(2, payload!!.messages.size)
        assertEquals("cursor-next-page", payload.nextCursor)

        // First message
        val msg1 = payload.messages[0]
        assertEquals("msg-1", msg1.messageId)
        assertEquals("WSP-SENDER-AAAA", msg1.from)
        assertEquals("WSP-RECIPIENT-BBBB", msg1.to)
        assertEquals("text", msg1.msgType)
        assertEquals(1700000000000L, msg1.timestamp)

        // Second message
        val msg2 = payload.messages[1]
        assertEquals("msg-2", msg2.messageId)
        assertEquals("WSP-SENDER-CCCC", msg2.from)
    }

    @Test
    fun `gate1 pending_messages parses empty list`() {
        val json = """
            {
                "type": "pending_messages",
                "payload": {
                    "messages": [],
                    "nextCursor": null
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)
        val payload = WsParser.parsePayload<PendingMessagesPayload>(envelope.payload)

        assertNotNull(payload)
        assertEquals(0, payload!!.messages.size)
        assertNull(payload.nextCursor)
    }

    @Test
    fun `gate1 pending_messages parses without nextCursor`() {
        val json = """
            {
                "type": "pending_messages",
                "payload": {
                    "messages": []
                }
            }
        """.trimIndent()

        val envelope = WsParser.parseRaw(json)
        val payload = WsParser.parsePayload<PendingMessagesPayload>(envelope.payload)

        assertNotNull(payload)
        assertNull(payload!!.nextCursor)
    }

    // ==========================================================================
    // delivery_receipt serialize
    // ==========================================================================

    @Test
    fun `gate1 delivery_receipt serializes correctly`() {
        val payload = DeliveryReceiptPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "sess_token",
            messageId = "msg-123",
            from = "WSP-RECIPIENT-AAAA",
            to = "WSP-SENDER-BBBB",
            status = "delivered",
            timestamp = 1700000002000L
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.DELIVERY_RECEIPT,
            payload = payload,
            requestId = "receipt-req-1"
        )

        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals("delivery_receipt", parsed.get("type").asString)
        assertEquals("receipt-req-1", parsed.get("requestId").asString)

        val payloadObj = parsed.getAsJsonObject("payload")
        assertEquals(1, payloadObj.get("protocolVersion").asInt)
        assertEquals(1, payloadObj.get("cryptoVersion").asInt)
        assertEquals("sess_token", payloadObj.get("sessionToken").asString)
        assertEquals("msg-123", payloadObj.get("messageId").asString)
        assertEquals("WSP-RECIPIENT-AAAA", payloadObj.get("from").asString)
        assertEquals("WSP-SENDER-BBBB", payloadObj.get("to").asString)
        assertEquals("delivered", payloadObj.get("status").asString)
        assertEquals(1700000002000L, payloadObj.get("timestamp").asLong)
    }

    @Test
    fun `gate1 delivery_receipt read status serializes correctly`() {
        val payload = DeliveryReceiptPayload(
            protocolVersion = 1,
            cryptoVersion = 1,
            sessionToken = "sess_token",
            messageId = "msg-456",
            from = "WSP-A",
            to = "WSP-B",
            status = "read",
            timestamp = 1700000003000L
        )

        val json = WsParser.createEnvelope(
            type = WsMessageTypes.DELIVERY_RECEIPT,
            payload = payload,
            requestId = "receipt-req-2"
        )

        val parsed = JsonParser.parseString(json).asJsonObject
        val payloadObj = parsed.getAsJsonObject("payload")

        assertEquals("read", payloadObj.get("status").asString)
    }

    // ==========================================================================
    // Message type constants
    // ==========================================================================

    @Test
    fun `gate1 message type constants match protocol`() {
        assertEquals("fetch_pending", WsMessageTypes.FETCH_PENDING)
        assertEquals("pending_messages", WsMessageTypes.PENDING_MESSAGES)
        assertEquals("delivery_receipt", WsMessageTypes.DELIVERY_RECEIPT)
        assertEquals("message_delivered", WsMessageTypes.MESSAGE_DELIVERED)
    }
}
