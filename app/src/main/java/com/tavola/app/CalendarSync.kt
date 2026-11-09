package com.tavola.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CalInfo(val id: Long, val nome: String, val account: String, val scrivibile: Boolean, val sincronizzato: Boolean)

/** Scrive i pasti nel calendario del telefono, che Google Calendar sincronizza da solo. */
object CalendarSync {
    const val ORA_PRANZO = 13      // ora di inizio dell'evento del pranzo
    const val ORA_CENA = 20        // ora di inizio dell'evento della cena
    const val DURATA_MIN = 60      // durata degli eventi, in minuti
    const val PROMEMORIA_MIN = 0   // minuti di preavviso della notifica (0 = nessuna notifica)
    private const val MAX_SETTIMANE = 12 // settimane di cui si ricordano gli eventi creati (come l'archivio dei menù)

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

    /**
     * Eventi di quella settimana, in quel calendario, che sembrano messi dall'app: iniziano all'ora del pranzo
     * o della cena e si chiamano come una ricetta (anche con "(avanzi)"). Servono quando l'app non ha più gli id,
     * per esempio per una settimana inviata con una versione precedente.
     */
    private fun eventiDelMenu(ctx: Context, calId: Long, start: LocalDate, nomi: Set<String>, zone: ZoneId): Set<Long> {
        val out = HashSet<Long>()
        if (nomi.isEmpty()) return out
        val da = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val a = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val sel = CalendarContract.Events.CALENDAR_ID + " = ? AND " +
            CalendarContract.Events.DELETED + " = 0 AND " +
            CalendarContract.Events.DTSTART + " >= ? AND " +
            CalendarContract.Events.DTSTART + " < ?"
        val proj = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
        ctx.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, proj, sel, arrayOf(calId.toString(), da.toString(), a.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val titolo = (c.getString(1) ?: "").removeSuffix(" (avanzi)")
                val inizio = Instant.ofEpochMilli(c.getLong(2)).atZone(zone)
                val oraPasto = inizio.minute == 0 && (inizio.hour == ORA_PRANZO || inizio.hour == ORA_CENA)
                if (oraPasto && titolo in nomi) out.add(c.getLong(0))
            }
        }
        return out
    }

    /**
     * Crea un evento per ogni pasto. Se quella settimana era già stata inviata, prima toglie gli eventi messi allora.
     * Restituisce quanti eventi ha creato e quanti eventi precedenti ha tolto.
     */
    fun send(ctx: Context, calId: Long, slots: List<Slot>, start: LocalDate, nomi: List<String>): Pair<Int, Int> {
        val p = prefs(ctx)
        val cr = ctx.contentResolver
        val zone = ZoneId.systemDefault()
        val settimane = leggiEventi(ctx)

        // 1. gli eventi precedenti di questa settimana: quelli di cui l'app ricorda l'id, più quelli riconoscibili dal nome
        val salvati = settimane.optString(start.toString(), "").split(",").mapNotNull { it.trim().toLongOrNull() }
        val nomiPiatti = (nomi + slots.mapNotNull { it.titolo }).toSet()
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
