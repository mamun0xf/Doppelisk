package com.doppelisk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.doppelisk.app.ui.DoppeliskRoot
import com.doppelisk.app.ui.theme.DoppeliskTheme

/**
 * The manager's only activity.
 *
 * It exists to host Compose and to re-read the framework scope when the app comes
 * back to the foreground — the user may have changed the LSPosed scope while the
 * manager was in the background, and a stale "not hooked" badge is exactly the
 * kind of wrong the UI must not show.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DoppeliskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DoppeliskRoot()
                }
            }
        }
    }
}
