package com.tavola.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Stato dell'app. Menu, impostazioni e spunte della spesa restano salvati sul telefono. */
class Model(ctx: Context) {
    var catalog by mutableStateOf(Catalog.load(ctx))
    private val prefs = ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE)

    var days by mutableIntStateOf(prefs.getInt("days", 7))
    var meals by mutableStateOf(prefs.getString("meals", "both") ?: "both")
    var leftover by mutableStateOf(prefs.getBoolean("leftover", true))
    var plan by mutableStateOf(loadPlan())
    var checked by mutableStateOf<Set<String>>(prefs.getStringSet("checked", emptySet())?.toSet() ?: emptySet())
    var editing by mutableStateOf(plan == null)
    var tab by mutableIntStateOf(0)

    /** Ricarica ricette e ingredienti (dopo un aggiornamento) e riadatta il menu salvato ai dati nuovi. */
    fun reload(ctx: Context) {
        catalog = Catalog.load(ctx)
        plan = loadPlan()
        if (plan == null) editing = true
    }

    fun generate() {
        plan = catalog.build(days, meals, leftover)
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

    fun toggle(id: String) {
        checked = if (id in checked) checked - id else checked + id
        save()
    }

    /** Cambia gli ingredienti di un pasto (e dei pranzi che ne riusano gli avanzi). */
    fun updateSlot(idx: Int, ing: Map<String, Double>) {
        val p = plan ?: return
        val r = p[idx].recipe.copy(ing = ing)
        plan = p.mapIndexed { i, x -> if (i == idx || x.from == idx) x.copy(recipe = r) else x }
        save()
    }

    fun isModified(s: Slot): Boolean = catalog.byName[s.recipe.nome]?.ing != s.recipe.ing

    fun save() {
        val p = plan
        prefs.edit()
            .putInt("days", days)
            .putString("meals", meals)
            .putBoolean("leftover", leftover)
            .putString("plan", if (p == null) null else encode(p))
            .putStringSet("checked", checked)
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
            a.put(o)
        }
        return a.toString()
    }

    private fun loadPlan(): List<Slot>? {
        val raw = prefs.getString("plan", null) ?: return null
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
                Slot(o.getInt("d"), o.getString("m"), r, o.getInt("x"), if (f >= 0) f else null)
            }
            if (slots.any { s -> s.recipe.ing.keys.any { it !in catalog.ing } }) null else slots
        } catch (e: Exception) {
            null
        }
    }
}
