package com.example.mailclient

import android.os.Bundle
import android.content.Context
import android.content.Intent
import java.io.OutputStream
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}

data class ComposePrefill(
    val to: String = "",
    val subject: String = "",
    val body: String = ""
)

sealed class Screen {
    object CheckingSavedLogin : Screen()
    data class Login(val showBack: Boolean = false) : Screen()
    object Mailbox : Screen()
    data class MessageView(val header: ImapConnector.MailHeader) : Screen()
    object FolderList : Screen()
    data class Compose(val prefill: ComposePrefill = ComposePrefill()) : Screen()
    object AccountList : Screen()
    object Settings : Screen()
}

private val avatarPalette = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784),
    Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFF4DB6AC),
    Color(0xFFF06292), Color(0xFF9575CD)
)

private fun avatarColorFor(text: String): Color {
    val index = (text.hashCode().let { if (it < 0) -it else it }) % avatarPalette.size
    return avatarPalette[index]
}

private fun avatarLetterFor(text: String): String {
    val nameOnly = text.substringBefore("<").trim()
    val source = nameOnly.ifBlank { text }
    return source.firstOrNull()?.uppercase() ?: "?"
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val workRequest = PeriodicWorkRequestBuilder<MailCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mail_check_worker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
    var screen by remember { mutableStateOf<Screen>(Screen.CheckingSavedLogin) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(993) }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf(465) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var currentFolder by remember { mutableStateOf("INBOX") }
    var mailboxReloadKey by remember { mutableStateOf(0) }
    var savedLoginError by remember { mutableStateOf("") }
    var accountListError by remember { mutableStateOf("") }
    var isSwitchingAccount by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf(ThemeStore.getThemeMode(context)) }
    val scope = rememberCoroutineScope()

    val useDarkTheme = when (themeMode) {
        ThemeStore.ThemeMode.LIGHT -> false
        ThemeStore.ThemeMode.DARK -> true
        ThemeStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()

    fun applyAccount(preset: ImapConnector.ServerPreset, e: String, pass: String) {
        host = preset.host; port = preset.port
        smtpHost = preset.smtpHost; smtpPort = preset.smtpPort
        email = e; password = pass
        currentFolder = "INBOX"
        mailboxReloadKey++
    }

    LaunchedEffect(Unit) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
        } catch (_: Exception) {}

        val result = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
        result.onSuccess { info ->
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            }
        }
    }

    LaunchedEffect(Unit) {
        val active = CredentialStore.getActiveAccount(context)
        if (active == null) {
            screen = Screen.Login()
        } else {
            val preset = ImapConnector.presets.firstOrNull { it.label == active.presetLabel }
                ?: ImapConnector.presets[0]
            val result = ImapConnector.fetchMessages(
                host = preset.host, port = preset.port,
                email = active.email, password = active.password, offset = 0
            )
            result.fold(
                onSuccess = {
                    applyAccount(preset, active.email, active.password)
                    screen = Screen.Mailbox
                },
                onFailure = {
                    savedLoginError = "Не удалось войти в ${active.email}: ${it.message}"
                    screen = Screen.AccountList
                }
            )
        }
    }



    BackHandler(enabled = screen !is Screen.Mailbox && screen !is Screen.CheckingSavedLogin) {
        when (val current = screen) {
            is Screen.MessageView -> screen = Screen.Mailbox
            is Screen.FolderList -> screen = Screen.Mailbox
            is Screen.Compose -> screen = Screen.Mailbox
            is Screen.AccountList -> screen = Screen.Mailbox
            is Screen.Settings -> screen = Screen.Mailbox
            is Screen.Login -> if (current.showBack) screen = Screen.AccountList
            else -> {}
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when (val current = screen) {
                is Screen.CheckingSavedLogin -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Проверка сохранённого входа...")
                    }
                }
                is Screen.Login -> LoginScreen(
                    initialError = savedLoginError,
                    showBack = current.showBack,
                    onBack = { screen = Screen.AccountList },
                    onLoginSuccess = { preset, e, pass ->
                        CredentialStore.addOrUpdateAccount(context, preset.label, e, pass)
                        applyAccount(preset, e, pass)
                        screen = Screen.Mailbox
                    }
                )
                is Screen.Mailbox -> MailboxScreen(
                    host = host, port = port, email = email, password = password,
                    folderName = currentFolder,
                    reloadKey = mailboxReloadKey,
                    themeMode = themeMode,
                    onToggleTheme = {
                        themeMode = ThemeStore.nextMode(themeMode)
                        ThemeStore.setThemeMode(context, themeMode)
                    },
                    onOpenAccounts = { accountListError = ""; screen = Screen.AccountList },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenMessage = { header -> screen = Screen.MessageView(header) },
                    onOpenFolders = { screen = Screen.FolderList },
                    onCompose = { screen = Screen.Compose() }
                )
                is Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Mailbox }
                )
                is Screen.MessageView -> MessageScreen(
                    host = host, port = port, email = email, password = password,
                    header = current.header,
                    folderName = currentFolder,
                    onBack = { screen = Screen.Mailbox },
                    onActionDone = {
                        mailboxReloadKey++
                        screen = Screen.Mailbox
                    },
                    onReplyOrForward = { prefill -> screen = Screen.Compose(prefill) }
                )
                is Screen.FolderList -> FolderListScreen(
                    host = host, port = port, email = email, password = password,
                    onSelectFolder = { folder ->
                        currentFolder = folder
                        mailboxReloadKey++
                        screen = Screen.Mailbox
                    },
                    onBack = { screen = Screen.Mailbox }
                )
                is Screen.Compose -> ComposeScreen(
                    smtpHost = smtpHost, smtpPort = smtpPort,
                    fromEmail = email, password = password,
                    prefill = current.prefill,
                    onBack = { screen = Screen.Mailbox },
                    onSent = { screen = Screen.Mailbox }
                )
                is Screen.AccountList -> AccountListScreen(
                    accounts = CredentialStore.loadAccounts(context),
                    activeEmail = email,
                    errorText = accountListError,
                    isBusy = isSwitchingAccount,
                    onSelectAccount = { account ->
                        if (!account.email.equals(email, ignoreCase = true)) {
                            isSwitchingAccount = true
                            accountListError = ""
                            scope.launch {
                                val preset = ImapConnector.presets.firstOrNull { it.label == account.presetLabel }
                                    ?: ImapConnector.presets[0]
                                val result = ImapConnector.fetchMessages(
                                    host = preset.host, port = preset.port,
                                    email = account.email, password = account.password, offset = 0
                                )
                                isSwitchingAccount = false
                                result.fold(
                                    onSuccess = {
                                        CredentialStore.setActiveEmail(context, account.email)
                                        applyAccount(preset, account.email, account.password)
                                        screen = Screen.Mailbox
                                    },
                                    onFailure = {
                                        accountListError = "Не удалось войти в ${account.email}: ${it.message}"
                                    }
                                )
                            }
                        } else {
                            screen = Screen.Mailbox
                        }
                    },
                    onAddAccount = { screen = Screen.Login(showBack = true) },
                    onRemoveAccount = { account ->
                        CredentialStore.removeAccount(context, account.email)
                        if (account.email.equals(email, ignoreCase = true)) {
                            val next = CredentialStore.getActiveAccount(context)
                            if (next == null) {
                                email = ""
                                screen = Screen.Login()
                            } else {
                                isSwitchingAccount = true
                                scope.launch {
                                    val preset = ImapConnector.presets.firstOrNull { it.label == next.presetLabel }
                                        ?: ImapConnector.presets[0]
                                    val result = ImapConnector.fetchMessages(
                                        host = preset.host, port = preset.port,
                                        email = next.email, password = next.password, offset = 0
                                    )
                                    isSwitchingAccount = false
                                    result.fold(
                                        onSuccess = { applyAccount(preset, next.email, next.password) },
                                        onFailure = { accountListError = "Не удалось войти в ${next.email}: ${it.message}" }
                                    )
                                }
                            }
                        }
                    },
                    onBack = { screen = Screen.Mailbox }
                )
            }

            if (showUpdateDialog && updateInfo != null) {
                val info = updateInfo!!
                AlertDialog(
                    onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
                    title = { Text("Доступно обновление ${info.version}") },
                    text = {
                        Column {
                            if (info.notes.isNotBlank()) {
                                Text(info.notes)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (isDownloading) {
                                Text("Скачивание запущено, следите за уведомлением...")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                isDownloading = true
                                UpdateChecker.downloadAndInstall(context, info.downloadUrl) {}
                            },
                            enabled = !isDownloading
                        ) { Text("Скачать и установить") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpdateDialog = false }, enabled = !isDownloading) {
                            Text("Позже")
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    initialError: String = "",
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onLoginSuccess: (ImapConnector.ServerPreset, String, String) -> Unit
) {
    var selectedPreset by remember { mutableStateOf(ImapConnector.presets[0]) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf(initialError) }
    var isLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
            Text("Вход в почту", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedPreset.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Провайдер") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ImapConnector.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            selectedPreset = preset
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль (для Yandex/Mail.ru/Yahoo — пароль приложения)") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                isLoading = true
                errorText = ""
                scope.launch {
                    val result = ImapConnector.fetchMessages(
                        host = selectedPreset.host,
                        port = selectedPreset.port,
                        email = email,
                        password = password,
                        offset = 0
                    )
                    isLoading = false
                    result.fold(
                        onSuccess = {
                            onLoginSuccess(selectedPreset, email, password)
                        },
                        onFailure = { error ->
                            errorText = "Ошибка: ${error.message}"
                        }
                    )
                }
            },
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isLoading) "Подключение..." else "Войти")
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun AccountListScreen(
    accounts: List<CredentialStore.SavedAccount>,
    activeEmail: String,
    errorText: String,
    isBusy: Boolean,
    onSelectAccount: (CredentialStore.SavedAccount) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (CredentialStore.SavedAccount) -> Unit,
    onBack: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<CredentialStore.SavedAccount?>(null) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Аккаунты", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (isBusy) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(accounts) { account ->
                val isActive = account.email.equals(activeEmail, ignoreCase = true)
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isBusy) { onSelectAccount(account) },
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isActive)
                        CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else CardDefaults.elevatedCardColors()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(avatarColorFor(account.email)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(avatarLetterFor(account.email), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                account.email,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                if (isActive) "${account.presetLabel} · активен" else account.presetLabel,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { pendingDelete = account }, enabled = !isBusy) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить аккаунт")
                        }
                    }
                }
            }
        }

        Button(
            onClick = onAddAccount,
            enabled = !isBusy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Добавить аккаунт")
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить аккаунт?") },
            text = { Text("${toDelete.email} будет забыт (пароль удалён из приложения).") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveAccount(toDelete)
                    pendingDelete = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MailboxScreen(
    host: String,
    port: Int,
    email: String,
    password: String,
    folderName: String,
    reloadKey: Int,
    themeMode: ThemeStore.ThemeMode,
    onToggleTheme: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMessage: (ImapConnector.MailHeader) -> Unit,
    onOpenFolders: () -> Unit,
    onCompose: () -> Unit
) {
    val context = LocalContext.current
    var offset by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var messages by remember { mutableStateOf(listOf<ImapConnector.MailHeader>()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var selectedMailForMenu by remember { mutableStateOf<ImapConnector.MailHeader?>(null) }
    val scope = rememberCoroutineScope()
    val pageSize = 20

    val leftGesture = remember { ThemeStore.getLeftGesture(context) }
    val rightGesture = remember { ThemeStore.getRightGesture(context) }

    fun loadPage(newOffset: Int) {
        isLoading = true
        errorText = ""
        scope.launch {
            val result = ImapConnector.fetchMessages(
                host = host, port = port, email = email, password = password,
                offset = newOffset, limit = pageSize, folderName = folderName
            )
            isLoading = false
            result.fold(
                onSuccess = { page ->
                    messages = page.messages
                    totalCount = page.totalCount
                    offset = newOffset
                },
                onFailure = { error -> errorText = "Ошибка: ${error.message}" }
            )
        }
    }

    LaunchedEffect(folderName, reloadKey) {
        loadPage(0)
    }

    fun deleteOrTrash(msgNum: Int) {
        scope.launch {
            isLoading = true
            if (ImapConnector.isTrashFolder(folderName) || ImapConnector.isSpamFolder(folderName)) {
                ImapConnector.deleteMessage(host, port, email, password, msgNum, folderName)
            } else {
                ImapConnector.moveToTrash(host, port, email, password, msgNum, folderName)
            }
            loadPage(offset)
        }
    }

    fun executeGestureAction(action: ThemeStore.GestureAction, mail: ImapConnector.MailHeader) {
        when (action) {
            ThemeStore.GestureAction.DELETE -> {
                deleteOrTrash(mail.msgNum)
            }
            ThemeStore.GestureAction.MARK_READ -> {
                scope.launch {
                    ImapConnector.markAsRead(host, port, email, password, mail.msgNum, folderName)
                    loadPage(offset)
                }
            }
            ThemeStore.GestureAction.SPAM -> {
                scope.launch {
                    isLoading = true
                    ImapConnector.moveToSpam(host, port, email, password, mail.msgNum, folderName)
                    loadPage(offset)
                }
            }
            ThemeStore.GestureAction.NONE -> {}
        }
    }

    val themeIcon = when (themeMode) {
        ThemeStore.ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
        ThemeStore.ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeStore.ThemeMode.DARK -> Icons.Filled.DarkMode
    }
    val themeDescription = when (themeMode) {
        ThemeStore.ThemeMode.SYSTEM -> "Тема: системная"
        ThemeStore.ThemeMode.LIGHT -> "Тема: светлая"
        ThemeStore.ThemeMode.DARK -> "Тема: тёмная"
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            email,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        TextButton(onClick = onOpenFolders, contentPadding = PaddingValues(start = 4.dp)) {
                            Text("· ${ImapConnector.displayNameFor(folderName)} ▾", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(themeIcon, contentDescription = themeDescription)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                    IconButton(onClick = onOpenAccounts) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Аккаунты")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
            if (errorText.isNotEmpty()) {
                Text(errorText, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }

            PullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = { loadPage(0) },
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.msgNum }) { mail ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                when (dismissValue) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        executeGestureAction(rightGesture, mail)
                                        false
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        executeGestureAction(leftGesture, mail)
                                        false
                                    }
                                    SwipeToDismissBoxValue.Settled -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val direction = dismissState.dismissDirection
                                val color = when (direction) {
                                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                    else -> Color.Transparent
                                }
                                val alignment = when (direction) {
                                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                    else -> Alignment.Center
                                }
                                val actionText = when (direction) {
                                    SwipeToDismissBoxValue.StartToEnd -> rightGesture.title
                                    SwipeToDismissBoxValue.EndToStart -> leftGesture.title
                                    else -> ""
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(color, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = alignment
                                ) {
                                    if (actionText.isNotEmpty() && actionText != "Ничего") {
                                        Text(actionText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        ) {
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onOpenMessage(mail) },
                                        onLongClick = { selectedMailForMenu = mail }
                                    ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(avatarColorFor(mail.from)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(avatarLetterFor(mail.from), color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mail.from, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(mail.subject, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            mail.date,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val currentEnd = offset + messages.size
            Text(
                "Показаны ${if (messages.isEmpty()) 0 else offset + 1}–$currentEnd из $totalCount",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { loadPage(maxOf(0, offset - pageSize)) },
                    enabled = !isLoading && offset > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text("Назад")
                }
                FilledIconButton(onClick = onCompose, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Написать письмо")
                }
                OutlinedButton(
                    onClick = { loadPage(offset + pageSize) },
                    enabled = !isLoading && currentEnd < totalCount,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Вперёд")
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }

    val mailForMenu = selectedMailForMenu
    if (mailForMenu != null) {
        AlertDialog(
            onDismissRequest = { selectedMailForMenu = null },
            title = { Text("Действие с письмом") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            selectedMailForMenu = null
                            scope.launch {
                                ImapConnector.markAsRead(host, port, email, password, mailForMenu.msgNum, folderName)
                                loadPage(offset)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Пометить прочитанным")
                    }
                    TextButton(
                        onClick = {
                            selectedMailForMenu = null
                            scope.launch {
                                isLoading = true
                                ImapConnector.moveToSpam(host, port, email, password, mailForMenu.msgNum, folderName)
                                loadPage(offset)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("В спам")
                    }
                    TextButton(
                        onClick = {
                            selectedMailForMenu = null
                            deleteOrTrash(mailForMenu.msgNum)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMailForMenu = null }) { Text("Отмена") }
            }
        )
    }
}

private enum class PendingAction { NONE, DELETE, SPAM, MOVE, RESTORE }

@Composable
private fun CompactActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
@Composable
fun MessageScreen(
    host: String,
    port: Int,
    email: String,
    password: String,
    header: ImapConnector.MailHeader,
    folderName: String,
    onBack: () -> Unit,
    onActionDone: () -> Unit,
    onReplyOrForward: (ComposePrefill) -> Unit
) {
    var body by remember { mutableStateOf(ImapConnector.MailBody("", "")) }
    var showPlainText by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf("") }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var folderOptions by remember { mutableStateOf(listOf<String>()) }
    var pendingAction by remember { mutableStateOf(PendingAction.NONE) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isBusy = pendingAction != PendingAction.NONE

    LaunchedEffect(header.msgNum) {
        val result = ImapConnector.fetchMessageBody(
            host = host, port = port, email = email, password = password,
            msgNum = header.msgNum, folderName = folderName
        )
        isLoading = false
        result.fold(
            onSuccess = { body = it },
            onFailure = { errorText = "Ошибка: ${it.message}" }
        )
    }

    fun quotedOriginal(): String {
        return "\n\n\n---------- Исходное письмо ----------\n" +
                "От: ${header.from}\n" +
                "Дата: ${header.date}\n" +
                "Тема: ${header.subject}\n\n" +
                body.plainText
    }

    fun runDelete() {
        pendingAction = PendingAction.DELETE
        scope.launch {
            val result = if (ImapConnector.isTrashFolder(folderName)) {
                ImapConnector.deleteMessage(host, port, email, password, header.msgNum, folderName)
            } else {
                ImapConnector.moveToTrash(host, port, email, password, header.msgNum, folderName)
            }
            pendingAction = PendingAction.NONE
            result.fold(
                onSuccess = { onActionDone() },
                onFailure = { errorText = "Ошибка удаления: ${it.message}" }
            )
        }
    }

    fun runRestore() {
        pendingAction = PendingAction.RESTORE
        scope.launch {
            val result = ImapConnector.restoreFromTrash(host, port, email, password, header.msgNum, folderName)
            pendingAction = PendingAction.NONE
            result.fold(
                onSuccess = { onActionDone() },
                onFailure = { errorText = "Ошибка восстановления: ${it.message}" }
            )
        }
    }

    fun runSpam() {
        pendingAction = PendingAction.SPAM
        scope.launch {
            val result = ImapConnector.moveToSpam(host, port, email, password, header.msgNum, folderName)
            pendingAction = PendingAction.NONE
            result.fold(
                onSuccess = { onActionDone() },
                onFailure = { errorText = "Ошибка: ${it.message}" }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Письмо", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactActionButton(
                text = "Ответить",
                enabled = !isBusy && !isLoading,
                onClick = {
                    onReplyOrForward(
                        ComposePrefill(
                            to = ImapConnector.extractEmailAddress(header.from),
                            subject = if (header.subject.startsWith("Re:", ignoreCase = true))
                                header.subject else "Re: ${header.subject}",
                            body = quotedOriginal()
                        )
                    )
                }
            )

            CompactActionButton(
                text = "Переслать",
                enabled = !isBusy && !isLoading,
                onClick = {
                    onReplyOrForward(
                        ComposePrefill(
                            to = "",
                            subject = if (header.subject.startsWith("Fwd:", ignoreCase = true))
                                header.subject else "Fwd: ${header.subject}",
                            body = quotedOriginal()
                        )
                    )
                }
            )

            if (ImapConnector.isTrashFolder(folderName)) {
                CompactActionButton(
                    text = "Удалить насовсем",
                    enabled = !isBusy,
                    isDestructive = true,
                    onClick = { showDeleteConfirm = true }
                )
                CompactActionButton(
                    text = if (pendingAction == PendingAction.RESTORE) "..." else "Восстановить",
                    enabled = !isBusy,
                    onClick = { runRestore() }
                )
            } else {
                CompactActionButton(
                    text = "Переместить",
                    enabled = !isBusy,
                    onClick = {
                        showMoveDialog = true
                        pendingAction = PendingAction.MOVE
                        scope.launch {
                            val result = ImapConnector.listFolders(host, port, email, password)
                            pendingAction = PendingAction.NONE
                            result.fold(
                                onSuccess = { folders -> folderOptions = folders.filter { it != folderName } },
                                onFailure = { errorText = "Ошибка получения папок: ${it.message}" }
                            )
                        }
                    }
                )

                CompactActionButton(
                    text = "Удалить",
                    enabled = !isBusy,
                    isDestructive = true,
                    onClick = { showDeleteConfirm = true }
                )

                if (!ImapConnector.isSpamFolder(folderName)) {
                    CompactActionButton(
                        text = if (pendingAction == PendingAction.SPAM) "..." else "СПАМ",
                        enabled = !isBusy,
                        onClick = { runSpam() }
                    )
                }
            }

            CompactActionButton(
                text = if (showPlainText) "Как HTML" else "Как текст",
                enabled = !isLoading,
                onClick = { showPlainText = !showPlainText }
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(header.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text("От: ${header.from} · ${header.date}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }

        if (body.attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Вложения (${body.attachments.size}):", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                body.attachments.forEach { att ->
                    var isDownloading by remember { mutableStateOf(false) }
                    ElevatedButton(
                        onClick = {
                            isDownloading = true
                            scope.launch {
                                val result = ImapConnector.downloadAttachment(host, port, email, password, header.msgNum, folderName, att.index)
                                isDownloading = false
                                result.fold(
                                    onSuccess = { (fileName, bytes) ->
                                        val saved = saveAttachmentToDownloads(context, fileName, bytes)
                                        if (saved) {
                                            Toast.makeText(context, "Сохранено в Загрузки: $fileName", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Ошибка сохранения файла", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onFailure = { err ->
                                        Toast.makeText(context, "Ошибка скачивания: ${err.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        },
                        enabled = !isDownloading,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isDownloading) "Скачивание..." else att.fileName, maxLines = 1)
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        if (isLoading || pendingAction != PendingAction.NONE) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

        if (showPlainText) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(body.plainText)
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.loadsImagesAutomatically = true
                        settings.domStorageEnabled = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(null, body.displayHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }

    if (showDeleteConfirm) {
        val isPermanent = ImapConnector.isTrashFolder(folderName)
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (isPermanent) "Удалить письмо навсегда?" else "Переместить в корзину?") },
            text = {
                Text(if (isPermanent) "Это действие нельзя отменить." else "Письмо можно будет восстановить из корзины.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    runDelete()
                }) { Text(if (isPermanent) "Удалить навсегда" else "Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showMoveDialog = false },
            title = { Text("Переместить в папку") },
            text = {
                if (folderOptions.isEmpty()) {
                    CircularProgressIndicator()
                } else {
                    Column {
                        folderOptions.forEach { folder ->
                            TextButton(
                                onClick = {
                                    pendingAction = PendingAction.MOVE
                                    scope.launch {
                                        val result = ImapConnector.moveMessage(
                                            host = host, port = port, email = email, password = password,
                                            msgNum = header.msgNum, fromFolder = folderName, toFolder = folder
                                        )
                                        pendingAction = PendingAction.NONE
                                        showMoveDialog = false
                                        result.fold(
                                            onSuccess = { onActionDone() },
                                            onFailure = { errorText = "Ошибка перемещения: ${it.message}" }
                                        )
                                    }
                                },
                                enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(ImapConnector.displayNameFor(folder))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoveDialog = false }, enabled = !isBusy) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun FolderListScreen(
    host: String,
    port: Int,
    email: String,
    password: String,
    onSelectFolder: (String) -> Unit,
    onBack: () -> Unit
) {
    var folders by remember { mutableStateOf(listOf<String>()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val result = ImapConnector.listFolders(host, port, email, password)
        isLoading = false
        result.fold(
            onSuccess = { folders = it },
            onFailure = { errorText = "Ошибка: ${it.message}" }
        )
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Папки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

        val rootFolders = mutableListOf<String>()
        val inboxSubfolders = mutableListOf<String>()

        folders.forEach { folder ->
            val upper = folder.uppercase()
            if (upper.startsWith("INBOX/") || upper.startsWith("INBOX.")) {
                inboxSubfolders.add(folder)
            } else {
                rootFolders.add(folder)
            }
        }

        inboxSubfolders.sortWith(Comparator { a, b ->
            ImapConnector.displayNameFor(a).compareTo(ImapConnector.displayNameFor(b), ignoreCase = true)
        })

        rootFolders.sortWith(Comparator { a, b ->
            val aIsTrash = ImapConnector.isTrashFolder(a) || ImapConnector.isTrashFolder(ImapConnector.displayNameFor(a))
            val bIsTrash = ImapConnector.isTrashFolder(b) || ImapConnector.isTrashFolder(ImapConnector.displayNameFor(b))
            val aIsSpam = ImapConnector.isSpamFolder(a) || ImapConnector.isSpamFolder(ImapConnector.displayNameFor(a))
            val bIsSpam = ImapConnector.isSpamFolder(b) || ImapConnector.isSpamFolder(ImapConnector.displayNameFor(b))

            val aRank = when {
                aIsTrash -> 3
                aIsSpam -> 2
                else -> 1
            }
            val bRank = when {
                bIsTrash -> 3
                bIsSpam -> 2
                else -> 1
            }

            if (aRank != bRank) {
                aRank.compareTo(bRank)
            } else {
                ImapConnector.displayNameFor(a).compareTo(ImapConnector.displayNameFor(b), ignoreCase = true)
            }
        })

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rootFolders.forEach { folder ->
                item(key = folder) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectFolder(folder) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            ImapConnector.displayNameFor(folder),
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (folder.equals("INBOX", ignoreCase = true)) {
                    items(inboxSubfolders, key = { it }) { sub ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp)
                                .clickable { onSelectFolder(sub) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                ImapConnector.displayNameFor(sub),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ComposeScreen(
    smtpHost: String,
    smtpPort: Int,
    fromEmail: String,
    password: String,
    prefill: ComposePrefill,
    onBack: () -> Unit,
    onSent: () -> Unit
) {
    var to by remember { mutableStateOf(prefill.to) }
    var subject by remember { mutableStateOf(prefill.subject) }
    var body by remember { mutableStateOf(prefill.body) }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Новое письмо", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = to,
            onValueChange = { to = it },
            label = { Text("Кому") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = subject,
            onValueChange = { subject = it },
            label = { Text("Тема") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Текст письма") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                isSending = true
                errorText = ""
                scope.launch {
                    val result = SmtpConnector.sendMail(
                        smtpHost = smtpHost, smtpPort = smtpPort,
                        fromEmail = fromEmail, password = password,
                        toEmail = to, subject = subject, body = body
                    )
                    isSending = false
                    result.fold(
                        onSuccess = { onSent() },
                        onFailure = { errorText = "Ошибка отправки: ${it.message}" }
                    )
                }
            },
            enabled = !isSending && to.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp)
        ) {
            Text(if (isSending) "Отправка..." else "Отправить")
        }
    }
}

private fun saveAttachmentToDownloads(context: Context, fileName: String, bytes: ByteArray): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, getMimeTypeForFile(fileName))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(bytes)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                true
            } else false
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)
            file.writeBytes(bytes)
            true
        }
    } catch (e: Exception) {
        false
    }
}

private fun getMimeTypeForFile(fileName: String): String {
    val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
    if (extension != null) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        if (mime != null) return mime
    }
    return "application/octet-stream"
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var leftGesture by remember { mutableStateOf(ThemeStore.getLeftGesture(context)) }
    var rightGesture by remember { mutableStateOf(ThemeStore.getRightGesture(context)) }
    var showLeftDialog by remember { mutableStateOf(false) }
    var showRightDialog by remember { mutableStateOf(false) }

    val soundUriStr = remember { mutableStateOf(ThemeStore.getNotificationSoundUri(context)) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            ThemeStore.setNotificationSoundUri(context, uri.toString())
            soundUriStr.value = uri.toString()
            NotificationHelper.ensureChannel(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text("Жесты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showLeftDialog = true }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Жест влево")
                    Text(leftGesture.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showRightDialog = true }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Жест вправо")
                    Text(rightGesture.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text("Звук уведомления", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (soundUriStr.value.isBlank()) "Звук по умолчанию" else "Выбран кастомный аудиофайл",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Выбрать файл")
                    }
                    if (soundUriStr.value.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                ThemeStore.setNotificationSoundUri(context, "")
                                soundUriStr.value = ""
                                NotificationHelper.ensureChannel(context)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Сбросить")
                        }
                    }
                }
            }
        }
    }

    if (showLeftDialog) {
        AlertDialog(
            onDismissRequest = { showLeftDialog = false },
            title = { Text("Жест влево") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeStore.GestureAction.values().forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    leftGesture = action
                                    ThemeStore.setLeftGesture(context, action)
                                    showLeftDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = leftGesture == action, onClick = {
                                leftGesture = action
                                ThemeStore.setLeftGesture(context, action)
                                showLeftDialog = false
                            })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(action.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLeftDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (showRightDialog) {
        AlertDialog(
            onDismissRequest = { showRightDialog = false },
            title = { Text("Жест вправо") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeStore.GestureAction.values().forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    rightGesture = action
                                    ThemeStore.setRightGesture(context, action)
                                    showRightDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = rightGesture == action, onClick = {
                                rightGesture = action
                                ThemeStore.setRightGesture(context, action)
                                showRightDialog = false
                            })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(action.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRightDialog = false }) { Text("Закрыть") }
            }
        )
    }
}