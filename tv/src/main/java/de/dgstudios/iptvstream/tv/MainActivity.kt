package de.dgstudios.iptvstream.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Wie am Handy: Ab Android 15 zeichnen Apps immer randlos. Fernseher haben meist keine
        // Systemleisten (Abstand 0); falls doch, hält der Abstand die Oberfläche frei – genau wie
        // vorher mit den Systemleisten, nur jetzt ausdrücklich statt vom System.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF05070F))
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                TvApp()
            }
        }
    }
}
