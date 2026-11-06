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
    val catalog = Catalog.load(ctx)
    private val prefs = ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE)

    var days by mutableIntStateOf(prefs.getInt("days", 7))
    var meals by mutableStateOf(prefs.getString("meals", "both") ?: "both")
    var leftover by mutableStateOf(prefs.getBoolean("leftover", true))
    var plan by mutableStateOf(loadPlan())
    var checked by mutableStateOf<Set<String>>(prefs.getStringSet("checked", emptySet())?.toSet() ?: emptySet())
    var editing by mutableStateOf(plan == null)
    var tab by mutableIntStateOf(0)

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
            a.put(
                JSONObject().put("d", s.day).put("m", s.meal).put("r", s.recipe.nome)
                    .put("x", s.mult).put("f", s.from ?: -1)
            )
        }
        return a.toString()
    }

    private fun loadPlan(): List<Slot>? {
        val raw = prefs.getString("plan", null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val byName = catalog.recipes.associateBy { it.nome }
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val f = o.getInt("f")
                Slot(o.getInt("d"), o.getString("m"), byName.getValue(o.getString("r")), o.getInt("x"), if (f >= 0) f else null)
            }
        } catch (e: Exception) {
            null
        }
    }
}
