package com.tavola.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

private val Lemon = Color(0xFFF6D84A)
private val Orange = Color(0xFFE8743B)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D4BD1), onPrimary = Color.White,
    background = Color(0xFFF5F6FB), onBackground = Color(0xFF161B33),
    surface = Color.White, onSurface = Color(0xFF161B33),
    onSurfaceVariant = Color(0xFF5C6385), outlineVariant = Color(0xFFDDE1F0),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EA0FF), onPrimary = Color(0xFF0F1220),
    background = Color(0xFF0F1220), onBackground = Color(0xFFEEF0FA),
    surface = Color(0xFF1A1E33), onSurface = Color(0xFFEEF0FA),
    onSurfaceVariant = Color(0xFFA2A9CC), outlineVariant = Color(0xFF2C3252),
)

@Composable
fun TavolaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

// ---- Formattazione ----
fun euro(x: Double): String = String.format(Locale.ITALY, "%.2f €", x)

fun num(x: Double): String =
    if (x % 1.0 == 0.0) x.toLong().toString()
    else String.format(Locale.ITALY, "%.2f", x).trimEnd('0').trimEnd(',')

fun qty(q: Double, u: String): String = when {
    u == "pz" -> num(q) + " pz"
    q >= 1000 -> num(q / 1000) + " kg"
    else -> num(q) + " g"
}

fun rq(q: Double, u: String): Double =
    if (u == "pz") Math.round(q * 2) / 2.0 else Math.round(q / 5) * 5.0

fun Macros.riga(): String =
    "${kcal.roundToInt()} kcal, carbo ${c.roundToInt()} g, proteine ${p.roundToInt()} g, grassi ${f.roundToInt()} g"

// ---- Componenti base ----
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, center: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = if (selected) cs.primary else cs.surface,
        contentColor = if (selected) cs.onPrimary else cs.onSurface,
        border = BorderStroke(2.dp, if (selected) cs.primary else cs.outlineVariant),
    ) {
        Box(
            Modifier.padding(vertical = 14.dp, horizontal = if (center) 0.dp else 16.dp),
            contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
        ) {
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun Question(t: String, hint: String? = null) {
    Text(
        t, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    if (hint != null) {
        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
}

// ---- Schermate ----
@Composable
fun TavolaApp() {
    val ctx = LocalContext.current
    val m = remember { Model(ctx.applicationContext) }
    // all'apertura controlla se ci sono ricette e ingredienti più recenti (se hai impostato l'indirizzo)
    LaunchedEffect(Unit) {
        val u = ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE).getString("dataUrl", "") ?: ""
        if (u.isNotBlank()) {
            Updater.aggiorna(ctx.applicationContext, u) { cambiato, _, _ ->
                if (cambiato) m.reload(ctx.applicationContext)
            }
        }
    }
    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (m.editing || m.plan == null) SetupScreen(m) else ResultScreen(m)
        }
    }
}

@Composable
fun SetupScreen(m: Model) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("MealYourWeek", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
        Text("Menu e spesa della settimana", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Question("Quali giorni vuoi pianificare?", "Tocca un giorno per toglierlo o rimetterlo.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 0..6) {
                Choice(GIORNI[d].take(3), d in m.giorniScelti, Modifier.weight(1f), true) { m.toggleGiorno(d) }
            }
        }
        Text(
            when (m.giorniScelti.size) {
                0 -> "Scegli almeno un giorno."
                1 -> "1 giorno"
                else -> "${m.giorniScelti.size} giorni"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (m.giorniScelti.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Question("Quali pasti vuoi pianificare?")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("Solo pranzo", m.meals == "pranzo", Modifier.fillMaxWidth()) { m.meals = "pranzo" }
            Choice("Solo cena", m.meals == "cena", Modifier.fillMaxWidth()) { m.meals = "cena" }
            Choice("Pranzo e cena", m.meals == "both", Modifier.fillMaxWidth()) { m.meals = "both" }
        }

        if (m.meals == "both") {
            Question(
                "La cena della sera prima vale anche per il pranzo del giorno dopo?",
                "Cucini il doppio la sera, scegliendo piatti che si riscaldano bene, e il giorno dopo mangi gli avanzi.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("Sì, riutilizza gli avanzi", m.leftover, Modifier.fillMaxWidth()) { m.leftover = true }
                Choice("No, ogni pasto è nuovo", !m.leftover, Modifier.fillMaxWidth()) { m.leftover = false }
            }
        }

        Button(
            onClick = { m.generate() },
            enabled = m.giorniScelti.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Crea il menu") }

        RecuperaButton(m)

        if (m.plan != null) {
            TextButton(onClick = { m.editing = false }, modifier = Modifier.fillMaxWidth()) { Text("Torna al menu") }
        }
        UpdateButton(m)
    }
}

@Composable
fun ResultScreen(m: Model) {
    val slots = m.plan ?: return
    val lista = remember(slots) { m.catalog.shopping(slots) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { m.generate() }) { Text("Rifai il menu") }
            TextButton(onClick = { m.editing = true }) { Text("Cambia impostazioni") }
        }
        TabRow(selectedTabIndex = m.tab) {
            listOf("Menu", "Spesa", "Nutrizione").forEachIndexed { i, t ->
                Tab(selected = m.tab == i, onClick = { m.tab = i }, text = { Text(t) })
            }
        }
        when (m.tab) {
            0 -> MenuTab(m, slots)
            1 -> ShopTab(m, lista)
            else -> NutriTab(m, slots, lista)
        }
    }
}

@Composable
fun MenuTab(m: Model, slots: List<Slot>) {
    val cat = m.catalog
    // solo i giorni che sono nel menu, in ordine (0 = lunedì)
    val giorni = slots.map { it.day }.distinct().sorted()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val ini = m.inizioMenu
        if (ini != null) {
            item(key = "inizio") {
                Text(
                    "Menù della settimana da " + dataIt(ini),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        for (d in giorni) {
            item(key = "h$d") {
                Column(Modifier.padding(top = 10.dp)) {
                    Text(GIORNI[d % 7], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Totale del giorno: " + PERSONE.joinToString(", ") { p ->
                            p.nome.lowercase() + " " + cat.dayMacros(slots, d, p).kcal.roundToInt() + " kcal"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            slots.forEachIndexed { i, s ->
                // la chiave è giorno + pasto, non la posizione: togliendo un pasto le schede restano al loro posto
                if (s.day == d) item(key = "s${s.day}${s.meal}") { MealCard(m, s, i) }
            }
        }
        item(key = "salva") { SalvaButton(m) }
        item(key = "calendario") { CalendarButton(m, slots) }
    }
}

@Composable
fun MealCard(m: Model, s: Slot, idx: Int) {
    val cat = m.catalog
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val lf = s.from != null
    AppCard {
        val label = s.meal + (if (lf) ", avanzi di ieri sera" else if (s.mult > 1) ", quantità per cena e pranzo di domani" else "")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { deleting = true },
                colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("Elimina") }
        }
        if (deleting) DeleteDialog(m, s, idx) { deleting = false }
        Text(s.titolo ?: s.recipe.nome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (s.recipe.giornoDopo) {
            Text("Buona anche il giorno dopo", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (m.isModified(s) || s.titolo != null) {
            Text("Ricetta modificata", style = MaterialTheme.typography.labelSmall, color = cs.primary, fontWeight = FontWeight.Bold)
        }
        for (p in PERSONE) {
            Text(p.nome + ": " + cat.mp(s.recipe, p).riga(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (!lf) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Nascondi" else "Ingredienti") }
                TextButton(onClick = { editing = true }) { Text("Modifica") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { choosing = true }) { Text("Cambia") }
            }
            if (editing) EditDialog(m, s, idx) { editing = false }
            if (choosing) RecipeDialog(m, s, idx) { choosing = false }
            if (open) {
                for ((k, q) in s.recipe.ing) {
                    val i = cat.ing.getValue(k)
                    Text(
                        i.nome + ": " + PERSONE.joinToString(", ") { p ->
                            p.nome.lowercase() + " " + qty(rq(q * cat.pm(s.recipe, p) * s.mult, i.unita), i.unita)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Chiede conferma prima di togliere un pasto, spiegando cosa succede agli avanzi. */
@Composable
fun DeleteDialog(m: Model, s: Slot, idx: Int, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val giorno = GIORNI[s.day % 7].lowercase(Locale.ITALY)
    val conseguenza = when {
        s.from != null -> "La cena di ieri sera si cucinerà per una volta sola."
        s.mult > 1 -> "Il pranzo di domani resta, con lo stesso piatto da cucinare a pranzo."
        else -> null
    }
    val ultimo = m.plan.orEmpty().size <= 1
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Eliminare questo pasto?") },
        text = {
            Text(
                listOfNotNull(
                    s.meal + " di " + giorno + ": " + (s.titolo ?: s.recipe.nome) + ".",
                    conseguenza,
                    if (ultimo) "È l'ultimo pasto del menu: tornerai alla schermata iniziale." else null,
                ).joinToString("\n\n")
            )
        },
        confirmButton = {
            TextButton(onClick = { onClose(); m.remove(idx) }) { Text("Elimina", color = cs.error) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )
}

@Composable
fun ShopTab(m: Model, lista: List<Item>) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        for (rep in REPARTI) {
            val righe = lista.filter { it.reparto == rep }.sortedBy { it.nome }
            if (righe.isEmpty()) continue
            item(key = "rep-$rep") {
                Text(
                    rep, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
            }
            items(righe, key = { x -> x.id }) { x ->
                val done = x.id in m.checked
                Row(
                    Modifier.fillMaxWidth().clickable { m.toggle(x.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = done, onCheckedChange = { m.toggle(x.id) })
                    Column(Modifier.weight(1f)) {
                        Text(x.nome, textDecoration = if (done) TextDecoration.LineThrough else null)
                        Text(qty(x.q, x.unita), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text(euro(x.costo), color = cs.onSurfaceVariant)
                }
            }
        }
        item(key = "totale") {
            Text(
                "Totale stimato: " + euro(lista.sumOf { it.costo }),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        item(key = "bring") { BringButton(m, lista) }
    }
}

@Composable
fun NutriTab(m: Model, slots: List<Slot>, lista: List<Item>) {
    val cat = m.catalog
    val cs = MaterialTheme.colorScheme
    val giorni = slots.map { it.day }.distinct()
    val nDays = giorni.size.coerceAtLeast(1)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard {
            Text("Spesa stimata", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(euro(lista.sumOf { it.costo }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text("Prezzi medi indicativi, per 2 persone e " + (if (nDays == 1) "1 giorno." else "$nDays giorni."), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        for (p in PERSONE) {
            val avg = giorni.fold(Macros(0.0, 0.0, 0.0)) { a, d -> a + cat.dayMacros(slots, d, p) } * (1.0 / nDays)
            val en = (avg.c + avg.p) * 4 + avg.f * 9
            AppCard {
                Text(p.nome, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Fabbisogno ${p.kcal} kcal al giorno. Dai pasti pianificati: ${avg.kcal.roundToInt()} kcal.", color = cs.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    Box(Modifier.weight((avg.c * 4).toFloat().coerceAtLeast(0.1f)).fillMaxHeight().background(Lemon))
                    Box(Modifier.weight((avg.p * 4).toFloat().coerceAtLeast(0.1f)).fillMaxHeight().background(cs.primary))
                    Box(Modifier.weight((avg.f * 9).toFloat().coerceAtLeast(0.1f)).fillMaxHeight().background(Orange))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MacroCol("Carboidrati", avg.c, avg.c * 4 / en, Lemon)
                    MacroCol("Proteine", avg.p, avg.p * 4 / en, cs.primary)
                    MacroCol("Grassi", avg.f, avg.f * 9 / en, Orange)
                }
            }
        }
        val quota = (QUOTA * 100 * (if (m.meals == "both") 2 else 1)).roundToInt()
        Text(
            "Pranzo e cena coprono circa il $quota% delle calorie giornaliere, il resto è colazione e spuntini. " +
                "Nelle calorie dei pasti ci sono circa ${OLIO.roundToInt()} kcal di olio e condimenti. Valori stimati.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
fun MacroCol(nome: String, g: Double, share: Double, colore: Color) {
    Column {
        Box(Modifier.width(28.dp).height(4.dp).background(colore))
        Text("${g.roundToInt()} g", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text("$nome, ${(share * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Modifica degli ingredienti di un pasto ----
@Composable
fun SmallBtn(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
}

@Composable
fun EditDialog(m: Model, s: Slot, idx: Int, onClose: () -> Unit) {
    val cat = m.catalog
    val cs = MaterialTheme.colorScheme
    val orig = cat.byName[s.recipe.nome]?.ing ?: s.recipe.ing
    var draft by remember { mutableStateOf(s.recipe.ing) }
    var picking by remember { mutableStateOf<String?>(null) }
    val rec = s.recipe.copy(ing = draft)
    var titolo by remember { mutableStateOf(s.titolo ?: s.recipe.nome) }
    var manuale by remember { mutableStateOf(false) }
    // quando cambiano i macroingredienti il titolo si aggiorna da solo, finché non lo scrivi tu a mano
    fun cambia(nuovo: Map<String, Double>) {
        if (!manuale) titolo = cat.titoloAggiornato(titolo, draft.keys, nuovo.keys)
        draft = nuovo
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.padding(16.dp)) {
                Text("Modifica gli ingredienti", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(
                    value = titolo, onValueChange = { titolo = it; manuale = true }, label = { Text("Nome del piatto") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Le porzioni si ricalcolano da sole per restare sulle calorie di ciascuno: se aumenti un ingrediente, gli altri diminuiscono un po’.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(draft.keys.toList(), key = { k -> k }) { k ->
                        val i = cat.ing.getValue(k)
                        AppCard(Modifier.padding(vertical = 5.dp)) {
                            Text(i.nome, fontWeight = FontWeight.SemiBold)
                            Text(
                                PERSONE.joinToString(", ") { p ->
                                    p.nome.lowercase() + " " + qty(rq(draft.getValue(k) * cat.pm(rec, p) * s.mult, i.unita), i.unita)
                                },
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                            Row {
                                SmallBtn("Meno") { draft = cat.scale(draft, k, 0.8) }
                                SmallBtn("Più") { draft = cat.scale(draft, k, 1.25) }
                                SmallBtn("Sostituisci") { picking = k }
                                if (draft.size > 1) SmallBtn("Togli") { cambia(draft - k) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    SmallBtn("Aggiungi ingrediente") { picking = "" }
                    if (draft != orig || titolo != s.recipe.nome) SmallBtn("Ripristina") { titolo = s.recipe.nome; manuale = false; draft = orig }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Annulla") }
                    Button(onClick = { m.updateSlot(idx, draft, titolo); onClose() }, modifier = Modifier.weight(1f)) { Text("Salva") }
                }
            }
        }
    }
    val pk = picking
    if (pk != null) {
        PickDialog(
            cat, draft.keys,
            onPick = { id ->
                cambia(if (pk.isEmpty()) cat.addIng(draft, id) else cat.replaceIng(draft, pk, id))
                picking = null
            },
            onClose = { picking = null },
        )
    }
}

@Composable
fun PickDialog(cat: Catalog, exclude: Set<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Text("Scegli un ingrediente", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, label = { Text("Cerca") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    for (rep in REPARTI) {
                        val l = cat.ing.values
                            .filter { it.reparto == rep && it.id !in exclude && it.nome.contains(q.trim(), ignoreCase = true) }
                            .sortedBy { it.nome }
                        if (l.isEmpty()) continue
                        item(key = "pick-$rep") {
                            Text(
                                rep, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(l, key = { x -> x.id }) { x ->
                            Text(x.nome, Modifier.fillMaxWidth().clickable { onPick(x.id) }.padding(vertical = 12.dp))
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Annulla") }
            }
        }
    }
}

// ---- Scelta di un'altra ricetta per un pasto ----
/** Testo senza accenti e in minuscolo, per la ricerca: "pure" trova anche "Purè". */
fun perRicerca(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ITALY)

/**
 * Le ricette che contengono tutte le parole cercate, in qualsiasi ordine ("pollo zucchine" trova
 * "Pollo con zucchine"), ordinate per nome. Quella attuale non c'è.
 */
fun cercaRicette(ricette: List<Recipe>, cerca: String, attuale: String): List<Recipe> {
    val parole = perRicerca(cerca).split(" ").filter { it.isNotBlank() }
    return ricette
        .filter { it.nome != attuale }
        .filter { r -> val n = perRicerca(r.nome); parole.all { it in n } }
        .sortedBy { perRicerca(it.nome) }
}

@Composable
fun RecipeDialog(m: Model, s: Slot, idx: Int, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val nelMenu = remember(m.plan) { m.plan.orEmpty().map { it.recipe.nome }.toSet() }
    val lista = cercaRicette(m.catalog.pool, q, s.recipe.nome)
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.padding(16.dp)) {
                Text("Scegli il piatto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    s.meal + " di " + GIORNI[s.day % 7].lowercase(Locale.ITALY) + ", ora: " + (s.titolo ?: s.recipe.nome),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, label = { Text("Cerca per nome") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                )
                // il campo di ricerca è già attivo: basta iniziare a scrivere
                LaunchedEffect(Unit) {
                    try {
                        focus.requestFocus()
                    } catch (e: Exception) {
                        // se non riesce, basta toccare il campo
                    }
                }
                OutlinedButton(
                    onClick = { m.swap(idx); onClose() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Scegline uno a caso") }
                if (s.mult > 1) {
                    Text(
                        "Questa cena vale anche per il pranzo di domani: meglio un piatto buono anche il giorno dopo.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                    if (lista.isEmpty()) {
                        item(key = "vuoto") {
                            Text(
                                "Nessuna ricetta contiene “${q.trim()}”.",
                                color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(lista, key = { r -> r.nome }) { r ->
                        Column(
                            Modifier.fillMaxWidth().clickable { m.choose(idx, r); onClose() }.padding(vertical = 10.dp),
                        ) {
                            Text(r.nome)
                            val note = listOfNotNull(
                                if (r.giornoDopo) "Buona anche il giorno dopo" else null,
                                if (r.nome in nelMenu) "Già nel menu" else null,
                            )
                            if (note.isNotEmpty()) {
                                Text(note.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Annulla") }
            }
        }
    }
}

// ---- Invio dei pasti a Google Calendar ----
@Composable
fun CalendarButton(m: Model, slots: List<Slot>) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val perms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) dialog = true else msg = "Senza il permesso per il calendario non posso inviare i pasti."
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)) {
        Button(
            onClick = {
                msg = null
                val ok = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
                if (ok) dialog = true else launcher.launch(perms)
            },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Conferma e invia a Google Calendar") }
        val t = msg
        if (t != null) {
            Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (dialog) {
        CalendarDialog(
            ctx, slots, m.catalog, m.inizioMenu,
            onInviato = { d -> m.inviato(d) },
            onClose = { dialog = false },
            onDone = { text -> msg = text; dialog = false },
        )
    }
}

@Composable
fun CalendarDialog(
    ctx: Context, slots: List<Slot>, cat: Catalog, preferito: LocalDate?,
    onInviato: (LocalDate) -> Unit, onClose: () -> Unit, onDone: (String) -> Unit,
) {
    val tutti = remember { CalendarSync.calendars(ctx) }
    val cals = tutti.filter { it.scrivibile && it.sincronizzato }
    val esclusi = tutti.filter { !(it.scrivibile && it.sincronizzato) }
    // il lunedì di questa settimana (per rimandare un menu già in corso), il prossimo e quello dopo
    val opzioni = remember {
        val oggi = LocalDate.now()
        val questa = oggi.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val prossimo = oggi.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        (listOfNotNull(preferito) + listOf(questa, prossimo, prossimo.plusWeeks(1))).distinct().sortedBy { it.toEpochDay() }
    }
    var start by remember {
        mutableStateOf(preferito ?: CalendarSync.lastStart(ctx)?.takeIf { it in opzioni } ?: opzioni.first { !it.isBefore(LocalDate.now()) })
    }
    var cal by remember {
        val saved = CalendarSync.savedCalendar(ctx)
        mutableStateOf<Long?>(if (saved != null && cals.any { it.id == saved }) saved else cals.firstOrNull()?.id)
    }
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALY) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Invia al calendario") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cals.isEmpty()) {
                    Text("Non trovo nessun calendario su cui scrivere. Controlla che Google Calendar sia installato e sincronizzato con il tuo account.")
                } else {
                    Text(
                        "Pranzo alle ${CalendarSync.ORA_PRANZO}:00 e cena alle ${CalendarSync.ORA_CENA}:00, con il nome del piatto. " +
                            "Nella descrizione di ogni evento c'è una riga che serve all'app per recuperare il menu dal calendario."
                    )
                    Text("Da quale lunedì parte il menu?", fontWeight = FontWeight.SemiBold)
                    for (d in opzioni) {
                        Choice(d.format(fmt), start == d, Modifier.fillMaxWidth()) { start = d }
                    }
                    Text("Su quale calendario?", fontWeight = FontWeight.SemiBold)
                    for (c in cals) {
                        Choice(
                            if (c.nome == c.account) c.nome else c.nome + "\n" + c.account,
                            cal == c.id, Modifier.fillMaxWidth(),
                        ) { cal = c.id }
                    }
                }
                if (esclusi.isNotEmpty()) {
                    Text(
                        "Non utilizzabili: " + esclusi.joinToString(", ") {
                            it.nome + (if (!it.sincronizzato) " (non sincronizzato)" else " (sola lettura)")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Non vedi il calendario che cerchi? Apri Google Calendar, vai in Impostazioni, tocca il tuo account e attiva la sincronizzazione di quel calendario.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (cals.isNotEmpty()) {
                TextButton(onClick = {
                    val c = cal
                    if (c != null) {
                        try {
                            val (n, vecchi) = CalendarSync.send(ctx, c, slots, start, cat)
                            onInviato(start)
                            onDone("Inviati $n pasti a Google Calendar." + (if (vecchi > 0) " Sostituiti $vecchi eventi precedenti." else ""))
                        } catch (e: Exception) {
                            onDone("Non sono riuscito a scrivere sul calendario.")
                        }
                    }
                }) { Text("Invia") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )
}

// ---- Invio della lista a Bring! ----
fun bringRiga(x: Item): String =
    if (x.unita == "pz") num(x.q) + " " + x.nome else qty(x.q, x.unita) + " " + x.nome

@Composable
fun BringButton(m: Model, lista: List<Item>) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE) }
    // da comprare = tutto ciò che NON è stato spuntato
    val daComprare = lista.filter { it.id !in m.checked }.sortedWith(compareBy({ REPARTI.indexOf(it.reparto) }, { it.nome }))
    var dialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(prefs.getString("bringUrl", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp)) {
        Button(
            onClick = { msg = null; dialog = true },
            enabled = daComprare.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Invia a Bring (${daComprare.size} da comprare)") }
        val t = msg
        if (t != null) {
            Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = false },
            title = { Text("Invia a Bring") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verranno inviati ${daComprare.size} ingredienti. Quelli che hai già spuntato restano fuori.")
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("Indirizzo del tuo Worker") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        busy = true
                        prefs.edit().putString("bringUrl", url.trim()).apply()
                        BringSync.deeplink(url, daComprare.map { bringRiga(it) }) { link, err ->
                            busy = false
                            dialog = false
                            if (link != null) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                } catch (e: Exception) {
                                    msg = "Non riesco ad aprire Bring. È installata?"
                                }
                            } else {
                                msg = err
                            }
                        }
                    },
                ) { Text(if (busy) "Invio in corso" else "Invia") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }, enabled = !busy) { Text("Annulla") } },
        )
    }
}

// ---- Aggiornamento di ricette e ingredienti da internet ----
@Composable
fun UpdateButton(m: Model) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("tavola", Context.MODE_PRIVATE) }
    var dialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(prefs.getString("dataUrl", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        TextButton(onClick = { msg = null; dialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Aggiorna ricette e ingredienti")
        }
        val t = msg
        if (t != null) {
            Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = false },
            title = { Text("Aggiorna ricette") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Su GitHub apri recipes.json, tocca “Raw” e incolla qui l’indirizzo che vedi nella barra.")
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("Indirizzo del file") },
                        maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        busy = true
                        prefs.edit().putString("dataUrl", url.trim()).apply()
                        Updater.aggiorna(ctx.applicationContext, url) { cambiato, messaggio, errore ->
                            busy = false
                            dialog = false
                            if (errore != null) {
                                msg = errore
                            } else {
                                if (cambiato) m.reload(ctx.applicationContext)
                                msg = messaggio
                            }
                        }
                    },
                ) { Text(if (busy) "Scarico" else "Scarica") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }, enabled = !busy) { Text("Annulla") } },
        )
    }
}

// ---- Recupero di un menù salvato ----
fun dataIt(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALY))

/** Il nome proposto quando salvi il menu: quello con cui l'hai già salvato, oppure la sua settimana, oppure la data di oggi. */
fun nomeProposto(m: Model): String =
    m.nomeMenu
        ?: m.inizioMenu?.let { "Settimana dal " + it.format(GIORNO_MESE) }
        ?: ("Menu del " + LocalDate.now().format(GIORNO_MESE))

// ---- Salvataggio del menu nel telefono ----
@Composable
fun SalvaButton(m: Model) {
    var dialog by remember { mutableStateOf(false) }
    var nome by remember { mutableStateOf("") }
    var nomiSalvati by remember { mutableStateOf<Set<String>>(emptySet()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        OutlinedButton(
            onClick = {
                nome = nomeProposto(m)
                nomiSalvati = m.salvati().map { it.nome.lowercase(Locale.ITALY) }.toSet()
                msg = null
                dialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Salva nel telefono") }
        val t = msg
        if (t != null) {
            Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (dialog) {
        val esiste = nome.trim().lowercase(Locale.ITALY) in nomiSalvati
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Salva il menu nel telefono") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lo ritrovi con «Recupera un menù salvato», nella schermata iniziale.")
                    OutlinedTextField(
                        value = nome, onValueChange = { nome = it }, label = { Text("Nome") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (esiste) {
                        Text(
                            "C'è già un menu salvato con questo nome: verrà sostituito.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (m.salvaLocale(nome)) {
                            msg = "Menu salvato nel telefono come «" + nome.trim() + "»."
                            dialog = false
                        }
                    },
                    enabled = nome.isNotBlank(),
                ) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Annulla") } },
        )
    }
}

// ---- Recupero di un menu: dal telefono o da Google Calendar ----
private enum class FaseRecupero { SCELTA, TELEFONO, CALENDARIO }

@Composable
fun RecuperaButton(m: Model) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var fase by remember { mutableStateOf<FaseRecupero?>(null) }
    var locali by remember { mutableStateOf<List<MenuSalvato>>(emptyList()) }
    var settimane by remember { mutableStateOf<List<MenuCalendario>>(emptyList()) }
    var errore by remember { mutableStateOf<String?>(null) }
    var daEliminare by remember { mutableStateOf<MenuSalvato?>(null) }

    fun apriCalendario() {
        errore = null
        settimane = try {
            CalendarSync.leggiMenu(ctx, m.catalog)
        } catch (e: Exception) {
            errore = "Non sono riuscito a leggere il calendario."
            emptyList()
        }
        fase = FaseRecupero.CALENDARIO
    }
    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            apriCalendario()
        } else {
            errore = "Senza il permesso per il calendario non posso leggere i menu."
            settimane = emptyList()
            fase = FaseRecupero.CALENDARIO
        }
    }

    OutlinedButton(
        onClick = { fase = FaseRecupero.SCELTA },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) { Text("Recupera un menù salvato") }

    when (fase) {
        FaseRecupero.SCELTA -> AlertDialog(
            onDismissRequest = { fase = null },
            title = { Text("Recupera un menù salvato") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Da dove vuoi recuperarlo?")
                    Choice("Dal telefono\nI menu che hai salvato con «Salva nel telefono»", false, Modifier.fillMaxWidth()) {
                        locali = m.salvati()
                        fase = FaseRecupero.TELEFONO
                    }
                    Choice("Da Google Calendar\nLe settimane che hai inviato al calendario", false, Modifier.fillMaxWidth()) {
                        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                        if (ok) apriCalendario() else permesso.launch(Manifest.permission.READ_CALENDAR)
                    }
                    if (m.plan != null) {
                        Text(
                            "Il menu che hai adesso verrà sostituito: se vuoi tenerlo, salvalo prima.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fase = null }) { Text("Annulla") } },
        )

        FaseRecupero.TELEFONO -> AlertDialog(
            onDismissRequest = { fase = null },
            title = { Text("Menu salvati nel telefono") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (locali.isEmpty()) {
                        Text("Non hai ancora salvato nessun menu. Crea un menu e tocca «Salva nel telefono», in fondo alla pagina del menu.")
                    }
                    for (s in locali) {
                        val dettagli = when {
                            !s.leggibile -> "Non più leggibile: le ricette sono cambiate"
                            s.dalVecchioArchivio -> "${s.pasti} pasti"
                            else -> "${s.pasti} pasti · salvato il " +
                                Instant.ofEpochMilli(s.quando).atZone(ZoneId.systemDefault()).toLocalDate().format(GIORNO_MESE)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Choice(s.nome + "\n" + dettagli, false, Modifier.weight(1f)) {
                                if (m.recuperaLocale(s.id)) fase = null
                            }
                            TextButton(
                                onClick = { daEliminare = s },
                                colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                            ) { Text("Elimina") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fase = null }) { Text("Chiudi") } },
            dismissButton = { TextButton(onClick = { fase = FaseRecupero.SCELTA }) { Text("Indietro") } },
        )

        FaseRecupero.CALENDARIO -> AlertDialog(
            onDismissRequest = { fase = null },
            title = { Text("Menu in Google Calendar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val e = errore
                    if (e != null) {
                        Text(e)
                    } else if (settimane.isEmpty()) {
                        Text("Nel calendario non trovo menu inviati con l'app negli ultimi 6 mesi o nelle prossime settimane.")
                    } else {
                        Text("Il menu torna com'è adesso nel calendario: se lì hai tolto o spostato un pasto, lo ritrovi così.")
                        for (w in settimane) {
                            val extra = if (w.nonRiconosciuti > 0) "\n${w.nonRiconosciuti} pasti non riconosciuti, saltati" else ""
                            Choice("Dal " + dataIt(w.lunedi) + "\n" + w.pasti.size + " pasti" + extra, false, Modifier.fillMaxWidth()) {
                                if (m.recuperaDaCalendario(w)) fase = null
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fase = null }) { Text("Chiudi") } },
            dismissButton = { TextButton(onClick = { fase = FaseRecupero.SCELTA }) { Text("Indietro") } },
        )

        null -> {}
    }

    val s = daEliminare
    if (s != null) {
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare «${s.nome}»?") },
            text = { Text("Il menu salvato nel telefono verrà cancellato. Il menu che hai adesso e il calendario non cambiano.") },
            confirmButton = {
                TextButton(onClick = {
                    m.eliminaLocale(s.id)
                    locali = m.salvati()
                    daEliminare = null
                }) { Text("Elimina", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { daEliminare = null }) { Text("Annulla") } },
        )
    }
}
