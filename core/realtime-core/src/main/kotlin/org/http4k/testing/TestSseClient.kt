package org.http4k.testing

import org.http4k.core.PolyHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.sse.PushAdaptingSse
import org.http4k.sse.SseClient
import org.http4k.sse.SseHandler
import org.http4k.sse.SseMessage
import org.http4k.sse.SseResponse
import java.util.ArrayDeque

/**
 * A class that is used for *offline* testing of a routed Sse, without starting up a Server.
 */
class TestSseClient internal constructor(sseResponse: SseResponse, request: Request) : SseClient {

    val status = sseResponse.status
    val response = Response(sseResponse.status).headers(sseResponse.headers)

    private val queue = ArrayDeque<() -> SseMessage?>()

    override fun received() = generateSequence {
        if (queue.isEmpty()) return@generateSequence null
        queue.remove()()
    }

    private val socket = object : PushAdaptingSse(request) {
        init {
            sseResponse.consumer(this)
            onClose { queue.add { null } }
        }

        override fun send(message: SseMessage) = apply {
            queue.add { message }
        }

        override fun close() {
            queue.add { null }
        }
    }

    override fun close() {
        socket.triggerClose()
    }
}

fun SseHandler.testSseClient(request: Request): TestSseClient = TestSseClient(invoke(request), request)
fun PolyHandler.testSseClient(request: Request): TestSseClient =
    sse?.testSseClient(request) ?: error("No SSE handler set.")

/**
 * Use this client nd then close it
 */
fun SseClient.useClient(fn: SseClient.() -> Unit) {
    fn(this)
    close()
}
