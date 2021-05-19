import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import java.net.URL
import java.util.logging.Logger
import java.util.regex.Pattern
import myutils.*
import java.time.LocalDate

sealed class TesterMsg {
    class CheckArticles(val articles: List<ZenArticle>): TesterMsg()
}

class Tester(private val timeout: Long) {
    private val log = Logger.getLogger("Tester")
    private val pattern = Pattern.compile("<meta\\s+name=\"robots\"\\s+content=\"noindex\"/>")

    private suspend fun checkArticle(link: String): ArticleState {
        try {
            val answer = readUrl(link) ?: return ArticleState.UNAVAILABLE
            val matcher = pattern.matcher(answer)
            return if (matcher.find())
                ArticleState.BANNED
            else
                ArticleState.TESTING
        } catch (exn: Exception) {
            return ArticleState.UNAVAILABLE
        }
    }

    @kotlinx.coroutines.ObsoleteCoroutinesApi
    fun testerActor(db: SendChannel<DBMsg>, bot: SendChannel<BotMsg>) =
        GlobalScope.actor<TesterMsg>(capacity = Channel.UNLIMITED) {
            log.info("Actor Started")
            for (msg in this.channel) {
                when (msg) {
                    is TesterMsg.CheckArticles -> {
                        for (article in msg.articles) {
                            val current = LocalDate.now()
                            if (current.isAfter(article.endOfTesting))
                                article.state = ArticleState.TESTED
                            else {
                                article.state = checkArticle(article.url)
                                if (article.state == ArticleState.BANNED)
                                    bot.send(BotMsg.NotifyArticleBanned(article.authorId, article.title, article.url))
                            }
                            delay(timeout)
                        }
                        db.send(DBMsg.UpdateArticles(msg.articles))
                        delay(timeout)
                    }
                }
            }
        }
}