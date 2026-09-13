package com.teledrive.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.teledrive.app.telegram.TdLibAuthState
import com.teledrive.app.theme.TeleDriveTheme
import com.teledrive.app.ui.navigation.NavGraph
import com.teledrive.app.ui.navigation.Screen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        TeleDriveApplication.instance.deviceMediaRepository.invalidateCache()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val requiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missingPermissions = requiredPermissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }

        val sharedUris = handleIntent(intent)

        setContent {
            val app = TeleDriveApplication.instance
            val themeMode by app.preferences.themeMode.collectAsState(initial = "system")
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            val authState by app.tdLibManager.authState.collectAsState()
            val localGalleryMode by app.preferences.localGalleryMode.collectAsState(initial = false)
            val botToken by app.preferences.botToken.collectAsState(initial = "")
            val loginType by app.preferences.loginType.collectAsState(initial = "phone")
            val storageChatId by app.preferences.storageChatId.collectAsState(initial = app.preferences.getCachedStorageChatId())

            TeleDriveTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (authState) {
                        is TdLibAuthState.Initial -> {
                            SplashScreen()
                        }
                        else -> {
                            val isAuthed = (authState is TdLibAuthState.Ready) ||
                                           (loginType == "web" && (storageChatId != null && storageChatId != 0L)) ||
                                           localGalleryMode ||
                                           (loginType == "bot" && botToken.isNotBlank())

                            val startDestination = if (isAuthed) {
                                Screen.Explorer.createRoute("/")
                            } else {
                                Screen.Auth.route
                            }

                            key(startDestination) {
                                val navController = rememberNavController()
                                NavGraph(
                                    navController = navController,
                                    startDestination = startDestination,
                                    shareUris = sharedUris
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type != null) {
                    val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    uri?.let { uris.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
                    } else {
                        @Suppress("DEPRECATION")
                        val uriList = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        uriList?.let { uris.addAll(it) }
                    }
                }
            }
        }
        return uris
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.CloudQueue,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "TeleDrive",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
        }
    }
}
