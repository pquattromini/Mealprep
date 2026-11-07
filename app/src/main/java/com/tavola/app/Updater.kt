package com.tavola.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Scarica da internet (per esempio da GitHub) la versione più recente di ricette e ingredienti. */
object Updater {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        if (c.responseCode != 200) {
            throw IllegalStateException("File non trovato (errore ${c.responseCode}). Controlla l’indirizzo e che il repository sia pubblico.")
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Scarica i due file, li controlla e, se sono validi e diversi da quelli in uso, li salva.
     * Se c'è un errore (file sbagliato, ingrediente mancante...) non cambia nulla e spiega il problema.
     */
    fun aggiorna(ctx: Context, indirizzo: String, done: (cambiato: Boolean, messaggio: String?, errore: String?) -> Unit) {
        Thread {
            var cambiato = false
            var msg: String? = null
            var err: String? = null
            try {
                val u = indirizzo.trim()
                val base = if (u.endsWith(".json")) u.substringBeforeLast('/') + "/" else u.trimEnd('/') + "/"
                val ing = get(base + "ingredients.json")
                val rec = get(base + "recipes.json")
                val cat = Catalog.parse(ing, rec)
                val fi = File(ctx.filesDir, "ingredients.json")
                val fr = File(ctx.filesDir, "recipes.json")
                val uguale = fi.exists() && fr.exists() && fi.readText() == ing && fr.readText() == rec
                if (!uguale) {
                    fi.writeText(ing)
                    fr.writeText(rec)
                    cambiato = true
                }
                msg = if (cambiato) "Aggiornate: ${cat.recipes.size} ricette e ${cat.ing.size} ingredienti." else "Era già tutto aggiornato."
            } catch (e: IOException) {
                err = "Connessione non riuscita. Controlla internet e l’indirizzo."
            } catch (e: Exception) {
                err = e.message ?: "Aggiornamento non riuscito."
            }
            Handler(Looper.getMainLooper()).post { done(cambiato, msg, err) }
        }.start()
    }
}
