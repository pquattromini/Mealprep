package com.tavola.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    /** Il nome con cui il menù mostrato è salvato nel telefono (se viene da lì o è stato salvato): si propone per risalvarlo. */
    var nomeMenu by mutableStateOf(prefs.getString("nomeMenu", null))
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
        nomeMenu = null
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

    /** Dopo l'invio al calendario: il menù mostrato è quello della settimana che parte da quel lunedì. */
    fun inviato(inizio: LocalDate) {
        inizioMenu = inizio
        save()
    }

    // ---- Menù salvati nel telefono ----
    // Si salvano solo quando lo chiedi tu (tasto "Salva nel telefono"), con un nome. Sono in "salvati":
    // un oggetto JSON con, per ogni menù, n = nome, w = quando è stato salvato, p = il menù, m e l = impostazioni dei pasti,
    // s = il lunedì della settimana (se il menù ne aveva una), v = true per quelli venuti dal vecchio archivio.

    private fun leggiSalvati(): JSONObject {
        val tutti = try { JSONObject(prefs.getString("salvati", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        // Le versioni precedenti salvavano da sole un menù per settimana all'invio al calendario ("archivio"):
        // la prima volta diventano menù salvati nel telefono, con il nome della settimana.
        val vecchio = prefs.getString("archivio", null)
        if (vecchio != null) {
            try {
                val arc = JSONObject(vecchio)
                for (k in arc.keys().asSequence().toList()) {
                    val a = arc.getJSONObject(k)
                    val lunedi = LocalDate.parse(k)
                    tutti.put(
                        "archivio-$k",
                        JSONObject()
                            .put("n", "Settimana dal " + lunedi.format(GIORNO_MESE))
                            .put("w", lunedi.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                            .put("p", a.getString("p"))
                            .put("m", a.optString("m", "both"))
                            .put("l", a.optBoolean("l", true))
                            .put("s", k)
                            .put("v", true),
                    )
                }
            } catch (e: Exception) {
                // archivio non leggibile: non c'è niente da recuperare
            }
            prefs.edit().putString("salvati", tutti.toString()).remove("archivio").apply()
        }
        return tutti
    }

    /** I menù salvati nel telefono, dal più recente. */
    fun salvati(): List<MenuSalvato> {
        val tutti = leggiSalvati()
        val out = ArrayList<MenuSalvato>()
        for (k in tutti.keys().asSequence().toList()) {
            val o = tutti.optJSONObject(k) ?: continue
            // se le ricette sono cambiate e il menù non si può più leggere, resta nell'elenco per poterlo eliminare
            val p = decode(o.optString("p", ""))
            out.add(
                MenuSalvato(
                    id = k,
                    nome = o.optString("n", "Menu senza nome"),
                    quando = o.optLong("w", 0L),
                    dalVecchioArchivio = o.optBoolean("v", false),
                    pasti = p?.size ?: 0,
                    leggibile = !p.isNullOrEmpty(),
                )
            )
        }
        return out.sortedByDescending { it.quando }
    }

    /** Salva il menù mostrato con quel nome. Se c'è già un menù con lo stesso nome (maiuscole a parte), lo sostituisce. */
    fun salvaLocale(nome: String): Boolean {
        val p = plan ?: return false
        val n = nome.trim()
        if (n.isEmpty()) return false
        val tutti = leggiSalvati()
        for (k in tutti.keys().asSequence().toList()) {
            if (tutti.optJSONObject(k)?.optString("n", "").equals(n, ignoreCase = true)) tutti.remove(k)
        }
        val adesso = System.currentTimeMillis()
        val o = JSONObject().put("n", n).put("w", adesso).put("p", encode(p)).put("m", meals).put("l", leftover)
        val ini = inizioMenu
        if (ini != null) o.put("s", ini.toString())
        tutti.put("m$adesso", o)
        prefs.edit().putString("salvati", tutti.toString()).apply()
        nomeMenu = n
        save()
        return true
    }

    /** Ricarica un menù salvato nel telefono, con le modifiche e i titoli com'erano quando l'hai salvato. */
    fun recuperaLocale(id: String): Boolean {
        val o = leggiSalvati().optJSONObject(id) ?: return false
        val p = decode(o.optString("p", "")) ?: return false
        if (p.isEmpty()) return false
        val s = o.optString("s", "")
        val lunedi = if (s.isEmpty()) null else try { LocalDate.parse(s) } catch (e: Exception) { null }
        applica(p, o.optString("m", meals), o.optBoolean("l", leftover), lunedi, o.optString("n", "").ifEmpty { null })
        return true
    }

    fun eliminaLocale(id: String) {
        val tutti = leggiSalvati()
        tutti.remove(id)
        prefs.edit().putString("salvati", tutti.toString()).apply()
    }

    /** Mostra il menù ricostruito dal calendario per quella settimana. */
    fun recuperaDaCalendario(mc: MenuCalendario): Boolean {
        val p = mc.pasti
        if (p.isEmpty()) return false
        val pranzi = p.any { it.meal == "Pranzo" }
        val cene = p.any { it.meal == "Cena" }
        val pasti = if (pranzi && cene) "both" else if (pranzi) "pranzo" else "cena"
        applica(p, pasti, if (pranzi && cene) p.any { it.from != null } else leftover, mc.lunedi, null)
        return true
    }

    private fun applica(p: List<Slot>, pasti: String, avanzi: Boolean, lunedi: LocalDate?, nome: String?) {
        // i giorni si ricavano dal menù stesso
        giorniScelti = p.map { it.day }.toSet()
        meals = pasti
        leftover = avanzi
        plan = p
        inizioMenu = lunedi
        nomeMenu = nome
        checked = emptySet()
        tab = 0
        editing = false
        save()
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
            .putString("nomeMenu", nomeMenu)
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

/** "9 novembre": per i nomi proposti ai menù salvati. */
val GIORNO_MESE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.ITALY)

/** Un menù salvato nel telefono, per l'elenco da cui recuperarlo. */
data class MenuSalvato(
    val id: String,
    val nome: String,
    val quando: Long,
    val dalVecchioArchivio: Boolean,
    val pasti: Int,
    val leggibile: Boolean,
)
