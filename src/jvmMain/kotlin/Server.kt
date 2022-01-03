import io.ktor.application.*
import io.ktor.features.*
import io.ktor.html.respondHtml
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.html.*

fun HTML.index() {
    head {
        title("Hello from Ktor!")
    }
    body {
        div {
            +"Hello from Ktor"
        }
        div {
            id = "root"
        }
        script(src = "/static/untitled2.js") {}
    }
}

fun main() {

    val app = App()

    embeddedServer(Netty, port = 8080, host = "127.0.0.1") {
        install(WebSockets)

        install(DefaultHeaders)
        install(CallLogging)

        install(Sessions) {
            cookie<UserSession>("SESSION")
        }

        intercept(ApplicationCallPipeline.Features) {
            if (call.sessions.get<UserSession>() == null) {
                call.sessions.set(UserSession(generateNonce()))
            }
        }

        routing {
            get("/") {
                call.respondHtml(HttpStatusCode.OK, HTML::index)
            }

            put("/join/{game}") {
                val session = call.sessions.get<UserSession>()

                //Shouldn't happen but yes
                if (session == null) {
                    call.respond(HttpStatusCode.UnprocessableEntity, "No session")
                    return@put
                }

                if (call.parameters["game"] == null) {
                    call.respond(HttpStatusCode.BadRequest, "Wrong UUID")
                    return@put
                }

                if (app.joinGame(session.id, call.parameters["game"]!!)) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Member doesn't exist or game")
                }
            }

            put("/quit/{game}") {
                val session = call.sessions.get<UserSession>()

                //Shouldn't happen but yes
                if (session == null) {
                    call.respond(HttpStatusCode.UnprocessableEntity, "No session")
                    return@put
                }

                if (call.parameters["game"] == null) {
                    call.respond(HttpStatusCode.BadRequest, "No UUID")
                    return@put
                }

                if (app.quitGame(session.id, call.parameters["game"]!!)) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Member doesn't exist or game")
                }
            }

            webSocket("/ws") {
                val session = call.sessions.get<UserSession>()

                //Shouldn't happen but yes
                if (session == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session"))
                    return@webSocket
                }

                app.memberJoin(session.id, this)

                try {
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            app.handle(session.id, frame.readText())
                        }
                    }
                } finally {
                    // Could be error or just end
                    app.memberLeft(session.id, this)
                }
            }

            static("/static") {
                resources()
            }
        }
    }.start(wait = true)
}

data class UserSession(val id: String)