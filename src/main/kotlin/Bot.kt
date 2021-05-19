import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.GetUpdates
import com.pengrad.telegrambot.request.SendMessage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger
import myutils.*
import java.lang.StringBuilder
import java.util.regex.Pattern

sealed class BotMsg {
    object Chat : BotMsg()
    data class NotifyAuthorAdded(val user: ZenUser): BotMsg()
    data class NotifyAuthorRemoved(val user: ZenUser, val removed: Boolean): BotMsg()
    data class SendAuthorsList(val authors: List<ZenUser>): BotMsg()
    data class NotifyArticlesFound(val userId: Int, val articles: List<ZenArticle>): BotMsg()
    data class NotifyChannelAdded(val user: ZenUser, val channelId: String): BotMsg()
    data class RemindAdmin(val message: String): BotMsg()
    data class NotifyChannelRemoved(val userId: Int, val channelUrl: String, val removed: Boolean): BotMsg()
    data class NotifyArticleBanned(val userId: Int, val title: String, val url: String): BotMsg()
}

class Bot(private val pwd: String, token: String) {
    private val bot = TelegramBot(token)
    private var adminId = -1
    private var offset = 0
    private val log = Logger.getLogger("BotActor")
    private val pattern = Pattern.compile("id/[a-zA-Z0-9]*")

    private fun sendMessage(userId: Int, text: String) = bot.execute(SendMessage(userId, text))

    private fun actionWithChannelId(url: String, userId: Int, action: (String) -> Unit) {
        val matcher = this.pattern.matcher(url)
        if (matcher.find()) {
            action(url.substring(matcher.start() + 3, matcher.end()))
        } else
            sendMessage(userId, "Неправильный url.")
    }

    private fun expressMisunderstanding(userId: Int) {
        this.sendMessage(userId, "Я Вас не понимаю.")
        val commandsList = """Список команд:
            |Пароль <password>
            |Добавить <username>
            |Удалить <username>
            |Список авторов
            |Список статей
            |Отслеживать <url>
            |Забыть <url>
            |----
            |NB!: <username> должен писаться без символа @.
        """.trimMargin()
        this.sendMessage(userId, commandsList)
    }

    @kotlinx.coroutines.ObsoleteCoroutinesApi
    fun botActor(db: SendChannel<DBMsg>) = GlobalScope.actor<BotMsg>(capacity = Channel.UNLIMITED) {
        log.info("Actor Started.")
        for (msg in this.channel)
            when (msg) {
                is BotMsg.Chat -> {
                    log.info("Chatting.")
                    val getUpdates = GetUpdates().offset(offset).timeout(5)
                    val response = bot.execute(getUpdates)
                    for (update in response.updates()) {
                        offset = update.updateId() + 1
                        val message = update.message()
                        val cmd = message.text().split(' ', limit = 2)
                        val userId = message.from().id()
                        when (cmd.first().toLowerCase()) {
                            "пароль" -> {
                                var text = "Не имею чести Вас знать."
                                if (cmd.last() == pwd) {
                                    adminId = userId
                                    text = "Слушаю."
                                    db.send(DBMsg.AddToAuthors(message.from().username()))
                                }
                                sendMessage(userId, text)
                            }
                            "добавить" -> {
                                if (userId == adminId)
                                    db.send(DBMsg.AddToAuthors(cmd.last()))
                                else
                                    sendMessage(userId, "Вам такое нельзя.")
                            }
                            "удалить" -> {
                                if (userId == adminId)
                                    db.send(DBMsg.RemoveFromAuthors(cmd.last()))
                                else
                                    sendMessage(userId, "Вам такое нельзя.")
                            }
                            "список" ->
                                when (cmd.last().toLowerCase()) {
                                    "авторов" -> {
                                        if (userId == adminId)
                                            db.send(DBMsg.GetAuthorsList)
                                        else
                                            sendMessage(userId, "Вам такое нельзя.")
                                    }
                                    "статей" -> db.send(DBMsg.GetArticlesList(userId))
                                    else -> expressMisunderstanding(userId)
                                }
                            "отслеживать" -> actionWithChannelId(cmd.last(), userId) {
                                x -> runBlocking {
                                    db.send(DBMsg.AddToChannels(message.from().username(), userId, x))
                                }
                            }
                            "забыть" -> actionWithChannelId(cmd.last(), userId) {x ->
                                runBlocking {db.send(DBMsg.RemoveFromChannels(userId, x))}
                            }
                            else -> expressMisunderstanding(userId)
                        }
                    }
                    this.channel.offer(BotMsg.Chat)
                }
                is BotMsg.NotifyAuthorAdded -> {
                    log.info("${msg.user.username} added to authors.")
                    sendMessage(adminId, "${msg.user.username} добавлен.")
                    if (msg.user.rights == UserRights.WAITING)
                        sendMessage(msg.user.userId, "Ваши каналы отслеживаются.")
                }
                is BotMsg.NotifyAuthorRemoved -> {
                    log.info("${msg.user.username} removed from authors.")
                    if (msg.removed) {
                        sendMessage(adminId, "${msg.user.username} удален.")
                        sendMessage(msg.user.userId, "Ваши каналы не отслеживаются по приказу администратора.")
                    } else
                        sendMessage(adminId, "${msg.user.username} не найден.")
                }
                is BotMsg.SendAuthorsList -> {
                    log.info("Got authors list.")
                    val builder = StringBuilder()
                    builder.appendLine("Авторы:")
                    for (user in msg.authors)
                        builder.appendLine(user.username)
                    builder.appendLine("-----")
                    builder.appendLine("Всего ${msg.authors.size}")
                    sendMessage(adminId, builder.toString())
                }
                is BotMsg.NotifyArticlesFound -> {
                    log.info("Found articles for ${msg.userId}.")
                    if (msg.articles.isNotEmpty()) {
                        val builder = StringBuilder()
                        builder.appendLine("Статьи:")
                        for (article in msg.articles) {
                            val state = when (article.state) {
                                ArticleState.TESTED -> "проверена"
                                ArticleState.TESTING -> "проверяется"
                                ArticleState.BANNED -> "заблокирована"
                                ArticleState.UNAVAILABLE -> "временно недоступна"
                            }
                            builder.appendLine("- ${article.title} -- $state.")
                        }
                        builder.appendLine("-----")
                        builder.appendLine("Всего ${msg.articles.size}")
                        sendMessage(msg.userId, builder.toString())
                    } else
                        sendMessage(msg.userId, "Нет статей.")
                }
                is BotMsg.NotifyChannelAdded -> {
                    log.info("Added ${msg.channelId} to user ${msg.user.username} channels.")
                    val response = when (msg.user.rights) {
                        UserRights.UNKNOWN_USER -> {
                            this.channel.offer(BotMsg.RemindAdmin("${msg.user.username} хочет отслеживать свои каналы."))
                            "Жду подтверждения от администратора."
                        }
                        UserRights.AUTHOR -> "${msg.channelId} отслеживается."
                        UserRights.WAITING ->"Ваши каналы не отслеживаются по приказу администратора, либо подтверждения еще нет."
                    }
                    sendMessage(msg.user.userId, response)
                }
                is BotMsg.RemindAdmin -> {
                    log.info("Try to remind admin about something.")
                    if (adminId > 0)
                        sendMessage(adminId, msg.message)
                    else
                        this.channel.offer(msg)
                }
                is BotMsg.NotifyChannelRemoved -> {
                    log.info("Removed ${msg.channelUrl} from user ${msg.userId} channels.")
                    val response =
                        if (msg.removed)
                            "${msg.channelUrl} не отслеживается."
                        else
                            "Такого канала у Вас нет."
                    sendMessage(msg.userId, response)
                }
                is BotMsg.NotifyArticleBanned -> {
                    log.info("${msg.title} is banned.")
                    sendMessage(msg.userId, "Статья ${msg.title} заблокирована.\n${msg.url}")
                }
            }
    }
}