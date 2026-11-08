package com.tavola.app

/**
 * Aggiorna il titolo di un piatto quando cambiano i macroingredienti.
 * Esempio: "Pollo con peperoni", togli i peperoni e metti le zucchine -> "Pollo con zucchine".
 */
object Titoli {
    private fun cerca(t: String, p: String): IntRange? =
        Regex("(?<![\\p{L}])" + Regex.escape(p) + "(?![\\p{L}])", RegexOption.IGNORE_CASE).find(t)?.range

    private fun maiuscola(t: String): String = if (t.isEmpty()) t else t.substring(0, 1).uppercase() + t.substring(1)

    private fun sostituisci(t: String, vecchia: String, nuova: String): String {
        val r = cerca(t, vecchia) ?: return t
        val nuovaParola = if (r.first == 0) maiuscola(nuova) else nuova
        return t.substring(0, r.first) + nuovaParola + t.substring(r.last + 1)
    }

    private fun togli(t: String, p: String): String {
        val r = cerca(t, p) ?: return t
        var a = r.first
        var b = r.last + 1
        val legame = "(\\s+con\\s+|\\s+e\\s+|,\\s*)"
        val prima = Regex(legame + "$").find(t.substring(0, a))
        val dopo = Regex("^" + legame).find(t.substring(b))
        if (prima != null && prima.value.trim() == "con" && dopo != null) {
            b += dopo.value.length            // "Pollo con peperoni e zucchine" -> "Pollo con zucchine"
        } else if (prima != null) {
            a -= prima.value.length
        } else if (dopo != null) {
            b += dopo.value.length
        }
        val res = (t.substring(0, a) + t.substring(b)).trim()
        return if (res.isEmpty()) t else maiuscola(res)
    }

    private fun aggiungi(t: String, p: String): String {
        if (cerca(t, p) != null) return t
        val i = t.lastIndexOf(" con ")
        if (i >= 0) {
            val coda = t.substring(i + 5)
            return if (coda.contains(" e ")) t.substring(0, i + 5) + coda.replace(" e ", ", ") + " e " + p else "$t e $p"
        }
        return "$t con $p"
    }

    /** rimossi e aggiunti sono le parole dei macroingredienti tolti e messi (i condimenti non contano). */
    fun aggiorna(titolo: String, rimossi: List<String>, aggiunti: List<String>): String {
        var t = titolo
        val agg = aggiunti.toMutableList()
        for (r in rimossi) {
            if (cerca(t, r) == null) continue
            val n = if (agg.isNotEmpty()) agg.removeAt(0) else null
            t = if (n != null) sostituisci(t, r, n) else togli(t, r)
        }
        for (a in agg) t = aggiungi(t, a)
        return t
    }
}
