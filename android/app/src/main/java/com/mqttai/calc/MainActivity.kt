package com.mqttai.calc

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: CalcViewModel = viewModel()

                // Launch Wi-Fi settings panel when needed (API 29+)
                LaunchedEffect(vm.showWifiPanel.value) {
                    if (vm.showWifiPanel.value) {
                        vm.showWifiPanel.value = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startActivity(Intent(Settings.Panel.ACTION_WIFI))
                        }
                    }
                }

                CalcScreen(vm)
            }
        }
    }
}

@Composable
fun CalcScreen(vm: CalcViewModel = viewModel()) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(vm.chatMessages.size) {
        if (vm.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(vm.chatMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // Calculator display
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f)
                .padding(24.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Text(
                text = vm.displayValue.value,
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                textAlign = TextAlign.End,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Divider(color = Color(0xFF333333))

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(vm.chatMessages) { msg ->
                ChatBubble(msg)
            }
            if (vm.isPending.value) {
                item {
                    Text(
                        "Thinking...",
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = vm.inputText.value,
                onValueChange = { vm.inputText.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask a calculation...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00BCD4),
                    cursorColor = Color(0xFF00BCD4),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.send() },
                enabled = vm.inputText.value.isNotBlank() && !vm.isPending.value,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val bgColor = if (msg.isUser) Color(0xFF00838F) else Color(0xFF1E1E1E)
    val alignment = if (msg.isUser) Arrangement.End else Arrangement.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
    ) {
        // Source tag for AI responses
        if (!msg.isUser) {
            val (label, tagColor) = when (msg.source) {
                MessageSource.LOCAL -> "LOCAL" to Color(0xFF4CAF50)
                MessageSource.CLOUD -> "CLOUD" to Color(0xFF2196F3)
                else -> "" to Color.Transparent
            }
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    color = tagColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = alignment
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = bgColor,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = msg.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
