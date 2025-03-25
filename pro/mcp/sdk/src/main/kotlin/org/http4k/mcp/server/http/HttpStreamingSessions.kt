package org.http4k.mcp.server.http

import dev.forkhandles.time.executors.SimpleScheduler
import dev.forkhandles.time.executors.SimpleSchedulerService
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.ACCEPTED
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.OK
import org.http4k.lens.Header
import org.http4k.lens.LAST_EVENT_ID
import org.http4k.lens.MCP_SESSION_ID
import org.http4k.lens.contentType
import org.http4k.mcp.model.CompletionStatus
import org.http4k.mcp.model.ProgressToken
import org.http4k.mcp.server.protocol.ClientRequestContext
import org.http4k.mcp.server.protocol.ClientRequestContext.ToolCall
import org.http4k.mcp.server.protocol.ClientRequestContext.Stream
import org.http4k.mcp.server.protocol.Session
import org.http4k.mcp.server.protocol.Sessions
import org.http4k.mcp.server.sessions.SessionEventStore
import org.http4k.mcp.server.sessions.SessionEventStore.Companion.NoCache
import org.http4k.mcp.server.sessions.SessionEventTracking
import org.http4k.mcp.server.sessions.SessionProvider
import org.http4k.mcp.util.McpJson.compact
import org.http4k.mcp.util.McpNodeType
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class HttpStreamingSessions(
    private val sessionProvider: SessionProvider = SessionProvider.Random(Random),
    private val sessionEventTracking: SessionEventTracking = SessionEventTracking.InMemory(),
    private val eventStore: SessionEventStore = NoCache,
    private val keepAliveDelay: Duration = Duration.ofSeconds(2)
) : Sessions<Sse, Response> {

    private val sessions = ConcurrentHashMap<Session, Sse>()
    private val requests = ConcurrentHashMap<ProgressToken, Sse>()

    override fun ok() = Response(ACCEPTED)

    override fun request(context: ClientRequestContext, message: McpNodeType): Response {
        val sse = when (context) {
            is ToolCall -> requests[context.progressToken]
            is Stream -> sessions[context.session]
        }

        return when (sse) {
            null -> error()
            else -> {
                SseMessage.Event("message", compact(message), sessionEventTracking.next(context.session)).also {
                    sse.send(it)
                    eventStore.write(context.session, it)
                }
                Response(ACCEPTED)
            }
        }
    }

    override fun respond(
        transport: Sse,
        session: Session,
        message: McpNodeType,
        status: CompletionStatus
    ): Response {
        SseMessage.Event("message", compact(message), sessionEventTracking.next(session)).also {
            eventStore.write(session, it)
            transport.send(it)
        }
        return Response(OK).contentType(APPLICATION_JSON).body(compact(message))
    }

    override fun error() = Response(BAD_REQUEST)

    override fun onClose(session: Session, fn: () -> Unit) {
        sessions[session]?.also { it.onClose(fn) }
    }

    override fun retrieveSession(connectRequest: Request) =
        sessionProvider.validate(connectRequest, Header.MCP_SESSION_ID(connectRequest))

    override fun transportFor(session: Session) = sessions[session] ?: error("Session not found")

    override fun end(context: ClientRequestContext) = ok().also {
        when (context) {
            is ToolCall -> requests.remove(context.progressToken)
            is Stream -> {
                sessions.remove(context.session)?.close()
                sessionEventTracking.remove(context.session)
            }
        }
    }

    override fun assign(context: ClientRequestContext, transport: Sse, connectRequest: Request) {
        when (context) {
            is ToolCall -> requests[context.progressToken] = transport
            is Stream -> {
                sessions[context.session] = transport
                eventStore.read(context.session, Header.LAST_EVENT_ID(connectRequest)).forEach(transport::send)
            }
        }
    }

    fun start(executor: SimpleScheduler = SimpleSchedulerService(1)) =
        executor.scheduleWithFixedDelay(::pruneDeadConnections, keepAliveDelay, keepAliveDelay)

    private fun pruneDeadConnections() =
        sessions.toList().forEach { (session, sse) ->
            try {
                sse.send(SseMessage.Event("ping", ""))
            } catch (e: Exception) {
                sessions.remove(session)
                sse.close()
            }
        }
}
