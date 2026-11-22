package com.example

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.MahiAiTheme

class WakePopupActivity : ComponentActivity() {
    companion object {
        const val EXTRA_WAKE_TRANSCRIPT = "extra_wake_transcript"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val transcript = intent?.getStringExtra(EXTRA_WAKE_TRANSCRIPT).orEmpty().ifBlank { "Hey Mahi" }

        setContent {
            MahiAiTheme {
                val colors = MaterialTheme.colorScheme
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surface)
                                .border(1.dp, CyberCyan.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("MAHI WAKE TRIGGERED", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            Text("Heard: \"$transcript\"", color = colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        startActivity(
                                            Intent(this@WakePopupActivity, MainActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                            }
                                        )
                                        finish()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Talk to Mahi") }
                                OutlinedButton(
                                    onClick = { finish() },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }
        }
    }
}
