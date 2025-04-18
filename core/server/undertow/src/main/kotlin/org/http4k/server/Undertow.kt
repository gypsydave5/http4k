package org.http4k.server

import org.http4k.core.HttpHandler
import org.http4k.server.ServerConfig.StopMode
import org.http4k.server.ServerConfig.StopMode.Immediate
import org.http4k.sse.SseHandler
import org.http4k.websocket.WsHandler

/**
 * Stock version of an Undertow Server. Not that if you want to configure your own server instance you
 * can duplicate this code and modify it as required. We are purposefully trying to limit options
 * here to keep the API simple for the 99% of use-cases.
 */
class Undertow(private val port: Int = 8000, override val stopMode: StopMode
) : PolyServerConfig {
    constructor(port: Int = 8000) : this(port, Immediate)

    override fun toServer(http: HttpHandler?, ws: WsHandler?, sse: SseHandler?): Http4kServer {
        val (httpHandler, multiProtocolHandler) = buildUndertowHandlers(http, ws, sse, stopMode)

        return defaultUndertowBuilder(port, multiProtocolHandler).buildHttp4kUndertowServer(
            httpHandler, stopMode, port
        )
    }
}
