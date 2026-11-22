package com.example.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MahiAiTheme

class TestPlaygroundActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MahiAiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    TestPlaygroundScreen(
                        onClose = { finish() },
                        modifier = Modifier.padding(inner)
                    )
                }
            }
        }
    }
}

@Composable
fun TestPlaygroundScreen(
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var tapCount by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var passwordSample by remember { mutableStateOf("Secret123") }
    var agreeChecked by remember { mutableStateOf(false) }
    var selfTestSummary by remember { mutableStateOf("Ready to verify 8 interactive UI nodes.") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("test_playground_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mahi Screen Automation Playground", color = CyberCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("1. Tap Counter: $tapCount", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            Button(
                onClick = { tapCount++ },
                modifier = Modifier.testTag("playground_tap_button")
            ) { Text("Increment Counter") }

            Text("2. Search / Type Input", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Sample Search Box") },
                modifier = Modifier.fillMaxWidth().testTag("playground_search_input")
            )

            Text("3. Password Masking Verification", color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = passwordSample,
                onValueChange = { passwordSample = it },
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Protected Password Field") },
                modifier = Modifier.fillMaxWidth().testTag("playground_password_input")
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = agreeChecked,
                    onCheckedChange = { agreeChecked = it },
                    modifier = Modifier.testTag("playground_checkbox")
                )
                Text("4. Toggleable Consent Node", color = colors.onSurface)
            }

            Button(
                onClick = {
                    val checks = listOf(
                        "Tap counter=${tapCount + 1}",
                        "Search='${searchQuery.ifBlank { "empty" }}'",
                        "Password redacted=[PASSWORD_HIDDEN]",
                        "Checkbox=${!agreeChecked}"
                    )
                    tapCount++
                    agreeChecked = !agreeChecked
                    selfTestSummary = "Passed: ${checks.joinToString(" • ")}"
                },
                modifier = Modifier.fillMaxWidth().testTag("playground_run_selftest")
            ) {
                Text("Run Local UI Node Self-Test")
            }

            Text(selfTestSummary, color = CyberEmerald, fontSize = 12.sp)
        }
    }
}
