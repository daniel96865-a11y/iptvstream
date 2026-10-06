package de.dgstudios.iptvstream.core.data.remote

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Ein einziger Retrofit-Endpunkt: jede URL wird per @Url übergeben und gestreamt. */
interface HttpService {
    @Streaming
    @GET
    suspend fun get(@Url url: String): ResponseBody
}

object Net {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
            } else {
                chain.proceed(req)
            }
        }
        .build()

    /** Verständliche deutsche Fehlermeldung. */
    fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException -> "Server nicht erreichbar. Adresse und Internetverbindung prüfen."
        is SocketTimeoutException -> "Zeitüberschreitung bei der Verbindung zum Server."
        is SSLException -> "Sichere Verbindung fehlgeschlagen. Versuche http:// statt https://."
        is HttpException -> when (e.code()) {
            401, 403 -> "Zugriff verweigert (HTTP ${e.code()}). Zugangsdaten prüfen."
            404 -> "Adresse nicht gefunden (HTTP 404). Server-URL prüfen."
            in 500..599 -> "Serverfehler (HTTP ${e.code()}). Später erneut versuchen."
            else -> "Serverfehler (HTTP ${e.code()})."
        }
        is IOException -> "Netzwerkfehler: ${e.message ?: "Verbindung unterbrochen"}"
        is IllegalArgumentException -> e.message ?: "Ungültige Eingabe."
        else -> e.message ?: "Unbekannter Fehler."
    }
}

/** Hilfen zum Streamen großer JSON-Arrays ohne die ganze Antwort im Speicher zu halten. */
object Json {
    /** Liest ein flaches Objekt; verschachtelte Werte werden übersprungen. */
    fun readFlat(r: JsonReader): Map<String, String?> {
        val m = HashMap<String, String?>(24)
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[name] = r.nextString()
                JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                JsonToken.NULL -> {
                    r.nextNull()
                    m[name] = null
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    /** Ruft [onRecord] für jedes Objekt eines Arrays (oder der Werte eines Objekts) auf. */
    inline fun forEachRecord(r: JsonReader, onRecord: (Map<String, String?>) -> Unit) {
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) onRecord(readFlat(r)) else r.skipValue()
                }
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName()
                    if (r.peek() == JsonToken.BEGIN_OBJECT) onRecord(readFlat(r)) else r.skipValue()
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }
}
