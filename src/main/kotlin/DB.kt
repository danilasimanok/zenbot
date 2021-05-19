import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.util.logging.Logger
import myutils.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.date
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

object Users: Table() {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 128)
    val telegramId: Column<Int> = integer("telegram_id")
    val rights: Column<Int> = integer("rights")
    override val primaryKey = PrimaryKey(id)
}

object Channels: Table() {
    val id: Column<Int> = integer("id").autoIncrement()
    val authorId: Column<Int> = reference("author_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val zenId: Column<String> = varchar("channel_id", 64)
    val state: Column<Int> = integer("state")
    override val primaryKey = PrimaryKey(id)
}

object Articles: Table() {
    private val id: Column<Int> = integer("id").autoIncrement()
    val channelId: Column<Int> = reference("channel_id", Channels.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 256)
    val url: Column<String> = varchar("url", 512)
    val state: Column<Int> = integer("state")
    val endOfTesting: Column<LocalDate> = date("end_of_testing")
    override val primaryKey = PrimaryKey(id)
}

sealed class DBMsg {
    data class AddToAuthors(val username: String): DBMsg()
    data class RemoveFromAuthors(val username: String): DBMsg()
    object GetAuthorsList : DBMsg()
    data class GetArticlesList(val userId: Int): DBMsg()
    data class AddToChannels(val username: String, val userId: Int, val channelId: String): DBMsg()
    data class RemoveFromChannels(val userId: Int, val channelId: String): DBMsg()
    data class UpdateChannelsAndAddArticles(val zenChannels: List<ZenChannel>, val articlesList: List<ZenArticle>): DBMsg()
    data class UpdateArticles(val articles: List<ZenArticle>): DBMsg()
}

fun queryToZenUsers(query: Query) = query.map {
    ZenUser(
        it[Users.telegramId],
        it[Users.name],
        UserRights.fromInt(it[Users.rights])
    )
}

fun queryToZenArticle(query: Query) = query.map {
    ZenArticle(
        it[Users.telegramId],
        it[Channels.zenId],
        it[Articles.title],
        it[Articles.url],
        ArticleState.fromInt(it[Articles.state]),
        it[Articles.endOfTesting]
    )
}

@kotlinx.coroutines.ObsoleteCoroutinesApi
fun dbActor(path: String, interlocutors: Channel<Triple<SendChannel<BotMsg>, SendChannel<TesterMsg>, SendChannel<WatcherMsg>>>) =
    GlobalScope.actor<DBMsg>(capacity = Channel.UNLIMITED) {
        val log = Logger.getLogger("DB Actor")
        Database.connect("jdbc:sqlite:$path") //there can be only one :)
        transaction {
            SchemaUtils.create(Users, Channels, Articles)
        }
        val (bot, tester, watcher) = interlocutors.receive()

        log.info("Actor Started.")
        for (msg in this.channel) {
            when (msg) {
                is DBMsg.AddToAuthors -> {
                    log.info("Add ${msg.username} to authors.")
                    val user = transaction {
                        val query = Users.select {Users.name eq msg.username}
                        if (query.empty()) {
                            Users.insert {
                                it[telegramId] = -1
                                it[rights] = UserRights.AUTHOR.ordinal
                                it[name] = msg.username
                            }
                            ZenUser(-1, msg.username, UserRights.UNKNOWN_USER)
                        } else {
                            Users.update ({Users.name eq msg.username}) {
                                it[rights] = UserRights.AUTHOR.ordinal
                            }
                            queryToZenUsers(query).first()
                        }
                    }
                    bot.send(BotMsg.NotifyAuthorAdded(user))
                }
                is DBMsg.RemoveFromAuthors -> {
                    log.info("Remove ${msg.username} from authors.")
                    val (user, removed) = transaction {
                        val users = queryToZenUsers(Users.select {Users.name eq msg.username})
                        if (users.isEmpty())
                            Pair(ZenUser(-1, msg.username, UserRights.UNKNOWN_USER), false)
                        else {
                            Users.deleteWhere {Users.name eq msg.username}
                            val zenUser = users.first()
                            Pair(zenUser, true)
                        }
                    }
                    bot.send(BotMsg.NotifyAuthorRemoved(user, removed))
                }
                is DBMsg.GetAuthorsList -> {
                    log.info("Get authors list.")
                    val list = transaction {
                        val query = Users.select {Users.rights eq UserRights.AUTHOR.ordinal}
                        queryToZenUsers(query)
                    }
                    bot.send(BotMsg.SendAuthorsList(list))
                }
                is DBMsg.GetArticlesList -> {
                    log.info("Get articles list for user ${msg.userId}.")
                    val list = transaction {
                        val query = Users.join(Channels, JoinType.INNER, Users.id, Channels.authorId).
                                join(Articles, JoinType.INNER, Channels.id, Articles.channelId).select {
                                    Users.telegramId eq msg.userId
                        }
                        queryToZenArticle(query)
                    }
                    bot.send(BotMsg.NotifyArticlesFound(msg.userId, list))
                }
                is DBMsg.AddToChannels -> {
                    log.info("Add ${msg.channelId} to user ${msg.userId} channels.")
                    val user = transaction {
                        val query = Users.select {Users.name eq msg.username}.limit(1) //Переделать с single()!
                        if (query.empty()) {
                            val id = Users.insert {
                                it[name] = msg.username
                                it[telegramId] = msg.userId
                                it[rights] = UserRights.UNKNOWN_USER.ordinal
                            } get Users.id
                            Channels.insert {
                                it[authorId] = id
                                it[zenId] = msg.channelId
                                it[state] = ChannelState.NEW.ordinal
                            }
                            ZenUser(msg.userId, msg.username, UserRights.UNKNOWN_USER)
                        } else {
                            val user = query.first()
                            if (user[Users.telegramId] < 0)
                                Users.update({Users.name eq msg.username}) {it[telegramId] = msg.userId}
                            Channels.insert {
                                it[authorId] = query.first()[Users.id]
                                it[zenId] = msg.channelId
                                it[state] = ChannelState.NEW.ordinal
                            }
                            queryToZenUsers(query).first()
                        }
                    }
                    bot.send(BotMsg.NotifyChannelAdded(user, msg.channelId))
                }
                is DBMsg.RemoveFromChannels -> {
                    log.info("Remove ${msg.channelId} from user ${msg.userId} channels.")
                    val result = transaction {
                        val query = Users.select {Users.telegramId eq msg.userId}.limit(1)
                        if (query.empty())
                            false
                        else {
                            val count = Channels.deleteWhere {
                                (Channels.authorId eq query.first()[Users.id]) and
                                        (Channels.zenId eq msg.channelId)
                            }
                            count > 0
                        }
                    }
                    bot.send(BotMsg.NotifyChannelRemoved(msg.userId, msg.channelId, result))
                }
                is DBMsg.UpdateChannelsAndAddArticles -> {
                    log.info("Update channels and add articles.")
                    val urls = transaction { Articles.selectAll().map{ it[Articles.url] } }
                    val articles = msg.articlesList.filter { !urls.contains(it.url) }
                    transaction {
                        val channels = Channels.select { Channels.state less 5 } //заменить на константу!
                        val channelsMap = channels.associateBy({it[Channels.zenId]}, {it[Channels.id] to it[Channels.state]})
                        for (article in articles) {
                            val zenId = (channelsMap[article.channelId] ?: continue).first
                            Articles.insert {
                                it[channelId] = zenId
                                it[title] = article.title
                                it[url] = article.url
                                it[state] = article.state.ordinal
                                it[endOfTesting] = article.endOfTesting
                            }
                        }
                        for (channel in msg.zenChannels) {
                            val previousState = (channelsMap[channel.channelId] ?: continue).second
                            val newState =
                                if (channel.state == ChannelState.UNAVAILABLE)
                                    previousState + 1
                                else
                                    channel.state.ordinal
                            Channels.update({Channels.zenId eq channel.channelId}) {it[state] = newState}
                        }
                    }
                    val zenChannels = transaction {
                        Channels.select { Channels.state less 5 }.map {
                            ZenChannel(
                                it[Channels.authorId],
                                it[Channels.zenId],
                                ChannelState.fromInt(it[Channels.state])
                            )
                        }
                    }
                    watcher.send(WatcherMsg.CheckChannels(zenChannels))
                }
                is DBMsg.UpdateArticles -> {
                    log.info("Update articles")
                    for (article in msg.articles) {
                        log.info("Article: ${article.title} -- ${article.state}")
                        transaction {
                            Articles.update({Articles.url eq article.url}) {it[state] = article.state.ordinal}
                        }
                    }
                    val response = transaction {
                        val articles = Users.join(Channels, JoinType.INNER, additionalConstraint = {Channels.authorId eq Users.id}).
                                join(Articles, JoinType.INNER, additionalConstraint = {Articles.channelId eq Channels.id}).
                                select {
                                    (Users.rights eq UserRights.AUTHOR.ordinal) and (Channels.state less 5) and
                                            ((Articles.state eq ArticleState.TESTING.ordinal) or
                                            (Articles.state eq ArticleState.UNAVAILABLE.ordinal))
                                }
                        queryToZenArticle(articles)
                    }
                    tester.send(TesterMsg.CheckArticles(response))
                }
            }
        }
    }