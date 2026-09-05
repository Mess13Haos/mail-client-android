package com.example.mailclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

sealed class Screen {
    object Login : Screen()
    object Mailbox : Screen()
    data class MessageView(val header: ImapConnector.MailHeader) : Screen()
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Login) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(993) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    when (val current = screen) {
        is Screen.Login -> LoginScreen(
            onLoginSuccess = { h, p, e, pass ->
                host = h; port = p; email = e; password = pass
                screen = Screen.Mailbox
            }
        )
        is Screen.Mailbox -> MailboxScreen(
            host = host, port = port, email = email, password = password,
            onLogout = { screen = Screen.Login },
            onOpenMessage = { header -> screen = Screen.MessageView(header) }
        )
        is Screen.MessageView -> MessageScreen(
            host = host, port = port, email = email, password = password,
            header = current.header,
            onBack = { screen = Screen.Mailbox }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: (String, Int, String, String) -> Unit) {
    var selectedPreset by remember { mutableStateOf(ImapConnector.presets[0]) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
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
        Text("Вход в почту", style = MaterialTheme.typography.titleLarge)

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedPreset.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Провайдер") },
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
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль (для Yandex/Mail.ru/Yahoo — пароль приложения)") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
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
                            onLoginSuccess(selectedPreset.host, selectedPreset.port, email, password)
                        },
                        onFailure = { error ->
                            errorText = "Ошибка: ${error.message}"
                        }
                    )
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Подключение..." else "Войти")
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun MailboxScreen(
    host: String,
    port: Int,
    email: String,
    password: String,
    onLogout: () -> Unit,
    onOpenMessage: (ImapConnector.MailHeader) -> Unit
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
                offset = newOffset, limit = pageSize
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

    LaunchedEffect(Unit) {
        loadPage(0)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Входящие", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onLogout) { Text("Выйти") }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { mail ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMessage(mail) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(mail.from, fontWeight = FontWeight.Bold)
                    Text(mail.subject)
                    Text(mail.date, style = MaterialTheme.typography.bodySmall)
                }
                Divider()
            }
        }

        val currentEnd = offset + messages.size
        Text(
            "Показаны ${if (messages.isEmpty()) 0 else offset + 1}–$currentEnd из $totalCount",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { loadPage(maxOf(0, offset - pageSize)) },
                enabled = !isLoading && offset > 0
            ) {
                Text("← Назад")
            }
            Button(
                onClick = { loadPage(offset + pageSize) },
                enabled = !isLoading && currentEnd < totalCount
            ) {
                Text("Вперёд →")
            }
        }
    }
}

@Composable
fun MessageScreen(
    host: String,
    port: Int,
    email: String,
    password: String,
    header: ImapConnector.MailHeader,
    onBack: () -> Unit
) {
    var body by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf("") }

    LaunchedEffect(header.msgNum) {
        val result = ImapConnector.fetchMessageBody(
            host = host, port = port, email = email, password = password,
            msgNum = header.msgNum
        )
        isLoading = false
        result.fold(
            onSuccess = { body = it },
            onFailure = { errorText = "Ошибка: ${it.message}" }
        )
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
            Text("Письмо", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(header.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("От: ${header.from}", style = MaterialTheme.typography.bodySmall)
        Text(header.date, style = MaterialTheme.typography.bodySmall)

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (errorText.isNotEmpty()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(body)
        }
    }
}