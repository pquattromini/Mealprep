package com.tavola.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Bring! non accetta elenchi diretti da altre app: importa "ricette" da una pagina web.
 * Qui la lista della spesa viene presentata come ricetta tramite una piccola pagina (il Worker),
 * poi si chiede a Bring un link di importazione che l'app apre.
 */
object BringSync {
    fun deeplink(workerUrl: String, righe: List<String>, done: (link: String?, errore: String?) -> Unit) {
        Thread {
            var link: String? = null
            var err: String? = null
            try {
                val base = workerUrl.trim().trimEnd('/')
                val pagina = base + "/?t=" + URLEncoder.encode("Spesa MealYourWeek", "UTF-8") +
                    "&i=" + URLEncoder.encode(righe.joinToString("\n"), "UTF-8")
                val body = JSONObject().put("url", pagina).put("baseQuantity", "1").put("requestedQuantity", "1").toString()
                val c = URL("https://api.getbring.com/rest/bringrecipes/deeplink").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (c.responseCode == 200) {
                    val d = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("deeplink")
                    if (d.isNotEmpty()) link = d else err = "Bring non ha restituito il link di importazione."
                } else {
                    err = "Bring ha risposto con l’errore ${c.responseCode}. Controlla l’indirizzo del Worker."
                }
            } catch (e: Exception) {
                err = "Connessione non riuscita. Controlla internet e l’indirizzo del Worker."
            }
            Handler(Looper.getMainLooper()).post { done(link, err) }
        }.start()
    }
}
