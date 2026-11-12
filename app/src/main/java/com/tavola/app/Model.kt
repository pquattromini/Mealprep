package com.tavola.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Stato dell'app. Menu, impostazioni e spunte della spesa restano salvati sul telefono. */
class Model(ctx: Context) {
    var catalog by mutableStateOf(Catalog.load(ctx))
    private val prefs = ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE)

    /** I giorni della settimana da pianificare: 0 = lunedì ... 6 = domenica. */
    var giorniScelti by mutableStateOf(leggiGiorni())
    var meals by mutableStateOf(prefs.getString("meals", "both") ?: "both")
    var leftover by mutableStateOf(prefs.getBoolean("leftover", true))
    var plan by mutableStateOf(loadPlan())
    var checked by mutableStateOf<Set<String>>(prefs.getStringSet("checked", emptySet())?.toSet() ?: emptySet())
    var editing by mutableStateOf(plan == null)
    var tab by mutableIntStateOf(0)
    /** Il lunedì della settimana a cui appartiene il menù mostrato (c'è dopo l'invio al calendario o dopo un recupero). */
    var inizioMenu by mutableStateOf<LocalDate?>(
        prefs.getString("inizio", null)?.let {
            try { LocalDate.parse(it) } catch (e: Exception) { null }
        }
    )

    /** Ricarica ricette e ingredienti (dopo un aggiornamento) e riadatta il menu salvato ai dati nuovi. */
    fun reload(ctx: Context) {
        catalog = Catalog.load(ctx)
        plan = loadPlan()
        if (plan == null) {
            editing = true
            inizioMenu = null
        }
    }

    /**
     * Legge i giorni salvati. Le versioni precedenti salvavano solo quanti giorni ("days"),
     * sempre a partire da lunedì: in quel caso si prendono i primi giorni della settimana.
     */
    private fun leggiGiorni(): Set<Int> {
        val salvati = prefs.getString("giorni", null)
            ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 0..6 }?.toSet()
        if (salvati != null) return salvati
        return (0 until prefs.getInt("days", 7).coerceIn(1, 7)).toSet()
    }

    /** Toglie o rimette un giorno della settimana. */
    fun toggleGiorno(d: Int) {
        giorniScelti = if (d in giorniScelti) giorniScelti - d else giorniScelti + d
    }

    fun generate() {
        if (giorniScelti.isEmpty()) return
        plan = catalog.build(giorniScelti, meals, leftover)
        inizioMenu = null
        checked = emptySet()
        tab = 0
        editing = false
        save()
    }

    fun swap(i: Int) {
        val p = plan ?: return
        plan = catalog.swap(p, i)
        save()
    }

    /** Mette in quel pasto la ricetta scelta dall'elenco. */
    fun choose(i: Int, r: Recipe) {
        val p = plan ?: return
        plan = catalog.choose(p, i, r)
        save()
    }

    /** Toglie un pasto dal menu. Se era l'ultimo rimasto, si torna alla schermata iniziale. */
    fun remove(i: Int) {
        val p = plan ?: return
        val nuovo = catalog.remove(p, i)
        plan = nuovo.ifEmpty { null }
        save()
    }

    fun toggle(id: String) {
        checked = if (id in checked) checked - id else checked + id
        save()
    }

    /** Cambia gli ingredienti di un pasto (e dei pranzi che ne riusano gli avanzi). */
    fun updateSlot(idx: Int, ing: Map<String, Double>, titolo: String? = null) {
        val p = plan ?: return
        val r = p[idx].recipe.copy(ing = ing)
        val t = titolo?.trim()?.takeIf { it.isNotEmpty() && it != r.nome }
        plan = p.mapIndexed { i, x -> if (i == idx || x.from == idx) x.copy(recipe = r, titolo = t) else x }
        save()
    }

    fun isModified(s: Slot): Boolean = catalog.byName[s.recipe.nome]?.ing != s.recipe.ing

    private fun leggiArchivio(): JSONObject =
        try { JSONObject(prefs.getString("archivio", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Salva il menù mostrato come menù di quella settimana: ne resta uno solo per settimana, l'ultimo inviato. */
    fun archivia(inizio: LocalDate) {
        val p = plan ?: return
        val arc = leggiArchivio()
        arc.put(
            inizio.toString(),
            JSONObject().put("p", encode(p)).put("m", meals).put("l", leftover),
        )
        // si tengono al massimo le ultime 12 settimane
        val chiavi = arc.keys().asSequence().toList().sorted()
        for (k in chiavi.dropLast(12)) arc.remove(k)
        prefs.edit().putString("archivio", arc.toString()).apply()
        inizioMenu = inizio
        save()
    }

    /** Le settimane con un menù salvato, dalla più recente. */
    fun settimane(): List<Settimana> {
        val arc = leggiArchivio()
        val out = ArrayList<Settimana>()
        for (k in arc.keys().asSequence().toList()) {
            try {
                val n = decode(arc.getJSONObject(k).getString("p"))?.size ?: 0
                if (n > 0) out.add(Settimana(LocalDate.parse(k), n))
            } catch (e: Exception) {
                // voce non leggibile: la salto
            }
        }
        return out.sortedByDescending { it.inizio.toEpochDay() }
    }

    /** Ricarica il menù salvato per quella settimana, con le modifiche e i titoli com'erano al momento dell'invio. */
    fun recupera(inizio: LocalDate): Boolean {
        val o = try { leggiArchivio().getJSONObject(inizio.toString()) } catch (e: Exception) { return false }
        val p = decode(o.getString("p")) ?: return false
        if (p.isEmpty()) return false
        // i giorni si ricavano dal menù stesso: vale anche per i menù salvati con le versioni precedenti
        giorniScelti = p.map { it.day }.toSet()
        meals = o.optString("m", meals)
        leftover = o.optBoolean("l", leftover)
        plan = p
        inizioMenu = inizio
        checked = emptySet()
        tab = 0
        editing = false
        save()
        return true
    }

    fun save() {
        val p = plan
        prefs.edit()
            .putString("giorni", giorniScelti.sorted().joinToString(","))
            .putString("meals", meals)
            .putBoolean("leftover", leftover)
            .putString("plan", if (p == null) null else encode(p))
            .putStringSet("checked", checked)
            .putString("inizio", inizioMenu?.toString())
            .apply()
    }

    private fun encode(p: List<Slot>): String {
        val a = JSONArray()
        for (s in p) {
            val o = JSONObject().put("d", s.day).put("m", s.meal).put("r", s.recipe.nome)
                .put("x", s.mult).put("f", s.from ?: -1)
            if (isModified(s)) {
                val io = JSONObject()
                for ((k, v) in s.recipe.ing) io.put(k, v)
                o.put("i", io)
            }
            if (s.titolo != null) o.put("t", s.titolo)
            a.put(o)
        }
        return a.toString()
    }

    private fun loadPlan(): List<Slot>? = decode(prefs.getString("plan", null))

    private fun decode(raw: String?): List<Slot>? {
        if (raw == null) return null
        return try {
            val arr = JSONArray(raw)
            val byName = catalog.recipes.associateBy { it.nome }
            val slots = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val f = o.getInt("f")
                var r = byName.getValue(o.getString("r"))
                if (o.has("i")) {
                    val io = o.getJSONObject("i")
                    r = r.copy(ing = io.keys().asSequence().associateWith { k -> io.getDouble(k) })
                }
                Slot(o.getInt("d"), o.getString("m"), r, o.getInt("x"), if (f >= 0) f else null, o.optString("t", "").takeIf { it.isNotEmpty() })
            }
            if (slots.any { s -> s.recipe.ing.keys.any { it !in catalog.ing } }) null else slots
        } catch (e: Exception) {
            null
        }
    }
}

/** Una settimana (con il suo lunedì) per cui c'è un menù salvato. */
data class Settimana(val inizio: LocalDate, val pasti: Int)
