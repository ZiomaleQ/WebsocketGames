import io.ktor.http.cio.websocket.*
import io.ktor.util.collections.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class App {
    private val members = ConcurrentHashMap<String, Player>()
    private val lobbies = ConcurrentHashMap<UUID, GameLobby>()
    private val memberCounter = AtomicInteger()

    private val channel = Channel<OutgoingMessage>()

    init {
        thread {
            runBlocking {
                channel.consumeEach { members[it.receiver]?.send(it.data) }
            }
        }
    }

    fun memberJoin(member: String, session: WebSocketSession) {
        val user = members.computeIfAbsent(member) { Player(member, mutableListOf(), memberCounter.incrementAndGet()) }
        user.sessions.add(session)
    }

    fun memberLeft(member: String, session: WebSocketSession) {
        val user = members[member]
        user?.sessions?.remove(session)

        if (user?.sessions?.isEmpty() == true) {
            members.remove(member)
        }
    }

    suspend fun joinGame(member: String, gameUuid: String): Boolean {

        val lobby = lobbies[UUID.fromString(gameUuid)] ?: return false
        members[member] ?: return false

        lobby.joinPlayer(member)

        return true
    }

    suspend fun quitGame(member: String, gameUuid: String): Boolean {

        val lobby = lobbies[UUID.fromString(gameUuid)] ?: return false
        members[member] ?: return false

        lobby.leftGame(member)

        return true
    }

    suspend fun handle(member: String, content: String) {
        if (content.startsWith("{")) {
            handleJSON(member, content)
        } else {
            handleRaw(member, content)
        }
    }

    private fun handleJSON(member: String, content: String) {
        println("$member : $content")
        //Only for JSON packets
    }

    private suspend fun handleRaw(member: String, content: String) = content.split("|").let { parts ->
        when (parts[0]) {
            "game" -> gameCommand(member, parts.drop(0))
            "player" -> playerCommand(member, parts.drop(0))
            else -> "INVALID-COMMAND"
        }
    }

    private suspend fun gameCommand(member: String, content: List<String>) = when (content[0]) {
        "new" -> run {
            val owner = members[member] ?: return
            val lobby = UnoLobby(UUID.randomUUID(), channel)
            lobby.joinPlayer(owner.member)
            lobby.setOwner(member)
            lobbies[lobby.uuid] = lobby
        }
        "start" -> run {
            members[member] ?: return
            val lobby = lobbies.values.find { member in it.members } ?: return
            return if (lobby.ownerID == member) lobby.startGame() else Unit
        }
        else -> Unit
    }

    private suspend fun playerCommand(member: String, content: List<String>) = when (content[0]) {
        "changeName" -> run {
            val user = members[member] ?: return
            user.customName = content[1]

            val lobby = lobbies.values.find { member in it.members } ?: return
            lobby.members.forEach {
                channel.send(OutgoingMessage(it, "player|playerUpdate|$member"))
            }
        }
        else -> Unit
    }
}

class UnoLobby(uuid: UUID, channel: Channel<OutgoingMessage>) : GameLobby(GameType.UNO, uuid, channel)

abstract class GameLobby(val game: GameType, val uuid: UUID, private val channel: Channel<OutgoingMessage>) {
    val members = ConcurrentList<String>()

    var ownerID: String = "NO_ID"

    var gameState = GameState.LOBBY

    fun setOwner(player: String) {
        if (player in members) ownerID = player
    }

    suspend fun startGame() {
        broadcast("game|start")
        gameState = GameState.IN_GAME
    }

    suspend fun endGame() {
        broadcast("game|end")
        gameState = GameState.POST_LOBBY
    }

    suspend fun joinPlayer(player: String) {
        broadcast("game|join")
        members.add(player)
    }

    suspend fun leftGame(player: String) {
        broadcast("game|quit")
        members.remove(player)
    }

    private suspend fun broadcast(message: String) {
        members.forEach {
            channel.send(OutgoingMessage(it, message))
        }
    }
}

enum class GameState {
    LOBBY, IN_GAME, POST_LOBBY
}

enum class GameType {
    UNO
}

data class Player(val member: String, val sessions: MutableList<WebSocketSession>, val id: Int) {
    var customName: String? = null

    val name: String
        get() = customName ?: "member-$id"


    suspend fun send(frame: Frame) {
        sessions.forEach {
            try {
                it.send(frame.copy())
            } catch (_: Throwable) {
                it.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, ""))
            }
        }
    }

    suspend fun send(frame: String) {
        sessions.forEach {
            try {
                it.send(Frame.Text(frame))
            } catch (_: Throwable) {
                it.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, ""))
            }
        }
    }
}

data class OutgoingMessage(val receiver: String, val data: String)