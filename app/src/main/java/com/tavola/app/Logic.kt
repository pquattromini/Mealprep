package com.tavola.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.random.Random

// ---- Impostazioni ----
const val QUOTA = 0.35        // ogni pasto (pranzo o cena) copre il 35% delle calorie del giorno
const val OLIO = 80.0         // kcal di olio e condimenti contate a ogni pasto
const val GRAVIDANZA = true   // metti false dopo il parto: riattiva le ricette sconsigliate

val CARB = setOf("pasta", "riso", "quinoa", "pane", "panini", "wrap", "pizza", "patate", "gnocchi", "polenta", "farro", "tortellini")
val PROT = setOf("pollo", "tacchino", "cosce", "manzo", "maiale", "macinato", "salmone", "merluzzo", "gamberi", "tofu", "ricotta", "yogurt")
val REPARTI = listOf("Ortofrutta", "Carne e pesce", "Frigo", "Panetteria", "Dispensa")

// Parola usata nei titoli dei piatti: solo per i macroingredienti (i condimenti non cambiano il titolo).
// Per un ingrediente nuovo si può aggiungere in ingredients.json il campo "titolo".
val PAROLE_TITOLO = mapOf(
    "pollo" to "pollo", "tacchino" to "tacchino", "cosce" to "cosce di pollo", "manzo" to "manzo", "macinato" to "manzo",
    "maiale" to "maiale", "salsiccia" to "salsiccia", "pancetta" to "pancetta", "prosciutto" to "prosciutto",
    "salmone" to "salmone", "merluzzo" to "merluzzo", "gamberi" to "gamberi", "tonno" to "tonno", "uova" to "uova",
    "tofu" to "tofu", "ricotta" to "ricotta", "mozzarella" to "mozzarella",
    "pasta" to "pasta", "riso" to "riso", "quinoa" to "quinoa", "pane" to "pane", "patate" to "patate",
    "gnocchi" to "gnocchi", "polenta" to "polenta", "farro" to "farro", "tortellini" to "tortellini",
    "ceci" to "ceci", "lenticchie" to "lenticchie", "fagioli" to "fagioli",
    "zucchine" to "zucchine", "melanzane" to "melanzane", "peperoni" to "peperoni", "spinaci" to "spinaci",
    "funghi" to "funghi", "pomodori" to "pomodori", "insalata" to "insalata", "avocado" to "avocado",
    "broccoli" to "broccoli", "cavolfiore" to "cavolfiore", "piselli" to "piselli", "zucca" to "zucca",
    "fagiolini" to "fagiolini", "pesto" to "pesto",
)
val GIORNI = listOf("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica")

data class Person(
    val nome: String, val maschio: Boolean, val eta: Int, val kg: Double, val cm: Double,
    val attivita: Double, val extra: Double,
) {
    // Mifflin-St Jeor x livello di attività + extra (gravidanza)
    val kcal: Int
        get() {
            val bmr = 10 * kg + 6.25 * cm - 5 * eta + (if (maschio) 5 else -161)
            return (Math.round((bmr * attivita + extra) / 10) * 10).toInt()
        }
}

val PERSONE = listOf(
    Person("Lui", true, 36, 90.0, 190.0, 1.55, 0.0),
    Person("Lei", false, 36, 45.0, 165.0, 1.375, 340.0),
)

data class Ingredient(
    val id: String, val nome: String, val unita: String, val prezzo: Double,
    val reparto: String, val carbo: Double, val proteine: Double, val grassi: Double,
    val tipo: String = "",   // facoltativo: "carbo" o "proteine"
    val parola: String = "", // facoltativo: parola per i titoli dei piatti (solo macroingredienti)
)

data class Recipe(val nome: String, val giornoDopo: Boolean, val evitaGravidanza: Boolean, val ing: Map<String, Double>)

data class Macros(val c: Double, val p: Double, val f: Double) {
    val kcal: Double get() = 4 * (c + p) + 9 * f
    operator fun plus(o: Macros) = Macros(c + o.c, p + o.p, f + o.f)
    operator fun times(x: Double) = Macros(c * x, p * x, f * x)
}

data class Slot(val day: Int, val meal: String, val recipe: Recipe, val mult: Int, val from: Int?, val titolo: String? = null)

/** Aggiunto al nome del piatto negli eventi del calendario per i pranzi con gli avanzi. */
const val SUFFISSO_AVANZI = " (avanzi)"

/**
 * Un pasto letto da un evento del calendario. I campi che possono mancare (null) mancano negli eventi
 * messi dalle versioni precedenti dell'app, che avevano solo il titolo.
 */
data class PastoCalendario(
    val data: LocalDate,
    val ora: Int,
    val titolo: String,
    val ricetta: String? = null,
    val pasto: String? = null,
    val avanzi: Boolean? = null,
    val titoloMio: String? = null,
    val ingredienti: Map<String, Double>? = null,
)

/** Il menu di una settimana ricostruito dal calendario, e quanti pasti non corrispondono più a nessuna ricetta. */
data class MenuCalendario(val lunedi: LocalDate, val pasti: List<Slot>, val nonRiconosciuti: Int)

data class Item(val id: String, val nome: String, val unita: String, val reparto: String, val q: Double, val costo: Double)

private data class Spec(val day: Int, val meal: String, val mult: Int, val from: Int?)

class Catalog(val ing: Map<String, Ingredient>, val recipes: List<Recipe>) {
    val pool: List<Recipe> = recipes.filter { !(GRAVIDANZA && it.evitaGravidanza) }

    /** Macro di una ricetta per una porzione base (senza olio). */
    fun macros(r: Recipe): Macros {
        var c = 0.0
        var p = 0.0
        var f = 0.0
        for ((k, q) in r.ing) {
            val i = ing.getValue(k)
            val x = if (i.unita == "pz") q else q / 100.0
            c += i.carbo * x
            p += i.proteine * x
            f += i.grassi * x
        }
        return Macros(c, p, f)
    }

    /** Moltiplicatore di porzione per una persona, per arrivare alla sua quota di calorie. */
    fun pm(r: Recipe, p: Person): Double = ((p.kcal * QUOTA - OLIO) / macros(r).kcal).coerceIn(0.6, 2.5)

    fun tot(r: Recipe): Double = PERSONE.sumOf { pm(r, it) }

    /** Macro e calorie del pasto per una persona, olio incluso. */
    fun mp(r: Recipe, p: Person): Macros {
        val m = macros(r) * pm(r, p)
        return Macros(m.c, m.p, m.f + OLIO / 9)
    }

    /** Quanto la ricetta è sbilanciata sulle proteine rispetto ai carboidrati (0..1). */
    fun pq(r: Recipe): Double {
        val m = macros(r)
        return if (m.c + m.p > 0) m.p / (m.p + m.c) else 0.0
    }

    val byName: Map<String, Recipe> = recipes.associateBy { it.nome }

    private fun kcalUnit(i: Ingredient): Double = 4 * (i.carbo + i.proteine) + 9 * i.grassi
    private fun energy(i: Ingredient, q: Double): Double = (if (i.unita == "pz") q else q / 100.0) * kcalUnit(i)
    private fun qFor(i: Ingredient, kcal: Double): Double {
        val u = kcalUnit(i)
        val pz = i.unita == "pz"
        val q = if (u <= 0.0) 0.0 else if (pz) kcal / u else kcal / u * 100.0
        return q.coerceIn(if (pz) 0.5 else 10.0, if (pz) 4.0 else 400.0)
    }

    /** Sostituisce un ingrediente con un altro, in una quantità che porta le stesse calorie. */
    fun replaceIng(cur: Map<String, Double>, from: String, to: String): Map<String, Double> {
        if (from == to) return cur
        val q = qFor(ing.getValue(to), energy(ing.getValue(from), cur.getValue(from)))
        val out = LinkedHashMap<String, Double>()
        for ((k, v) in cur) {
            if (k == from) out[to] = (out[to] ?: 0.0) + q else out[k] = (out[k] ?: 0.0) + v
        }
        return out
    }

    /** Aggiunge un ingrediente con una quantità iniziale di circa 120 kcal. */
    fun addIng(cur: Map<String, Double>, id: String): Map<String, Double> =
        if (id in cur) cur else cur + (id to qFor(ing.getValue(id), 120.0))

    fun scale(cur: Map<String, Double>, id: String, f: Double): Map<String, Double> =
        cur + (id to cur.getValue(id) * f)

    private fun parola(id: String): String? {
        val i = ing[id] ?: return null
        val p = if (i.parola.isNotEmpty()) i.parola else (PAROLE_TITOLO[id] ?: "")
        return if (p.isEmpty()) null else p
    }

    /** Nuovo titolo del piatto dopo aver cambiato gli ingredienti (conta solo chi ha una parola per i titoli). */
    fun titoloAggiornato(titolo: String, prima: Set<String>, dopo: Set<String>): String =
        Titoli.aggiorna(titolo, (prima - dopo).mapNotNull { parola(it) }, (dopo - prima).mapNotNull { parola(it) })

    /**
     * Crea il menu per i giorni scelti (0 = lunedì ... 6 = domenica).
     * Gli avanzi passano solo da un giorno a quello subito dopo: se il giorno dopo non è nel menu,
     * la cena si cucina per una volta sola, e il pranzo di un giorno che segue un giorno saltato è un piatto nuovo.
     */
    fun build(giorni: Collection<Int>, meals: String, leftover: Boolean): List<Slot> {
        val lunch = meals != "cena"
        val dinner = meals != "pranzo"
        val lf = leftover && lunch && dinner
        val scelti = giorni.filter { it in 0..6 }.toSortedSet()
        val specs = ArrayList<Spec>()
        val cenaDel = HashMap<Int, Int>() // giorno -> posizione della sua cena in specs
        for (d in scelti) {
            if (lunch) specs.add(Spec(d, "Pranzo", 1, if (lf) cenaDel[d - 1] else null))
            if (dinner) {
                cenaDel[d] = specs.size
                specs.add(Spec(d, "Cena", if (lf && (d + 1) in scelti) 2 else 1, null))
            }
        }
        val sc = pool.associateWith { pq(it) + (Random.nextDouble() - 0.5) * 0.2 }
        val sh = pool.sortedByDescending { sc.getValue(it) }
        val shd = sh.filter { it.giornoDopo }
        val seen = HashSet<Recipe>()
        val rec = arrayOfNulls<Recipe>(specs.size)
        var n = 0
        for (i in specs.indices) {
            val s = specs[i]
            if (s.from != null) continue
            val src = if (s.mult > 1 && shd.isNotEmpty()) shd else sh
            val r = src.firstOrNull { it !in seen } ?: src[n % src.size]
            rec[i] = r
            seen.add(r)
            n++
        }
        return specs.mapIndexed { i, s ->
            val f = s.from
            val r = if (f != null) rec[f] else rec[i]
            Slot(s.day, s.meal, r!!, s.mult, s.from)
        }
    }

    fun swap(slots: List<Slot>, idx: Int): List<Slot> {
        val s = slots[idx]
        val p0 = pool.filter { s.mult < 2 || it.giornoDopo }
        val pl = if (p0.isEmpty()) pool else p0
        val used = slots.map { it.recipe }.toSet()
        val c = pl.filter { it !in used }
        val l = if (c.isNotEmpty()) c else pl.filter { it != s.recipe }
        if (l.isEmpty()) return slots
        val p = l.sortedByDescending { pq(it) }.take(8).random()
        return choose(slots, idx, p)
    }

    /** Mette la ricetta scelta in quel pasto (e nei pranzi che ne riusano gli avanzi), ripartendo dalla ricetta originale. */
    fun choose(slots: List<Slot>, idx: Int, r: Recipe): List<Slot> =
        slots.mapIndexed { i, x -> if (i == idx || x.from == idx) x.copy(recipe = r, titolo = null) else x }

    /**
     * Ricostruisce il menu della settimana che parte da quel lunedì a partire dagli eventi del calendario, così come sono:
     * un pasto tolto o spostato nel calendario risulta tolto o spostato anche nel menu.
     * - Il giorno è la data dell'evento; il pasto è quello scritto nei dati, oppure si ricava dall'ora.
     * - Un pranzo con gli avanzi si collega alla cena del giorno prima se c'è ed è lo stesso piatto;
     *   altrimenti diventa un pasto normale (come quando si toglie quella cena dall'app).
     * - Se due eventi cadono nello stesso pasto dello stesso giorno, si tiene il primo.
     */
    fun daCalendario(lunedi: LocalDate, eventi: List<PastoCalendario>): MenuCalendario {
        var nonRiconosciuti = 0
        val trovati = LinkedHashMap<Pair<Int, String>, Slot>()
        val conAvanzi = HashSet<Pair<Int, String>>()
        for (e in eventi.sortedWith(compareBy({ it.data }, { it.ora }))) {
            val day = ChronoUnit.DAYS.between(lunedi, e.data).toInt()
            if (day !in 0..6) continue
            val meal = e.pasto?.takeIf { it == "Pranzo" || it == "Cena" } ?: if (e.ora < 17) "Pranzo" else "Cena"
            val chiave = Pair(day, meal)
            if (chiave in trovati) continue
            var r = byName[e.ricetta ?: e.titolo.removeSuffix(SUFFISSO_AVANZI)]
            if (r == null) {
                nonRiconosciuti++
                continue
            }
            val mod = e.ingredienti
            if (mod != null && mod.isNotEmpty() && mod.keys.all { it in ing }) r = r.copy(ing = mod)
            trovati[chiave] = Slot(day, meal, r, 1, null, e.titoloMio)
            if (e.avanzi ?: e.titolo.endsWith(SUFFISSO_AVANZI)) conAvanzi.add(chiave)
        }
        val out = trovati.values.sortedWith(compareBy({ it.day }, { if (it.meal == "Pranzo") 0 else 1 })).toMutableList()
        for (i in out.indices) {
            val s = out[i]
            if (s.meal != "Pranzo" || Pair(s.day, s.meal) !in conAvanzi) continue
            val j = out.indexOfFirst { it.day == s.day - 1 && it.meal == "Cena" }
            if (j < 0 || out[j].recipe.nome != s.recipe.nome) continue
            out[j] = out[j].copy(mult = 2)
            out[i] = s.copy(recipe = out[j].recipe, titolo = out[j].titolo, from = j)
        }
        return MenuCalendario(lunedi, out, nonRiconosciuti)
    }

    /**
     * Toglie un pasto dal menu.
     * - Se è una cena che lasciava gli avanzi per il pranzo dopo, quel pranzo resta, con lo stesso piatto, da cucinare a pranzo.
     * - Se è un pranzo con gli avanzi, la cena da cui venivano si cucina per una volta sola.
     * Togliendo un pasto i successivi scalano di una posizione, quindi i riferimenti agli avanzi (from) si aggiornano.
     */
    fun remove(slots: List<Slot>, idx: Int): List<Slot> {
        if (idx !in slots.indices) return slots
        val tolto = slots[idx]
        val out = ArrayList<Slot>()
        for ((i, s) in slots.withIndex()) {
            if (i == idx) continue
            val f = s.from
            out.add(
                when {
                    i == tolto.from -> s.copy(mult = 1)          // la cena che lasciava gli avanzi al pranzo tolto
                    f == null -> s
                    f == idx -> s.copy(from = null, mult = 1)    // gli avanzi venivano dalla cena tolta
                    f > idx -> s.copy(from = f - 1)
                    else -> s
                }
            )
        }
        return out
    }

    fun shopping(slots: List<Slot>): List<Item> {
        val a = LinkedHashMap<String, Double>()
        for (s in slots) {
            if (s.from != null) continue
            val t = tot(s.recipe)
            for ((k, q) in s.recipe.ing) a[k] = (a[k] ?: 0.0) + q * t * s.mult
        }
        return a.map { (k, q0) ->
            val i = ing.getValue(k)
            val q = if (i.unita == "pz") ceil(q0) else ceil(q0 / 10) * 10
            val c = if (i.unita == "pz") q * i.prezzo else q / 1000 * i.prezzo
            Item(k, i.nome, i.unita, i.reparto, q, c)
        }
    }

    fun dayMacros(slots: List<Slot>, d: Int, p: Person): Macros =
        slots.filter { it.day == d }.fold(Macros(0.0, 0.0, 0.0)) { acc, s -> acc + mp(s.recipe, p) }

    companion object {
        fun load(ctx: Context): Catalog {
            val fi = File(ctx.filesDir, "ingredients.json")
            val fr = File(ctx.filesDir, "recipes.json")
            if (fi.exists() && fr.exists()) {
                try {
                    return parse(fi.readText(), fr.readText())
                } catch (e: Exception) {
                    // la copia scaricata non è valida: si usa quella inclusa nell'app
                }
            }
            fun asset(name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }
            return parse(asset("ingredients.json"), asset("recipes.json"))
        }

        /** Legge e controlla i due file; se c'è un errore lancia un'eccezione con un messaggio comprensibile. */
        fun parse(ingJson: String, recJson: String): Catalog {
            try {
                val ia = JSONArray(ingJson)
                val ing = (0 until ia.length()).map { ia.getJSONObject(it) }.map { o ->
                    Ingredient(
                        o.getString("id"), o.getString("nome"), o.getString("unita"), o.getDouble("prezzo"),
                        o.getString("reparto"), o.getDouble("carbo"), o.getDouble("proteine"), o.getDouble("grassi"),
                        o.optString("tipo", ""),
                        o.optString("titolo", ""),
                    )
                }
                val ingMap = ing.associateBy { it.id }
                if (ingMap.size != ing.size) {
                    throw IllegalArgumentException("In ingredients.json ci sono due ingredienti con lo stesso id.")
                }
                for (i in ing) {
                    if (i.reparto !in REPARTI) {
                        throw IllegalArgumentException("Ingrediente “${i.nome}”: il reparto “${i.reparto}” non esiste. Usa uno tra: ${REPARTI.joinToString(", ")}.")
                    }
                    if (i.unita != "g" && i.unita != "pz") {
                        throw IllegalArgumentException("Ingrediente “${i.nome}”: l’unità deve essere g oppure pz.")
                    }
                }
                val ra = JSONArray(recJson)
                val rec = (0 until ra.length()).map { ra.getJSONObject(it) }.map { o ->
                    val nome = o.getString("nome")
                    val io = o.getJSONObject("ingredienti")
                    val m = LinkedHashMap<String, Double>()
                    val keys = io.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val i = ingMap[k]
                            ?: throw IllegalArgumentException("La ricetta “$nome” usa l’ingrediente “$k”, che non esiste in ingredients.json.")
                        val q = io.getDouble(k)
                        if (q <= 0) throw IllegalArgumentException("Ricetta “$nome”: la quantità di “$k” deve essere maggiore di zero.")
                        // meno carboidrati (x0,6) e più proteine (x1,2), come nel prototipo
                        val f = when {
                            i.tipo == "carbo" -> 0.6
                            i.tipo == "proteine" -> 1.2
                            i.tipo.isEmpty() && k in CARB -> 0.6
                            i.tipo.isEmpty() && k in PROT -> 1.2
                            else -> 1.0
                        }
                        m[k] = q * f
                    }
                    if (m.isEmpty()) throw IllegalArgumentException("La ricetta “$nome” non ha ingredienti.")
                    Recipe(nome, o.optBoolean("giorno_dopo", false), o.optBoolean("sconsigliata_in_gravidanza", false), m)
                }
                if (rec.isEmpty()) throw IllegalArgumentException("Non ci sono ricette in recipes.json.")
                val nomi = HashSet<String>()
                for (r in rec) {
                    if (!nomi.add(r.nome)) throw IllegalArgumentException("La ricetta “${r.nome}” compare due volte.")
                }
                return Catalog(ingMap, rec)
            } catch (e: JSONException) {
                throw IllegalArgumentException("Errore nel formato del file: controlla virgole, virgolette e parentesi. (${e.message})")
            }
        }
    }
}
