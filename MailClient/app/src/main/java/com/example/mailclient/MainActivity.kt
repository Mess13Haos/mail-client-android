package com.example.mailclient

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onOpenMessage = { header -> screen = Screen.MessageView(header) },
                    onOpenFolders = { screen = Screen.FolderList },
                    onCompose = { screen = Screen.Compose() }
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onOpenMessage: (ImapConnector.MailHeader) -> Unit,
    onOpenFolders: () -> Unit,
    onCompose: () -> Unit
) {
    var offset by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var messages by remember { mutableStateOf(listOf<ImapConnector.MailHeader>()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pageSize = 20

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(email, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onOpenFolders, contentPadding = PaddingValues(0.dp)) {
                            Text("${ImapConnector.displayNameFor(folderName)} ▾", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(themeIcon, contentDescription = themeDescription)
                    }
                    IconButton(onClick = onOpenAccounts) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Аккаунты")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) {
                Icon(Icons.Filled.Add, contentDescription = "Написать письмо")
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            if (errorText.isNotEmpty()) {
                Text(errorText, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { mail ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMessage(mail) },
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

            val currentEnd = offset + messages.size
            Text(
                "Показаны ${if (messages.isEmpty()) 0 else offset + 1}–$currentEnd из $totalCount",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 72.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { loadPage(maxOf(0, offset - pageSize)) },
                    enabled = !isLoading && offset > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text("Назад")
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
}

private enum class PendingAction { NONE, DELETE, SPAM, MOVE, RESTORE }

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
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf("") }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var folderOptions by remember { mutableStateOf(listOf<String>()) }
    var pendingAction by remember { mutableStateOf(PendingAction.NONE) }
    val scope = rememberCoroutineScope()
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
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Письмо", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(
                onClick = {
                    onReplyOrForward(
                        ComposePrefill(
                            to = ImapConnector.extractEmailAddress(header.from),
                            subject = if (header.subject.startsWith("Re:", ignoreCase = true))
                                header.subject else "Re: ${header.subject}",
                            body = quotedOriginal()
                        )
                    )
                },
                enabled = !isBusy && !isLoading
            ) { Text("Ответить") }

            TextButton(
                onClick = {
                    onReplyOrForward(
                        ComposePrefill(
                            to = "",
                            subject = if (header.subject.startsWith("Fwd:", ignoreCase = true))
                                header.subject else "Fwd: ${header.subject}",
                            body = quotedOriginal()
                        )
                    )
                },
                enabled = !isBusy && !isLoading
            ) { Text("Переслать") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (ImapConnector.isTrashFolder(folderName)) {
                TextButton(onClick = { showDeleteConfirm = true }, enabled = !isBusy) {
                    Text("Удалить насовсем", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { runRestore() }, enabled = !isBusy) {
                    Text(if (pendingAction == PendingAction.RESTORE) "..." else "Восстановить")
                }
            } else {
                TextButton(
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
                    },
                    enabled = !isBusy
                ) { Text("Переместить") }

                TextButton(onClick = { showDeleteConfirm = true }, enabled = !isBusy) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }

                if (!ImapConnector.isSpamFolder(folderName)) {
                    TextButton(onClick = { runSpam() }, enabled = !isBusy) {
                        Text(if (pendingAction == PendingAction.SPAM) "..." else "СПАМ")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(header.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("От: ${header.from}", style = MaterialTheme.typography.bodySmall)
        Text(header.date, style = MaterialTheme.typography.bodySmall)

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        if (isLoading || pendingAction != PendingAction.NONE) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(folders) { folder ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectFolder(folder) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        ImapConnector.displayNameFor(folder),
                        modifier = Modifier.fillMaxWidth().padding(14.dp)
                    )
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