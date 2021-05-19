import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.util.logging.Logger
import myutils.*
import java.time.LocalDate

sealed class WatcherMsg {
    data class CheckChannels(val zenChannels: List<ZenChannel>): WatcherMsg()
}

class Watcher(private val timeout: Long) {
    private val queryHead = "https://zen.yandex.ru/api/v3/launcher/more?channel_id="
    private val parser = JSONParser()
    private val log = Logger.getLogger("Watcher")

    private fun findArticlesAndGetNextLink(zenChannel: ZenChannel, answer: String, articles: MutableList<ZenArticle>): String {
        val jsonObject = this.parser.parse(answer) as JSONObject
        val items = jsonObject["items"] as JSONArray
        for (articleObj in items) {
            val article = articleObj as JSONObject
            val endOfTesting = LocalDate.now().plusWeeks(3)
            articles.add(
                ZenArticle(
                    zenChannel.authorId,
                    zenChannel.channelId,
                    article["title"] as String,
                    (article["link"] as String).substringBefore('?'),
                    ArticleState.TESTING,
                    endOfTesting
                )
            )
        }
        val more = jsonObject["more"] as JSONObject
        return more["link"] as String
    }

    @kotlinx.coroutines.ObsoleteCoroutinesApi
    fun watcherActor(db: SendChannel<DBMsg>) = GlobalScope.actor<WatcherMsg>(capacity = Channel.UNLIMITED) {
        log.info("Actor Started")
        for (msg in this.channel) {
            when (msg) {
                is WatcherMsg.CheckChannels -> {
                    val articles = mutableListOf<ZenArticle>()
                    for (zenChannel in msg.zenChannels) {
                        var answer = readUrl(queryHead + zenChannel.channelId)
                        if (answer == null) {
                            log.warning("${zenChannel.channelId} unavailable.")
                            zenChannel.state = ChannelState.UNAVAILABLE
                            continue
                        }
                        var nextLink = findArticlesAndGetNextLink(zenChannel, answer, articles)
                        if (zenChannel.state == ChannelState.NEW) {
                            answer = readUrl(nextLink)
                            while (answer != null) {
                                nextLink = findArticlesAndGetNextLink(zenChannel, answer, articles)
                                answer = readUrl(nextLink)
                            }
                            zenChannel.state = ChannelState.AVAILABLE
                        }
                    }
                    db.send(DBMsg.UpdateChannelsAndAddArticles(msg.zenChannels, articles))
                }
            }
            delay(timeout)
        }
    }
}