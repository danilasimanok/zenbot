import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

@kotlinx.coroutines.ObsoleteCoroutinesApi
fun main(args: Array<String>) = runBlocking {
    val selector = ActorSelectorManager(Dispatchers.IO)
    val address = InetSocketAddress("127.0.0.1", 2323)
    when(args.size) {
        1 -> {
            println("Бот останавливается.")
            val password = args[0]
            val client = aSocket(selector).tcp().connect(address)
            val output = client.openWriteChannel(autoFlush = true)
            output.writeStringUtf8(password)
            output.close()
            client.close()
        }
        2 -> {
            println("Бот запускается.")
            val password = args[0]
            val dbName = args[1]

            val channel =
                Channel<Triple<SendChannel<BotMsg>, SendChannel<TesterMsg>, SendChannel<WatcherMsg>>>()
            val db = dbActor(dbName, channel)
            val bot = Bot(password, "TOKEN").botActor(db)
            val watcher = Watcher(10000).watcherActor(db)
            val tester = Tester(2500).testerActor(db, bot)
            channel.send(Triple(bot, tester, watcher))
            bot.send(BotMsg.Chat)
            tester.send(TesterMsg.CheckArticles(listOf()))
            watcher.send(WatcherMsg.CheckChannels(listOf()))
            channel.close()

            val server = aSocket(selector).tcp().bind(address)
            var passwordIsCorrect = false
            while (!passwordIsCorrect) {
                val socket = server.accept()
                val input = socket.openReadChannel()
                val got = input.readUTF8Line()
                passwordIsCorrect = if (got != null) got == password else false
                socket.close()
            }
            server.close()

            bot.close()
            tester.close()
            db.close()

            println("Бот остановлен.")
        }
        else ->
            println("Неправильные аргументы.")
    }
}