package org.http4k.mcp.server.http

import org.http4k.core.Method.DELETE
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.ACCEPTED
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.mcp.server.protocol.AuthedSession
import org.http4k.mcp.server.protocol.InvalidSession
import org.http4k.mcp.server.protocol.McpProtocol
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage

/**
 * Routes inbound POST requests to the MCP server to the MCP protocol for processing (returning responses via JSON RPC),
 * and deletes old sessions at the request of the client.
 */
fun HttpNonStreamingMcpConnection(protocol: McpProtocol<Sse, Response>, messageStore: (Sse) -> Sse = { it }) =
    "/mcp" bind routes(
        POST to { req ->
            with(protocol) {
                when (val session = validate(req)) {
                    is AuthedSession -> receive(messageStore(FakeSse(req)), session.id, req)
                    is InvalidSession -> Response(BAD_REQUEST)
                }
            }
        },
        DELETE to { req ->
            when(val session = protocol.validate(req)) {
                is AuthedSession -> {
                    protocol.end(session)
                    Response(ACCEPTED)
                }
                InvalidSession -> Response(NOT_FOUND)
            }
        }
    )

private class FakeSse(override val connectRequest: Request) : Sse {
    override fun send(message: SseMessage) = this
    override fun close() {}
    override fun onClose(fn: () -> Unit): Sse = this
}

