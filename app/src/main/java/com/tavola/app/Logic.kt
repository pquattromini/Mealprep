package com.tavola.app

import android.content.Context
import org.json.JSONArray
import kotlin.math.ceil
import kotlin.random.Random

// ---- Impostazioni ----
const val QUOTA = 0.35        // ogni pasto (pranzo o cena) copre il 35% delle calorie del giorno
const val OLIO = 80.0         // kcal di olio e condimenti contate a ogni pasto
const val GRAVIDANZA = true   // metti false dopo il parto: riattiva le ricette sconsigliate

val CARB = setOf("pasta", "riso", "quinoa", "pane", "panini", "wrap", "pizza", "patate", "gnocchi", "polenta", "farro", "tortellini")
val PROT = setOf("pollo", "tacchino", "cosce", "manzo", "maiale", "macinato", "salmone", "merluzzo", "gamberi", "tofu", "ricotta", "yogurt")
val REPARTI = listOf("Ortofrutta", "Carne e pesce", "Frigo", "Panetteria", "Dispensa")
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
)

data class Recipe(val nome: String, val giornoDopo: Boolean, val evitaGravidanza: Boolean, val ing: Map<String, Double>)

data class Macros(val c: Double, val p: Double, val f: Double) {
    val kcal: Double get() = 4 * (c + p) + 9 * f
    operator fun plus(o: Macros) = Macros(c + o.c, p + o.p, f + o.f)
    operator fun times(x: Double) = Macros(c * x, p * x, f * x)
}

data class Slot(val day: Int, val meal: String, val recipe: Recipe, val mult: Int, val from: Int?)

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

    fun build(days: Int, meals: String, leftover: Boolean): List<Slot> {
        val lunch = meals != "cena"
        val dinner = meals != "pranzo"
        val lf = leftover && lunch && dinner
        val specs = ArrayList<Spec>()
        for (d in 0 until days) {
            if (lunch) specs.add(Spec(d, "Pranzo", 1, if (lf && d > 0) specs.size - 1 else null))
            if (dinner) specs.add(Spec(d, "Cena", if (lf && d < days - 1) 2 else 1, null))
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
        return slots.mapIndexed { i, x -> if (i == idx || x.from == idx) x.copy(recipe = p) else x }
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
            fun read(name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }
            val ia = JSONArray(read("ingredients.json"))
            val ing = (0 until ia.length()).map { ia.getJSONObject(it) }.map { o ->
                Ingredient(
                    o.getString("id"), o.getString("nome"), o.getString("unita"), o.getDouble("prezzo"),
                    o.getString("reparto"), o.getDouble("carbo"), o.getDouble("proteine"), o.getDouble("grassi"),
                )
            }.associateBy { it.id }
            val ra = JSONArray(read("recipes.json"))
            val rec = (0 until ra.length()).map { ra.getJSONObject(it) }.map { o ->
                val io = o.getJSONObject("ingredienti")
                // meno carboidrati (x0,6) e più proteine (x1,2), come nel prototipo
                val m = io.keys().asSequence().associateWith { k ->
                    val f = if (k in CARB) 0.6 else if (k in PROT) 1.2 else 1.0
                    io.getDouble(k) * f
                }
                Recipe(o.getString("nome"), o.optBoolean("giorno_dopo", false), o.optBoolean("sconsigliata_in_gravidanza", false), m)
            }
            return Catalog(ing, rec)
        }
    }
}
