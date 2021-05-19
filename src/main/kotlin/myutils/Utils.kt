package myutils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URL
import java.time.LocalDate
import java.util.*
import javax.net.ssl.HttpsURLConnection

enum class UserRights {
    UNKNOWN_USER, AUTHOR, WAITING;
    companion object {
        fun fromInt(i: Int) = when (i) {
            0 -> UNKNOWN_USER
            1 -> AUTHOR
            2 -> WAITING
            else -> throw IllegalArgumentException("Unexpected rights value.")
        }
    }
}

enum class ChannelState {
    NEW, AVAILABLE, UNAVAILABLE;
    companion object {
        fun fromInt(i: Int) = when (i) {
            0 -> NEW
            1 -> AVAILABLE
            else -> UNAVAILABLE
        }
    }
}

enum class ArticleState {
    TESTING, TESTED, BANNED, UNAVAILABLE;
    companion object {
        fun fromInt(i: Int) = when (i) {
            0 -> TESTING
            1 -> TESTED
            2 -> BANNED
            3 -> UNAVAILABLE
            else -> throw IllegalArgumentException("Unexpected article state value.")
        }
    }
}

data class ZenUser(val userId: Int, val username: String, var rights: UserRights)
data class ZenChannel(val authorId: Int, val channelId: String, var state: ChannelState)
data class ZenArticle(val authorId: Int, val channelId: String, val title: String, val url: String, var state: ArticleState, val endOfTesting: LocalDate)

suspend fun readUrl(urlString: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpsURLConnection
        if (connection.responseCode != 200)
            null
        else {
            val scanner = Scanner(connection.inputStream)
            scanner.useDelimiter("\\Z")
            val result = scanner.next()
            scanner.close()
            result
        }
    } catch (exn: Exception) {
        null
    }
}