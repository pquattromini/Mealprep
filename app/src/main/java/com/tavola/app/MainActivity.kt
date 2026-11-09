package com.tavola.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** Per quanto tempo resta l'icona all'apertura dell'app, in millisecondi. */
const val DURATA_AVVIO_MS = 2000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val avvio = installSplashScreen()
        super.onCreate(savedInstanceState)
        // L'icona resta sullo schermo per DURATA_AVVIO_MS, poi compare il menù.
        // Solo all'apertura: se l'app si ricrea (per esempio ruotando il telefono) non si aspetta.
        if (savedInstanceState == null) {
            val fine = SystemClock.uptimeMillis() + DURATA_AVVIO_MS
            avvio.setKeepOnScreenCondition { SystemClock.uptimeMillis() < fine }
        }
        enableEdgeToEdge()
        setContent { TavolaTheme { TavolaApp() } }
    }
}
