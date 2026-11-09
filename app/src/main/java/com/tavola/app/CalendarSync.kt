package com.tavola.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

data class CalInfo(val id: Long, val nome: String, val account: String, val scrivibile: Boolean, val sincronizzato: Boolean)

/** Scrive i pasti nel calendario del telefono, che Google Calendar sincronizza da solo. */
object CalendarSync {
    const val ORA_PRANZO = 13      // ora di inizio dell'evento del pranzo
    const val ORA_CENA = 20        // ora di inizio dell'evento della cena
    const val DURATA_MIN = 60      // durata degli eventi, in minuti
    const val PROMEMORIA_MIN = 0   // minuti di preavviso della notifica (0 = nessuna notifica)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE)

    fun savedCalendar(ctx: Context): Long? = prefs(ctx).getLong("calId", -1L).takeIf { it >= 0 }

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

    /** Crea un evento per ogni pasto. Se la stessa settimana era già stata inviata, sostituisce gli eventi precedenti. */
    fun send(ctx: Context, calId: Long, slots: List<Slot>, start: LocalDate): Int {
        val p = prefs(ctx)
        val cr = ctx.contentResolver
        if (p.getString("calStart", null) == start.toString()) {
            val old = (p.getString("calIds", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            for (id in old) {
                try {
                    cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
                } catch (e: Exception) {
                    // evento già cancellato: non importa
                }
            }
        }
        val zone = ZoneId.systemDefault()
        val ids = ArrayList<Long>()
        for (s in slots) {
            val ora = if (s.meal == "Pranzo") ORA_PRANZO else ORA_CENA
            val begin = start.plusDays(s.day.toLong()).atTime(ora, 0).atZone(zone).toInstant().toEpochMilli()
            val nome = s.titolo ?: s.recipe.nome
            val titolo = if (s.from != null) "$nome (avanzi)" else nome
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, titolo)
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
        p.edit().putLong("calId", calId).putString("calStart", start.toString()).putString("calIds", ids.joinToString(",")).apply()
        return ids.size
    }
}
