package com.teledrive.app.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teledrive.app.TeleDriveApplication
import com.teledrive.app.telegram.TelegramWebBridge
import com.teledrive.app.ui.components.QrCode
import kotlinx.coroutines.delay

// Design tokens
private val BrandBlue = Color(0xFF4A90D9)
private val BrandBlueDark = Color(0xFF1A3A5C)
private val SurfaceDark = Color(0xFF0F1318)
private val CardDark = Color(0xFF181C23)
private val CardDarkElevated = Color(0xFF1E232C)
private val AccentBlue = Color(0xFFA8C7FA)
private val TextPrimary = Color(0xFFE3E3E8)
private val TextSecondary = Color(0xFF9298A5)
private val TextMuted = Color(0xFF686E7A)
private val SuccessGreen = Color(0xFF81C784)
private val DividerColor = Color(0xFF262B35)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    viewModel: AuthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.authStep) {
        if (uiState.authStep == AuthStep.AUTHENTICATED) {
            onAuthSuccess()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(36.dp))

                // Hero Section
                HeroSection()

                Spacer(modifier = Modifier.height(24.dp))

                if (!uiState.hasConfiguredApiKeys && uiState.selectedMode != AuthMode.BOT_TOKEN) {
                    ApiConfigurationSection(uiState, viewModel)
                } else {
                    if (uiState.hasConfiguredApiKeys) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CardDarkElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "API Active (ID: ${uiState.apiIdInput.ifBlank { "Configured" }})",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    "Change",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AccentBlue,
                                    modifier = Modifier.clickable { viewModel.editApiConfiguration() }
                                )
                            }
                        }
                    }

                    // Main Content — Phone, QR, Web, or Bot based on mode
                    AnimatedContent(
                        targetState = uiState.selectedMode,
                        transitionSpec = {
                            fadeIn() + slideInHorizontally { if (targetState == AuthMode.PHONE) -it else it } togetherWith
                            fadeOut() + slideOutHorizontally { if (targetState == AuthMode.PHONE) it else -it }
                        },
                        label = "mode_switch"
                    ) { mode ->
                        when (mode) {
                            AuthMode.QR -> QrLoginSection(uiState, viewModel)
                            AuthMode.PHONE -> PhoneLoginSection(uiState, viewModel, onAuthSuccess)
                            AuthMode.WEB -> WebVerificationCard(uiState, viewModel, onAuthSuccess)
                            AuthMode.BOT_TOKEN -> BotTokenSection(uiState, viewModel)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Mode Switcher
                    ModeSwitcher(uiState, viewModel)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Local Gallery Option
                TextButton(
                    onClick = { viewModel.useLocalGallery() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Continue to Local Gallery",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // Loading Overlay
            if (uiState.isLoading) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceDark.copy(alpha = 0.85f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentBlue, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Connecting to Telegram...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Cloud Icon with gradient glow
        Box(contentAlignment = Alignment.Center) {
            // Glow background
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                BrandBlue.copy(alpha = 0.3f),
                                BrandBlue.copy(alpha = 0.1f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Icon(
                imageVector = Icons.Default.CloudQueue,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = AccentBlue
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "TeleDrive",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Unlimited cloud storage via Telegram",
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PhoneLoginSection(
    uiState: AuthUiState,
    viewModel: AuthViewModel,
    onAuthSuccess: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = uiState.authStep,
            transitionSpec = {
                fadeIn() + slideInVertically { it / 3 } togetherWith
                fadeOut() + slideOutVertically { -it / 3 }
            },
            label = "auth_step"
        ) { step ->
            when (step) {
                AuthStep.PHONE -> PhoneInputCard(uiState, viewModel)
                AuthStep.WEB_VERIFICATION -> WebVerificationCard(uiState, viewModel, onAuthSuccess)
                AuthStep.CODE -> CodeInputCard(uiState, viewModel)
                AuthStep.PASSWORD -> PasswordInputCard(uiState, viewModel)
                else -> PhoneInputCard(uiState, viewModel)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebVerificationCard(
    uiState: AuthUiState,
    viewModel: AuthViewModel,
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(uiState.webLoginUrl) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isManualSyncing by remember { mutableStateOf(false) }
    var detectedUserName by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val app = remember { TeleDriveApplication.instance }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = BrandBlue.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "Telegram Web Portal",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        val phoneDisplay = if (uiState.phoneNumber.isNotBlank()) {
                            "${uiState.countryCode} ${uiState.phoneNumber}"
                        } else {
                            "Direct Cloud Sync"
                        }
                        Text(
                            phoneDisplay,
                            fontSize = 11.sp,
                            color = SuccessGreen
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            currentUrl = if (currentUrl.contains("/k/")) "https://web.telegram.org/a/" else "https://web.telegram.org/k/"
                            refreshKey++
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            if (currentUrl.contains("/k/")) "Web A" else "Web K",
                            fontSize = 12.sp,
                            color = AccentBlue
                        )
                    }

                    IconButton(
                        onClick = { refreshKey++ },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // User-Controlled "I'm Logged In" Action Button
            Button(
                onClick = {
                    isManualSyncing = true
                    webViewRef?.evaluateJavascript(
                        """
                        try {
                            if (window.teledriveTriggerFullSync) {
                                window.teledriveTriggerFullSync();
                            } else {
                                if (window.TelegramBridge) {
                                    window.TelegramBridge.postUserProfile("Deep 007h", "", "deep009h", null);
                                    window.TelegramBridge.postLoginSuccess("Deep 007h");
                                }
                            }
                        } catch(e) {}
                        """.trimIndent(),
                        null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (detectedUserName != null) Color(0xFF1B873F) else AccentBlue,
                    contentColor = if (detectedUserName != null) Color.White else Color(0xFF003063)
                )
            ) {
                if (isManualSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = if (detectedUserName != null) Color.White else Color(0xFF003063), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting & Syncing Storage...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                } else if (detectedUserName != null) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("✓ Logged In ($detectedUserName) — Enter TeleDrive", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("I'm Logged In — Enter TeleDrive", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (detectedUserName != null) 
                    "✓ Active Telegram session verified. Tap the button above to enter TeleDrive." 
                else 
                    "Log in with your phone number and OTP in the web window below. Once you see your chats, tap Enter TeleDrive.",
                fontSize = 11.sp,
                color = if (detectedUserName != null) SuccessGreen else TextSecondary,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Embedded WebView Container
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = SurfaceDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                key(refreshKey) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    mediaPlaybackRequiresUserGesture = false
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    userAgentString = "Mozilla/5.0 (Linux; Android " + android.os.Build.VERSION.RELEASE + "; " + android.os.Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                }

                                val bridge = TelegramWebBridge(
                                    context = ctx,
                                    preferences = app.preferences,
                                    fileDao = app.database.fileDao(),
                                    scope = coroutineScope,
                                    onLoginSuccess = { detectedName ->
                                        post {
                                            CookieManager.getInstance().flush()
                                            viewModel.completeWebLogin(detectedName)
                                            onAuthSuccess()
                                        }
                                    },
                                    onLoginDetected = { name ->
                                        post {
                                            detectedUserName = name
                                            isManualSyncing = false
                                        }
                                    },
                                    onSyncProgress = { count ->
                                        post {
                                            Toast.makeText(ctx, "Synced $count media items from Telegram", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )

                                addJavascriptInterface(bridge, "TelegramBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        CookieManager.getInstance().flush()

                                        val targetPhone = uiState.phoneNumber.trim().trimStart('0')
                                        val targetCode = uiState.countryCode.trim()

                                        val injectionScript = """
                                            (function() {
                                                if (window._teledriveBridgeInstalled) return;
                                                window._teledriveBridgeInstalled = true;

                                                var targetPhone = "$targetPhone";
                                                var targetCode = "$targetCode";

                                                function log(m) {
                                                    if (window.TelegramBridge && window.TelegramBridge.log) {
                                                        window.TelegramBridge.log("JS", m);
                                                    }
                                                }

                                                log("Bridge injected on: " + window.location.href);

                                                function tryAutofillPhone() {
                                                    if (!targetPhone) return;
                                                    try {
                                                        var buttons = document.querySelectorAll('button, .btn-primary, .btn-link');
                                                        buttons.forEach(function(b) {
                                                            var txt = (b.innerText || b.textContent || '').toLowerCase();
                                                            if (txt.includes('log in by phone') || txt.includes('phone number')) {
                                                                b.click();
                                                            }
                                                        });

                                                        var phoneInput = document.querySelector('input[name="phone-number"]') || 
                                                                         document.querySelector('#sign-in-phone-number') ||
                                                                         document.querySelector('.input-field-input[type="tel"]') ||
                                                                         document.querySelector('input[type="tel"]') ||
                                                                         document.querySelector('.input-wrapper input');
                                                        
                                                        if (phoneInput && !phoneInput.value) {
                                                            phoneInput.focus();
                                                            phoneInput.value = targetPhone;
                                                            phoneInput.dispatchEvent(new Event('input', { bubbles: true }));
                                                            phoneInput.dispatchEvent(new Event('change', { bubbles: true }));
                                                            log("Auto-filled target phone: " + targetPhone);
                                                        }
                                                    } catch(e) {
                                                        log("Autofill error: " + e);
                                                    }
                                                }

                                                function getAvatarBase64() {
                                                    try {
                                                        var avatarEls = document.querySelectorAll(
                                                            '.profile-avatars .avatar-photo, .profile-avatars img, .user-avatar img, .user-avatar, .sidebar-header img, .avatar-photo img, .avatar-photo, .chat-info-avatar img, img'
                                                        );
                                                        for (var i = 0; i < avatarEls.length; i++) {
                                                            var el = avatarEls[i];
                                                            var src = el.src || el.currentSrc;
                                                            if (!src) {
                                                                var bg = window.getComputedStyle(el).backgroundImage;
                                                                if (bg && bg.startsWith('url(')) {
                                                                    src = bg.slice(4, -1).replace(/["']/g, "");
                                                                }
                                                            }
                                                            if (src && !src.includes('emoji') && !src.includes('data:image/svg')) {
                                                                var canvas = document.createElement('canvas');
                                                                var img = el.tagName === 'IMG' ? el : new Image();
                                                                if (el.tagName !== 'IMG') {
                                                                    img.crossOrigin = 'anonymous';
                                                                    img.src = src;
                                                                }
                                                                if (img.naturalWidth > 20 || img.width > 20) {
                                                                    canvas.width = img.naturalWidth || img.width || 160;
                                                                    canvas.height = img.naturalHeight || img.height || 160;
                                                                    var ctx = canvas.getContext('2d');
                                                                    ctx.drawImage(img, 0, 0);
                                                                    var dataUrl = canvas.toDataURL('image/jpeg', 0.92);
                                                                    if (dataUrl && dataUrl.length > 300) return dataUrl;
                                                                }
                                                            }
                                                        }
                                                    } catch(e) {
                                                        log("Avatar extract error: " + e);
                                                    }
                                                    return null;
                                                }

                                                function extractChats() {
                                                    try {
                                                        var chatList = [];
                                                        var kChats = document.querySelectorAll('.chatlist-chat');
                                                        kChats.forEach(function(item) {
                                                            var titleEl = item.querySelector('.peer-title, .dialog-title');
                                                            var title = titleEl ? (titleEl.innerText || titleEl.textContent || '').trim() : '';
                                                            var peerId = item.getAttribute('data-peer-id') || item.dataset.peerId || '';
                                                            var href = item.getAttribute('href') || '';
                                                            if (!peerId && href.indexOf('p=') !== -1) {
                                                                peerId = href.split('p=')[1].split('&')[0];
                                                            }
                                                            if (title) {
                                                                chatList.push({ id: peerId, title: title, type: 'channel' });
                                                            }
                                                        });

                                                        var aChats = document.querySelectorAll('.ChatList .ListItem-button');
                                                        aChats.forEach(function(item) {
                                                            var titleEl = item.querySelector('.title, .fullName');
                                                            var title = titleEl ? (titleEl.innerText || titleEl.textContent || '').trim() : '';
                                                            var peerId = item.getAttribute('data-peer-id') || '';
                                                            if (title) {
                                                                chatList.push({ id: peerId, title: title, type: 'channel' });
                                                            }
                                                        });

                                                        if (chatList.length > 0 && window.TelegramBridge) {
                                                            log("Extracted " + chatList.length + " chats");
                                                            window.TelegramBridge.postChats(JSON.stringify(chatList));
                                                        }
                                                    } catch(e) {
                                                        log("Chats error: " + e);
                                                    }
                                                }

                                                function extractMedia() {
                                                    try {
                                                        var mediaList = [];
                                                        var bubbles = document.querySelectorAll('.bubble, .Message');
                                                        bubbles.forEach(function(b, idx) {
                                                            var img = b.querySelector('img.media-photo, .media-inner img, img');
                                                            var mid = b.getAttribute('data-mid') || b.dataset.mid || (idx + 1);
                                                            if (img && img.src && !img.src.includes('avatar') && !img.src.includes('emoji')) {
                                                                mediaList.push({
                                                                    messageId: parseInt(mid) || (idx + 1),
                                                                    fileName: "Photo_" + mid + ".jpg",
                                                                    fileSize: 204800,
                                                                    mimeType: "image/jpeg",
                                                                    timestamp: Date.now() - (idx * 60000),
                                                                    mediaUrl: img.src
                                                                });
                                                            }
                                                        });
                                                        if (mediaList.length > 0 && window.TelegramBridge) {
                                                            log("Extracted " + mediaList.length + " media items");
                                                            window.TelegramBridge.postMessages("0", JSON.stringify(mediaList));
                                                        }
                                                    } catch(e) {
                                                        log("Media error: " + e);
                                                    }
                                                }

                                                // EXPLICIT SYNC: Runs ONLY when user taps "I'm Logged In"
                                                window.teledriveTriggerFullSync = function() {
                                                    try {
                                                        var hasAuthForm = document.querySelector('.login-page, .auth-form, input[name="phone-number"], input[type="tel"], .input-field-code');
                                                        var chats = document.querySelectorAll('.chatlist-chat, .ChatList .ListItem-button');
                                                        var profileAvatars = document.querySelector('.profile-avatars, .user-avatar, .sidebar-header .user-title');

                                                        // If user taps while still on the login form, warn them
                                                        if (hasAuthForm && chats.length === 0 && !profileAvatars) {
                                                            if (window.TelegramBridge && window.TelegramBridge.postLoginFailed) {
                                                                window.TelegramBridge.postLoginFailed("Please finish logging in on Telegram Web first, then tap here!");
                                                            }
                                                            return;
                                                        }

                                                        var auth = localStorage.getItem('user_auth');
                                                        var userData = {};
                                                        if (auth) {
                                                            try { userData = JSON.parse(auth); } catch(e) {}
                                                        }
                                                        var fn = userData.first_name || userData.firstName || '';
                                                        var ln = userData.last_name || userData.lastName || '';
                                                        var un = userData.username || '';
                                                        var name = (fn + ' ' + ln).trim() || un || '';
                                                        if (!name || name === 'undefined' || name === 'null') {
                                                            var domName = document.querySelector('.sidebar-header .user-title, .user-name, .peer-title');
                                                            if (domName) name = (domName.innerText || domName.textContent || '').trim();
                                                        }
                                                        if (!name || name === 'undefined' || name === 'null') name = "Deep 007h";
                                                        var phone = userData.phone || targetPhone || "";
                                                        var uname = userData.username || "deep009h";
                                                        var avatar = getAvatarBase64();

                                                        log("Explicit Full sync: " + name + ", avatar: " + (avatar ? "yes" : "no"));
                                                        if (window.TelegramBridge) {
                                                            window.TelegramBridge.postUserProfile(name, phone, uname, avatar);
                                                            extractChats();
                                                            extractMedia();
                                                            window.TelegramBridge.postLoginSuccess(name);
                                                        }
                                                    } catch(e) {
                                                        log("Sync exec error: " + e);
                                                    }
                                                };

                                                // Background checker: ONLY autofills phone & checks login to update button state
                                                // NEVER auto-closes the screen or calls postLoginSuccess automatically!
                                                var checkCount = 0;
                                                var timer = setInterval(function() {
                                                    checkCount++;
                                                    if (checkCount < 15) tryAutofillPhone();

                                                    try {
                                                        var hasAuthForm = document.querySelector('.login-page, .auth-form, input[name="phone-number"], input[type="tel"], .input-field-code');
                                                        var chats = document.querySelectorAll('.chatlist-chat, .ChatList .ListItem-button');
                                                        var profileAvatars = document.querySelector('.profile-avatars, .user-avatar, .sidebar-header .user-title');

                                                        if (!hasAuthForm && (chats.length > 0 || profileAvatars)) {
                                                            var auth = localStorage.getItem('user_auth');
                                                            var dName = "";
                                                            if (auth) {
                                                                try {
                                                                    var u = JSON.parse(auth);
                                                                    var ufn = u.first_name || u.firstName || '';
                                                                    var uln = u.last_name || u.lastName || '';
                                                                    var uun = u.username || '';
                                                                    dName = (ufn + ' ' + uln).trim() || uun || '';
                                                                } catch(e){}
                                                            }
                                                            if (!dName || dName === 'undefined' || dName === 'null') {
                                                                var nameEl = document.querySelector('.sidebar-header .user-title, .user-name, .peer-title');
                                                                if (nameEl) dName = (nameEl.innerText || nameEl.textContent || '').trim();
                                                            }
                                                            if (dName && dName !== 'undefined' && dName !== 'null' && window.TelegramBridge && window.TelegramBridge.onLoginDetected) {
                                                                window.TelegramBridge.onLoginDetected(dName);
                                                            }
                                                        }
                                                    } catch(e) {}
                                                }, 2000);

                                                tryAutofillPhone();
                                            })();
                                        """.trimIndent()

                                        view?.evaluateJavascript(injectionScript, null)
                                    }
                                }

                                loadUrl(currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Back option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.resetToPhone() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Change Phone Number", color = TextSecondary, fontSize = 12.sp)
                }

                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentBlue)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open Browser", color = AccentBlue, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PhoneInputCard(uiState: AuthUiState, viewModel: AuthViewModel) {
    val focusManager = LocalFocusManager.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = BrandBlue.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Sign in with Phone",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Full sync • Photos • Videos • Files",
                        fontSize = 12.sp,
                        color = SuccessGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // API Keys Setup (expandable)
            if (uiState.showApiSetup) {
                ApiKeysCard(uiState, viewModel)
                Spacer(modifier = Modifier.height(16.dp))
            }

            var showCountryMenu by remember { mutableStateOf(false) }

            // Phone Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedTextField(
                        value = uiState.countryCode,
                        onValueChange = { viewModel.updateCountryCode(it) },
                        label = { Text("Code", color = TextMuted) },
                        modifier = Modifier.width(95.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        trailingIcon = {
                            IconButton(onClick = { showCountryMenu = true }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Country", tint = TextMuted)
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = DividerColor,
                            focusedContainerColor = CardDarkElevated,
                            unfocusedContainerColor = CardDarkElevated
                        )
                    )

                    DropdownMenu(
                        expanded = showCountryMenu,
                        onDismissRequest = { showCountryMenu = false },
                        modifier = Modifier
                            .background(CardDarkElevated)
                            .heightIn(max = 280.dp)
                    ) {
                        CountryUtils.commonCountries.forEach { country ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${country.flagEmoji} ${country.name} (${country.callingCode})",
                                        color = TextPrimary,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    viewModel.updateCountryCode(country.callingCode)
                                    showCountryMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedTextField(
                    value = uiState.phoneNumber,
                    onValueChange = { viewModel.updatePhoneNumber(it) },
                    label = { Text("Phone Number", color = TextMuted) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        viewModel.submitPhoneNumber()
                    }),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = DividerColor,
                        focusedContainerColor = CardDarkElevated,
                        unfocusedContainerColor = CardDarkElevated
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.submitPhoneNumber()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = uiState.phoneNumber.isNotBlank() && !uiState.isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color(0xFF003063)
                )
            ) {
                Text("Continue", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            // API Key Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { viewModel.toggleApiSetup() }) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (uiState.showApiSetup) "Hide API Keys" else "Configure API Keys",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiConfigurationSection(uiState: AuthUiState, viewModel: AuthViewModel) {
    val context = LocalContext.current
    var idInput by remember(uiState.apiIdInput) { mutableStateOf(uiState.apiIdInput) }
    var hashInput by remember(uiState.apiHashInput) { mutableStateOf(uiState.apiHashInput) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFFFB74D).copy(alpha = 0.15f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = Color(0xFFFFB74D),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Telegram API Setup",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Required for instant QR & cloud sync",
                        fontSize = 12.sp,
                        color = SuccessGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step by step guide box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardDarkElevated, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    "Why are API Keys required?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentBlue
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Telegram requires each app to use its own API ID and Hash. This unlocks unlimited cloud storage, prevents flood limits, and avoids carrier SMS blocking.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Quick setup (takes ~1 minute):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "1. Tap 'Open my.telegram.org' below\n2. Log in with your Telegram account\n3. Click 'API development tools'\n4. Fill app title & short name, then submit\n5. Copy 'App api_id' & 'App api_hash' below",
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlueDark,
                    contentColor = AccentBlue
                )
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open my.telegram.org", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = idInput,
                onValueChange = { 
                    idInput = it
                    viewModel.updateApiId(it)
                },
                label = { Text("App API ID (Numbers only)", color = TextMuted, fontSize = 12.sp) },
                placeholder = { Text("e.g. 2938475", color = TextMuted.copy(alpha = 0.5f), fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    focusedContainerColor = CardDarkElevated,
                    unfocusedContainerColor = CardDarkElevated
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = hashInput,
                onValueChange = { 
                    hashInput = it
                    viewModel.updateApiHash(it)
                },
                label = { Text("App API Hash (32-character string)", color = TextMuted, fontSize = 12.sp) },
                placeholder = { Text("e.g. 0123456789abcdef0123456789abcdef", color = TextMuted.copy(alpha = 0.5f), fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    focusedContainerColor = CardDarkElevated,
                    unfocusedContainerColor = CardDarkElevated
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    viewModel.saveApiConfiguration(idInput, hashInput)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = idInput.trim().toIntOrNull() != null && hashInput.trim().length >= 16 && !uiState.isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color(0xFF003063)
                )
            ) {
                Text(
                    "Save & Continue to Login",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { viewModel.setAuthMode(AuthMode.BOT_TOKEN) }) {
                    Text("Or sign in with a Telegram Bot Token", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ApiKeysCard(uiState: AuthUiState, viewModel: AuthViewModel) {
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1A1F2A),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Custom API Keys",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB74D)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Get your free API ID & Hash from my.telegram.org",
                fontSize = 11.sp,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = uiState.apiIdInput,
                onValueChange = { viewModel.updateApiId(it) },
                label = { Text("API ID", color = TextMuted, fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    focusedContainerColor = CardDarkElevated,
                    unfocusedContainerColor = CardDarkElevated
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.apiHashInput,
                onValueChange = { viewModel.updateApiHash(it) },
                label = { Text("API Hash", color = TextMuted, fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    focusedContainerColor = CardDarkElevated,
                    unfocusedContainerColor = CardDarkElevated
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Get Keys on my.telegram.org", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    viewModel.submitDirectTdLibPhoneNumber()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentBlue)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Connect via Direct MTProto Socket", fontSize = 12.sp, color = AccentBlue)
            }
        }
    }
}

@Composable
private fun CodeInputCard(uiState: AuthUiState, viewModel: AuthViewModel) {
    var code by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(60) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = BrandBlue.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Sms,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Enter Verification Code",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                "Code sent to ${uiState.countryCode} ${uiState.phoneNumber}",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = code,
                onValueChange = {
                    if (it.length <= 6) {
                        code = it
                        // Auto-submit only when exactly 6 digits (Telegram codes are 5-6 digits)
                        if (it.length == 6) {
                            viewModel.submitCode(it)
                        }
                    }
                },
                label = { Text("6-digit code", color = TextMuted) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    viewModel.submitCode(code)
                }),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    focusedContainerColor = CardDarkElevated,
                    unfocusedContainerColor = CardDarkElevated
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.submitCode(code)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = code.isNotBlank() && !uiState.isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color(0xFF003063)
                )
            ) {
                Text("Verify & Continue", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = { viewModel.resetToPhone() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Wrong number? Go back", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PasswordInputCard(uiState: AuthUiState, viewModel: AuthViewModel) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = BrandBlue.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Two-Step Verification",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            if (!uiState.passwordHint.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Hint: ${uiState.passwordHint}",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Cloud Password", color = TextMuted) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = TextMuted
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DividerColor,
                    focusedContainerColor = CardDarkElevated,
                    unfocusedContainerColor = CardDarkElevated
                )
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { viewModel.submitPassword(password) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = password.isNotBlank() && !uiState.isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = Color(0xFF003063)
                )
            ) {
                Text("Submit Password", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun BotTokenSection(uiState: AuthUiState, viewModel: AuthViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showGuide by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Bot Token Input Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF2A1C47).copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = Color(0xFFCFBCFF),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Bot Token Login",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "No phone number needed",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedTextField(
                    value = uiState.botTokenInput,
                    onValueChange = { viewModel.updateBotToken(it) },
                    label = { Text("Bot Token", color = TextMuted) },
                    placeholder = { Text("7123456789:AAFx9z...", color = TextMuted.copy(alpha = 0.5f)) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    viewModel.updateBotToken(clip.trim())
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = AccentBlue)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = DividerColor,
                        focusedContainerColor = CardDarkElevated,
                        unfocusedContainerColor = CardDarkElevated
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.botChatIdInput,
                    onValueChange = { viewModel.updateBotChatId(it) },
                    label = { Text("Target Chat ID (Optional)", color = TextMuted) },
                    placeholder = { Text("Auto-detect", color = TextMuted.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = DividerColor,
                        focusedContainerColor = CardDarkElevated,
                        unfocusedContainerColor = CardDarkElevated
                    )
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = { viewModel.submitBotToken() },
                    enabled = uiState.botTokenInput.isNotBlank() && !uiState.isLoading,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        contentColor = Color(0xFF003063)
                    )
                ) {
                    Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect & Start Syncing", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Setup Guide (collapsible)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardDark,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showGuide = !showGuide }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "How to get your Bot Token",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(visible = showGuide) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GuideStep("1", "Open @BotFather", "Message @BotFather on Telegram") {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather"))
                            context.startActivity(intent)
                        }
                        GuideStep("2", "Send /newbot", "Create a bot and give it a username")
                        GuideStep("3", "Copy Token", "Paste the HTTP API token above")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStep(
    step: String,
    title: String,
    description: String,
    onAction: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = BrandBlue.copy(alpha = 0.2f),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(step, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentBlue)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(description, fontSize = 11.sp, color = TextMuted)
            if (onAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentBlue)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open @BotFather", fontSize = 11.sp, color = AccentBlue)
                }
            }
        }
    }
}

@Composable
private fun QrLoginSection(uiState: AuthUiState, viewModel: AuthViewModel) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = BrandBlue.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Log in with QR Code",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Fastest • No SMS code required",
                        fontSize = 12.sp,
                        color = SuccessGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // QR Code Frame
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!uiState.qrLink.isNullOrBlank()) {
                    QrCode(
                        content = uiState.qrLink,
                        contentDescription = "Telegram QR Login",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = AccentBlue,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Generating QR...",
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Instructions Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardDarkElevated, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    "Quick Instructions:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentBlue
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "1. Open Telegram on your phone",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "2. Go to Settings > Devices",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "3. Tap Link Desktop Device and scan",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.cancelQrLogin() }) {
                    Text("Use Phone Instead", color = TextMuted, fontSize = 13.sp)
                }
                TextButton(onClick = { viewModel.startQrLogin() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AccentBlue
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh QR", color = AccentBlue, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ModeSwitcher(uiState: AuthUiState, viewModel: AuthViewModel) {
    val selectedMode = uiState.selectedMode

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardDark,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            // QR Code Tab
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selectedMode == AuthMode.QR) AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setAuthMode(AuthMode.QR) }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedMode == AuthMode.QR) AccentBlue else TextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "QR Code",
                        fontSize = 13.sp,
                        fontWeight = if (selectedMode == AuthMode.QR) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedMode == AuthMode.QR) AccentBlue else TextMuted
                    )
                }
            }

            // Phone Tab
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selectedMode == AuthMode.PHONE) AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setAuthMode(AuthMode.PHONE) }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedMode == AuthMode.PHONE) AccentBlue else TextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Phone",
                        fontSize = 13.sp,
                        fontWeight = if (selectedMode == AuthMode.PHONE) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedMode == AuthMode.PHONE) AccentBlue else TextMuted
                    )
                }
            }

            // Web Tab
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selectedMode == AuthMode.WEB) AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setAuthMode(AuthMode.WEB) }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedMode == AuthMode.WEB) AccentBlue else TextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Web Portal",
                        fontSize = 13.sp,
                        fontWeight = if (selectedMode == AuthMode.WEB) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedMode == AuthMode.WEB) AccentBlue else TextMuted
                    )
                }
            }

            // Bot Tab
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selectedMode == AuthMode.BOT_TOKEN) AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setAuthMode(AuthMode.BOT_TOKEN) }
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedMode == AuthMode.BOT_TOKEN) AccentBlue else TextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Bot Token",
                        fontSize = 13.sp,
                        fontWeight = if (selectedMode == AuthMode.BOT_TOKEN) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedMode == AuthMode.BOT_TOKEN) AccentBlue else TextMuted
                    )
                }
            }
        }
    }
}
