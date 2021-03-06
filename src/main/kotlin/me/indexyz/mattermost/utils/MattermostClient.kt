package me.indexyz.mattermost.utils

import com.beust.klaxon.Json
import com.beust.klaxon.Klaxon
import dagger.Module
import io.ktor.client.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.sentry.Sentry
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Contact.Companion.uploadImage
import net.mamoe.mirai.message.data.*
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.inject.Inject

data class Attachment(
    @Json(name = "image_url")
    val imageUrl: String
)

data class MattermostMessage(
    @Json(name = "username")
    val name: String,
    @Json(name = "icon_url")
    val userAvatar: String,
    @Json(name = "channel")
    val channelId: String,
    @Json(name = "text")
    val text: String,
    @Json(name = "attachments")
    val attachments: List<Attachment>
)

@Module
class MattermostClient @Inject constructor(
    private val config: BotConfig,
    private val http: HttpClient,
    private val bot: Bot
) {

    suspend fun sendMessage(message: MattermostMessage) {
        http.post<ByteArray>(config.mattermost.webhook) {
            body = Klaxon().toJsonString(message)
            contentType(ContentType.Application.Json)
        }

        println(Klaxon().toJsonString(message))
    }

    private suspend fun readImage(id: String): ByteArray {
        println("${config.mattermost.url}v4/files/${id}")
        val res = http.get<ByteArray>("${config.mattermost.url}v4/files/${id}") {
            header("Authorization", "Bearer ${config.mattermost.token}")
        }

        return res
    }

    private suspend fun onMessage(data: String) {
        val obj = Klaxon().parseJsonObject(StringReader(data)) ?: return

        if (obj.string("event") != "posted") {
            return
        }

        val dataObj = obj.obj("data") ?: return

        val channel = dataObj.string("channel_name") ?: return

        val links = (config.links.filter { cfg -> cfg.channel == channel }).lastOrNull() ?: return
        val group = bot.getGroup(links.group) ?: return

        val post = Klaxon().parseJsonObject(
            StringReader(dataObj.string("post") ?: return)
        )

        if (post.obj("props")?.string("from_webhook").equals("true")) {
            return
        }

        val message = post.string("message") ?: return

        var files: List<ByteArray> = emptyList()
        if (post.containsKey("file_ids")) {
            files = post.array<String>("file_ids")!!.map {
                readImage(it)
            }
        }

        var out: MessageChain = EmptyMessageChain

        out += message
        files.map {
            out += group.uploadImage(ByteArrayInputStream(it))
        }

        group.sendMessage(out)
    }

    suspend fun listenMessage() {
        this.http.wss(urlString = (config.mattermost.url + "v4/websocket").replace("https", "wss"), {
            this.cookie("MMAUTHTOKEN", config.mattermost.token)
        }) {
            while (true) {
                try {
                    val frame = incoming.receive()
                    val data = frame.data.decodeToString()
                    println(data)

                    this@MattermostClient.onMessage(data)
                } catch (e: Exception) {
                    Sentry.captureException(e)
                }
            }
        }
    }
}