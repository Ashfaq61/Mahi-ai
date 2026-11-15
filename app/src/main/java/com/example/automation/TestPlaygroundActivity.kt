package com.example.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MahiAiTheme
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestPlaygroundActivity : ComponentActivity() {

    companion object {
        @Volatile
        var lastSelfTestSummary: String = "Not run yet"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MahiAiTheme {
                TestPlaygroundScreen(
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MahiAccessibilityService.isTestPlaygroundActive = true
    }

    override fun onPause() {
        MahiAccessibilityService.isTestPlaygroundActive = false
        super.onPause()
    }

    override fun onDestroy() {
        MahiAccessibilityService.isTestPlaygroundActive = false
        super.onDestroy()
    }
}

@Composable
fun TestPlaygroundScreen(
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var tapCount by remember { mutableIntStateOf(0) }
    var textValue by remember { mutableStateOf("") }
    var submittedText by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf("None") }
    var isAgreed by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 3 })

    var isRunningSelfTest by remember { mutableStateOf(false) }
    var selfTestResults by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var selfTestHeadline by remember { mutableStateOf("") }

    val listEntries = remember {
        (1..30).map { i ->
            if (i == 22) "Target Item #22" else "Playground Item #$i"
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag("test_playground_screen"),
        containerColor = colors.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("playground_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close Test Playground",
                            tint = CyberCyan
                        )
                    }
                    Column {
                        Text(
                            text = "SCREEN ENGINE V2 TESTBED",
                            color = CyberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Mahi Test Playground",
                            color = colors.onBackground,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Self-Test Runner Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, NeonMagenta.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Accessibility Self-Test",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Runs live Mahi Accessibility actions across all 5 widgets below and reports real PASS/FAIL.",
                    color = colors.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Button(
                    onClick = {
                        if (isRunningSelfTest) return@Button
                        isRunningSelfTest = true
                        selfTestResults = emptyList()
                        selfTestHeadline = "Running Accessibility self-test..."
                        scope.launch {
                            val outcomes = mutableListOf<Pair<String, Boolean>>()
                            if (!MahiAccessibilityService.isServiceEnabled(context)) {
                                withContext(Dispatchers.Main) {
                                    selfTestHeadline = "FAIL: Enable Mahi Accessibility Service first."
                                    selfTestResults = listOf("Accessibility Service connected" to false)
                                    TestPlaygroundActivity.lastSelfTestSummary = selfTestHeadline
                                    isRunningSelfTest = false
                                }
                                return@launch
                            }

                            val startTaps = tapCount
                            val startChecked = isAgreed
                            val startPage = pagerState.currentPage

                            val r1 = withContext(Dispatchers.Default) {
                                MahiAccessibilityService.tapElementAwait(context, query = "Action Button")
                            }
                            delay(200L)
                            val pass1 = r1.success && tapCount > startTaps
                            outcomes.add("1. Tap Action Button (taps=$tapCount)" to pass1)

                            val r2 = withContext(Dispatchers.Default) {
                                MahiAccessibilityService.typeTextAwait(
                                    context = context,
                                    query = "Playground Text Box",
                                    text = "Mahi V2 OK",
                                    submit = true
                                )
                            }
                            delay(250L)
                            val pass2 = r2.success && submittedText.contains("Mahi V2 OK")
                            outcomes.add("2. Type & Submit ('${submittedText}')" to pass2)

                            val snapBeforeScroll = MahiAccessibilityService.refreshScreenSnapshot(force = true)?.readableSummary.orEmpty()
                            val r3 = withContext(Dispatchers.Default) {
                                MahiAccessibilityService.scrollScreenAwait(forward = true, speed = GestureSpeed.NORMAL)
                            }
                            delay(250L)
                            val snapAfterScroll = MahiAccessibilityService.refreshScreenSnapshot(force = true)?.readableSummary.orEmpty()
                            val pass3 = r3.success && (r3.changed || snapAfterScroll != snapBeforeScroll)
                            outcomes.add("3. Scroll List Down" to pass3)

                            val r4 = withContext(Dispatchers.Default) {
                                MahiAccessibilityService.swipeScreenAwait(SwipeDirection.LEFT, GestureSpeed.NORMAL)
                            }
                            delay(350L)
                            val pass4 = r4.success && pagerState.currentPage != startPage
                            outcomes.add("4. Horizontal Pager Swipe (page=${pagerState.currentPage + 1}/3)" to pass4)

                            val r5 = withContext(Dispatchers.Default) {
                                MahiAccessibilityService.tapElementAwait(context, query = "Agree Toggle")
                            }
                            delay(200L)
                            val pass5 = r5.success && isAgreed != startChecked
                            outcomes.add("5. Toggle Checkbox (checked=$isAgreed)" to pass5)

                            val passed = outcomes.count { it.second }
                            val headline = "Self-Test: $passed/${outcomes.size} PASSED"
                            TestPlaygroundActivity.lastSelfTestSummary = headline
                            withContext(Dispatchers.Main) {
                                selfTestResults = outcomes
                                selfTestHeadline = headline
                                isRunningSelfTest = false
                            }
                        }
                    },
                    enabled = !isRunningSelfTest,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("playground_run_selftest_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRunningSelfTest) "Testing..." else "Run Accessibility Self-Test", fontWeight = FontWeight.Bold)
                }

                if (selfTestHeadline.isNotBlank()) {
                    Text(
                        text = selfTestHeadline,
                        color = if (selfTestResults.isNotEmpty() && selfTestResults.all { it.second }) CyberEmerald else CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("playground_selftest_headline")
                    )
                }
                if (selfTestResults.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.testTag("playground_selftest_card")
                    ) {
                        selfTestResults.forEach { (label, ok) ->
                            Text(
                                text = "${if (ok) "PASS" else "FAIL"} • $label",
                                color = if (ok) CyberEmerald else WarningAmber,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // 1. Action Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { tapCount++ },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("playground_action_button")
                    ) {
                        Text("Action Button", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Taps: $tapCount",
                        color = CyberCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("playground_tap_count_text")
                    )
                }
            }

            // 2. Text Box + Submit
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Playground Text Box") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playground_text_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { submittedText = textValue },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("playground_submit_button")
                    ) {
                        Text("Submit")
                    }
                    Text(
                        text = "Submitted: ${submittedText.ifBlank { "(empty)" }}",
                        color = CyberEmerald,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("playground_submitted_text")
                    )
                }
            }

            // 3. Long Scrolling List
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Long Scrolling List", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Selected: $selectedItem", color = CyberCyan, fontSize = 11.sp)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                        .testTag("playground_lazy_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(listEntries) { entry ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedItem = entry }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = entry,
                                color = if (entry.contains("#22")) NeonMagenta else colors.onSurface,
                                fontSize = 13.sp,
                                fontWeight = if (entry.contains("#22")) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // 4. Horizontal Pager
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Horizontal Pager", color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Active Page: ${pagerState.currentPage + 1} / 3", color = CyberCyan, fontSize = 12.sp)
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant)
                        .testTag("playground_pager")
                ) { page ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Page ${page + 1} of 3",
                            color = NeonMagenta,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 5. Checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .clickable { isAgreed = !isAgreed }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("playground_checkbox_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isAgreed,
                        onCheckedChange = { isAgreed = it },
                        colors = CheckboxDefaults.colors(checkedColor = CyberEmerald),
                        modifier = Modifier.testTag("playground_checkbox")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Agree Toggle",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = if (isAgreed) "Checked" else "Unchecked",
                    color = if (isAgreed) CyberEmerald else colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
