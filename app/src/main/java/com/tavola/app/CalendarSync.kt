package com.tavola.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

data class CalInfo(val id: Long, val nome: String, val account: String, val scrivibile: Boolean, val sincronizzato: Boolean)

/** Scrive i pasti nel calendario del telefono, che Google Calendar sincronizza da solo. */
object CalendarSync {
    const val ORA_PRANZO = 13      // ora di inizio dell'evento del pranzo
    const val ORA_CENA = 20        // ora di inizio dell'evento della cena
    const val DURATA_MIN = 60      // durata degli eventi, in minuti
    const val PROMEMORIA_MIN = 0   // minuti di preavviso della notifica (0 = nessuna notifica)
    private const val MAX_SETTIMANE = 12 // settimane di cui si ricordano gli id degli eventi creati
    const val SETTIMANE_INDIETRO = 26L   // fin dove si cercano i menu nel calendario, nel passato...
    const val SETTIMANE_AVANTI = 12L     // ...e nel futuro

    /** Inizio della riga con i dati del pasto, nella descrizione degli eventi: serve per recuperare il menu dal calendario. */
    const val MARCATORE = "MealYourWeek:"
    private const val DESCRIZIONE = "Pasto pianificato con MealYourWeek.\nLa riga qui sotto serve all'app per recuperare il menu: non modificarla."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE)

    fun savedCalendar(ctx: Context): Long? = prefs(ctx).getLong("calId", -1L).takeIf { it >= 0 }

    /** Il lunedì dell'ultima settimana inviata al calendario, se c'è. */
    fun lastStart(ctx: Context): LocalDate? =
        prefs(ctx).getString("calStart", null)?.let {
            try { LocalDate.parse(it) } catch (e: Exception) { null }
        }

    /**
     * Gli eventi creati dall'app, settimana per settimana: lunedì -> id separati da virgole.
     * Le versioni precedenti ricordavano una sola settimana ("calStart" + "calIds"): se ci sono, si recuperano.
     */
    private fun leggiEventi(ctx: Context): JSONObject {
        val p = prefs(ctx)
        val o = try { JSONObject(p.getString("calSettimane", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val vecchiaSettimana = p.getString("calStart", null)
        val vecchiIds = p.getString("calIds", null)
        if (vecchiaSettimana != null && !vecchiIds.isNullOrEmpty() && !o.has(vecchiaSettimana)) {
            o.put(vecchiaSettimana, vecchiIds)
        }
        return o
    }

    /** Tutti i calendari che il telefono conosce, con l'indicazione se si può scrivere e se sono sincronizzati con Google. */
    fun calendars(ctx: Context): List<CalInfo> {
        val out = ArrayList<CalInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    CalInfo(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                        c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        c.getInt(4) == 1,
                    )
                )
            }
        }
        return out
    }

    /** Tra gli id dati, quelli di eventi che esistono ancora (non cancellati). */
    private fun ancoraPresenti(ctx: Context, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        val sel = CalendarContract.Events._ID + " IN (" + ids.joinToString(",") + ") AND " +
            CalendarContract.Events.DELETED + " = 0"
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), sel, null, null,
        )?.use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** Un evento messo dalle versioni precedenti dell'app (senza dati): all'ora del pranzo o della cena e col nome di un piatto. */
    private fun sembraDelMenu(titolo: String, inizio: ZonedDateTime, nomi: Set<String>): Boolean {
        val oraPasto = inizio.minute == 0 && (inizio.hour == ORA_PRANZO || inizio.hour == ORA_CENA)
        return oraPasto && titolo.removeSuffix(SUFFISSO_AVANZI) in nomi
    }

    /**
     * Eventi di quella settimana, in quel calendario, messi dall'app: quelli con i dati del pasto nella descrizione
     * (anche se nel calendario li hai spostati di ora), più quelli delle versioni precedenti riconoscibili da ora e nome.
     * Servono quando l'app non ha più gli id.
     */
    private fun eventiDelMenu(ctx: Context, calId: Long, start: LocalDate, nomi: Set<String>, zone: ZoneId): Set<Long> {
        val out = HashSet<Long>()
        val da = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val a = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val sel = CalendarContract.Events.CALENDAR_ID + " = ? AND " +
            CalendarContract.Events.DELETED + " = 0 AND " +
            CalendarContract.Events.DTSTART + " >= ? AND " +
            CalendarContract.Events.DTSTART + " < ?"
        val proj = arrayOf(
            CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DESCRIPTION,
        )
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj, sel, arrayOf(calId.toString(), da.toString(), a.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val titolo = c.getString(1) ?: ""
                val inizio = Instant.ofEpochMilli(c.getLong(2)).atZone(zone)
                val nostro = (c.getString(3) ?: "").contains(MARCATORE)
                if (nostro || sembraDelMenu(titolo, inizio, nomi)) out.add(c.getLong(0))
            }
        }
        return out
    }

    /**
     * I dati del pasto che vanno nella descrizione dell'evento, per poter ricostruire il menu dal calendario:
     * r = ricetta, m = pasto, a = avanzi, t = titolo cambiato, i = ingredienti (solo se modificati).
     */
    private fun datiPasto(s: Slot, cat: Catalog): String {
        val o = JSONObject().put("r", s.recipe.nome).put("m", s.meal)
        if (s.from != null) o.put("a", true)
        val t = s.titolo
        if (t != null) o.put("t", t)
        if (cat.byName[s.recipe.nome]?.ing != s.recipe.ing) {
            val io = JSONObject()
            for ((k, v) in s.recipe.ing) io.put(k, v)
            o.put("i", io)
        }
        return o.toString()
    }

    /** Legge i dati del pasto dalla descrizione di un evento: la riga che inizia dopo MARCATORE. */
    private fun leggiDati(descrizione: String?): JSONObject? {
        if (descrizione == null) return null
        val i = descrizione.indexOf(MARCATORE)
        if (i < 0) return null
        val riga = descrizione.substring(i + MARCATORE.length).lineSequence().firstOrNull()?.trim() ?: return null
        return try { JSONObject(riga) } catch (e: Exception) { null }
    }

    /**
     * Il pasto rappresentato da un evento, oppure null se l'evento non è stato messo dall'app.
     * Se i dati nella descrizione non si leggono (per esempio perché la descrizione è stata modificata), resta il titolo.
     */
    internal fun pastoDaEvento(titolo: String, inizio: ZonedDateTime, descrizione: String?, nomi: Set<String>): PastoCalendario? {
        val nostro = descrizione?.contains(MARCATORE) == true
        if (!nostro && !sembraDelMenu(titolo, inizio, nomi)) return null
        val d = leggiDati(descrizione)
        val io = d?.optJSONObject("i")
        return PastoCalendario(
            data = inizio.toLocalDate(),
            ora = inizio.hour,
            titolo = titolo,
            ricetta = d?.optString("r", "")?.ifEmpty { null },
            pasto = d?.optString("m", "")?.ifEmpty { null },
            avanzi = d?.optBoolean("a", false),
            titoloMio = d?.optString("t", "")?.ifEmpty { null },
            ingredienti = io?.keys()?.asSequence()?.associateWith { k -> io.getDouble(k) },
        )
    }

    /** La descrizione di un evento: una frase per chi la legge e la riga con i dati del pasto. */
    internal fun descrizione(s: Slot, cat: Catalog): String = DESCRIZIONE + "\n" + MARCATORE + datiPasto(s, cat)

    /**
     * I menu che si trovano nel calendario, settimana per settimana, dalla più recente: dalle SETTIMANE_INDIETRO
     * settimane passate alle SETTIMANE_AVANTI future. Legge gli eventi con i dati del pasto in qualsiasi calendario
     * e, nel calendario usato per l'ultimo invio, anche quelli messi dalle versioni precedenti.
     */
    fun leggiMenu(ctx: Context, cat: Catalog): List<MenuCalendario> {
        val zone = ZoneId.systemDefault()
        val oggi = LocalDate.now()
        val da = oggi.minusWeeks(SETTIMANE_INDIETRO).atStartOfDay(zone).toInstant().toEpochMilli()
        val a = oggi.plusWeeks(SETTIMANE_AVANTI).atStartOfDay(zone).toInstant().toEpochMilli()
        val calSalvato = savedCalendar(ctx) ?: -1L
        val nomi = cat.recipes.map { it.nome }.toSet()
        val sel = CalendarContract.Events.DELETED + " = 0 AND " +
            CalendarContract.Events.DTSTART + " >= ? AND " +
            CalendarContract.Events.DTSTART + " < ? AND (" +
            CalendarContract.Events.DESCRIPTION + " LIKE ? OR " +
            CalendarContract.Events.CALENDAR_ID + " = ?)"
        val proj = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DESCRIPTION)
        val pasti = ArrayList<PastoCalendario>()
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj, sel,
            arrayOf(da.toString(), a.toString(), "%$MARCATORE%", calSalvato.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val inizio = Instant.ofEpochMilli(c.getLong(1)).atZone(zone)
                val p = pastoDaEvento(c.getString(0) ?: "", inizio, c.getString(2), nomi)
                if (p != null) pasti.add(p)
            }
        }
        return pasti
            .groupBy { it.data.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .map { (lunedi, l) -> cat.daCalendario(lunedi, l) }
            .filter { it.pasti.isNotEmpty() }
            .sortedByDescending { it.lunedi }
    }

    /**
     * Crea un evento per ogni pasto. Se quella settimana era già stata inviata, prima toglie gli eventi messi allora.
     * Restituisce quanti eventi ha creato e quanti eventi precedenti ha tolto.
     */
    fun send(ctx: Context, calId: Long, slots: List<Slot>, start: LocalDate, cat: Catalog): Pair<Int, Int> {
        val p = prefs(ctx)
        val cr = ctx.contentResolver
        val zone = ZoneId.systemDefault()
        val settimane = leggiEventi(ctx)

        // 1. gli eventi precedenti di questa settimana: quelli di cui l'app ricorda l'id, più quelli riconoscibili dal nome
        val salvati = settimane.optString(start.toString(), "").split(",").mapNotNull { it.trim().toLongOrNull() }
        val nomiPiatti = (cat.recipes.map { it.nome } + slots.mapNotNull { it.titolo }).toSet()
        val vecchi = ancoraPresenti(ctx, salvati) + eventiDelMenu(ctx, calId, start, nomiPiatti, zone)
        var tolti = 0
        for (id in vecchi) {
            try {
                tolti += cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            } catch (e: Exception) {
                // evento già cancellato: non importa
            }
        }

        // 2. i nuovi eventi
        val ids = ArrayList<Long>()
        for (s in slots) {
            val ora = if (s.meal == "Pranzo") ORA_PRANZO else ORA_CENA
            val begin = start.plusDays(s.day.toLong()).atTime(ora, 0).atZone(zone).toInstant().toEpochMilli()
            val nome = s.titolo ?: s.recipe.nome
            val titolo = if (s.from != null) nome + SUFFISSO_AVANZI else nome
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, titolo)
                put(CalendarContract.Events.DESCRIPTION, descrizione(s, cat))
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, begin + DURATA_MIN * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: continue
            val id = ContentUris.parseId(uri)
            ids.add(id)
            if (PROMEMORIA_MIN > 0) {
                val r = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, PROMEMORIA_MIN)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                cr.insert(CalendarContract.Reminders.CONTENT_URI, r)
            }
        }

        // 3. si ricordano gli id di questa settimana (al massimo le ultime MAX_SETTIMANE)
        settimane.put(start.toString(), ids.joinToString(","))
        val chiavi = settimane.keys().asSequence().toList().sorted()
        for (k in chiavi.dropLast(MAX_SETTIMANE)) settimane.remove(k)
        p.edit()
            .putLong("calId", calId)
            .putString("calStart", start.toString())
            .putString("calSettimane", settimane.toString())
            .remove("calIds")
            .apply()
        return Pair(ids.size, tolti)
    }
}
