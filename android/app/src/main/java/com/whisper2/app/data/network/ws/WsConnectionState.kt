package com.whisper2.app.data.network.ws

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_EXPIRED
}
