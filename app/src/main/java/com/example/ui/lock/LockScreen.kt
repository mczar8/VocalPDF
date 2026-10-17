package com.example.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.security.AppLockManager
import com.example.security.AppLockManager.VerifyResult
import com.example.security.PinHasher
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ==========================================================================================
// Briques communes : points + clavier
// ==========================================================================================

@Composable
fun PinDots(length: Int, isError: Boolean, errorTick: Int, modifier: Modifier = Modifier) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            for (x in listOf(14f, -12f, 8f, -6f, 0f)) shake.animateTo(x, tween(45))
        }
    }
    Row(
        modifier = modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        repeat(PinHasher.PIN_LENGTH) { i ->
            val filled = i < length
            val color = when {
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(2.dp, color, CircleShape)
                    .background(if (filled) color else MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
fun PinPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf(' ', '0', '<'))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { key ->
                    when (key) {
                        ' ' -> Spacer(modifier = Modifier.size(72.dp))
                        '<' -> Surface(
                            onClick = onBackspace,
                            enabled = enabled,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("pin_key_backspace")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = stringResource(R.string.cd_backspace)
                                )
                            }
                        }
                        else -> Surface(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDigit(key)
                            },
                            enabled = enabled,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("pin_key_$key")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = key.toString(),
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compte à rebours en secondes jusqu'à la fin du délai d'attente. */
@Composable
private fun rememberLockoutSeconds(lockoutUntil: Long): Int {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntil) {
        while (System.currentTimeMillis() < lockoutUntil) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    return ((lockoutUntil - now + 999) / 1000).toInt().coerceAtLeast(0)
}

// ==========================================================================================
// Écran de verrouillage (au lancement / au retour dans l'app)
// ==========================================================================================

@Composable
fun LockScreen(lockManager: AppLockManager) {
    var entered by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    var lockoutUntil by remember { mutableLongStateOf(System.currentTimeMillis() + lockManager.remainingLockoutMs()) }
    val secondsLeft = rememberLockoutSeconds(lockoutUntil)
    val wrongFormat = stringResource(R.string.lock_wrong_pin)

    fun submit(pin: String) {
        when (val result = lockManager.unlock(pin)) {
            VerifyResult.Success -> errorText = null
            is VerifyResult.Wrong -> {
                errorText = wrongFormat.format(result.attemptsBeforeDelay)
                errorTick++
            }
            is VerifyResult.LockedOut -> {
                lockoutUntil = System.currentTimeMillis() + result.remainingMs
                errorText = null
                errorTick++
            }
        }
        entered = ""
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .testTag("lock_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Image(
                    painter = painterResource(R.drawable.ic_vocal_pdf_logo),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            PinDots(length = entered.length, isError = errorText != null && entered.isEmpty(), errorTick = errorTick)
            Spacer(modifier = Modifier.height(12.dp))
            val status = when {
                secondsLeft > 0 -> stringResource(R.string.lock_locked_out, secondsLeft)
                else -> errorText.orEmpty()
            }
            Text(
                text = status,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.height(36.dp)
            )
            PinPad(
                enabled = secondsLeft == 0,
                onDigit = { d ->
                    if (entered.length < PinHasher.PIN_LENGTH) {
                        entered += d
                        if (entered.length == PinHasher.PIN_LENGTH) submit(entered)
                    }
                },
                onBackspace = { entered = entered.dropLast(1) }
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.lock_forgot),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )
        }
    }
}

// ==========================================================================================
// Création / modification / désactivation du code
// ==========================================================================================

enum class PinFlowMode { ENABLE, DISABLE, CHANGE }

private enum class PinStep { CURRENT, NEW, CONFIRM }

@Composable
fun PinFlowDialog(
    mode: PinFlowMode,
    lockManager: AppLockManager,
    onFinished: (messageRes: Int?) -> Unit
) {
    var step by remember { mutableStateOf(if (mode == PinFlowMode.ENABLE) PinStep.NEW else PinStep.CURRENT) }
    var entered by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    var lockoutUntil by remember { mutableLongStateOf(System.currentTimeMillis() + lockManager.remainingLockoutMs()) }
    val secondsLeft = rememberLockoutSeconds(lockoutUntil)

    val wrongFormat = stringResource(R.string.lock_wrong_pin)
    val mismatchText = stringResource(R.string.pin_mismatch)

    fun fail(message: String?) {
        errorText = message
        errorTick++
        entered = ""
    }

    fun onComplete(pin: String) {
        when (step) {
            PinStep.CURRENT -> when (val result = lockManager.verify(pin)) {
                VerifyResult.Success -> {
                    if (mode == PinFlowMode.DISABLE) {
                        lockManager.disable()
                        onFinished(R.string.msg_lock_disabled)
                    } else {
                        step = PinStep.NEW
                        entered = ""
                        errorText = null
                    }
                }
                is VerifyResult.Wrong -> fail(wrongFormat.format(result.attemptsBeforeDelay))
                is VerifyResult.LockedOut -> {
                    lockoutUntil = System.currentTimeMillis() + result.remainingMs
                    fail(null)
                }
            }
            PinStep.NEW -> {
                firstPin = pin
                step = PinStep.CONFIRM
                entered = ""
                errorText = null
            }
            PinStep.CONFIRM -> {
                if (pin == firstPin) {
                    lockManager.setPin(pin)
                    onFinished(if (mode == PinFlowMode.CHANGE) R.string.msg_pin_changed else R.string.msg_lock_enabled)
                } else {
                    firstPin = ""
                    step = PinStep.NEW
                    fail(mismatchText)
                }
            }
        }
    }

    val title = stringResource(
        when (mode) {
            PinFlowMode.ENABLE -> R.string.pin_setup_title
            PinFlowMode.DISABLE -> R.string.pin_disable_title
            PinFlowMode.CHANGE -> R.string.pin_change_title
        }
    )
    val subtitle = stringResource(
        when (step) {
            PinStep.CURRENT -> R.string.pin_current_subtitle
            PinStep.NEW -> R.string.pin_setup_subtitle
            PinStep.CONFIRM -> R.string.pin_confirm_subtitle
        }
    )

    Dialog(onDismissRequest = { onFinished(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 380.dp)
                .testTag("dialog_pin_flow")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                PinDots(length = entered.length, isError = errorText != null && entered.isEmpty(), errorTick = errorTick)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (secondsLeft > 0) stringResource(R.string.lock_locked_out, secondsLeft) else errorText.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(34.dp)
                )
                PinPad(
                    enabled = secondsLeft == 0 || step != PinStep.CURRENT,
                    onDigit = { d ->
                        if (entered.length < PinHasher.PIN_LENGTH) {
                            entered += d
                            if (entered.length == PinHasher.PIN_LENGTH) onComplete(entered)
                        }
                    },
                    onBackspace = { entered = entered.dropLast(1) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { onFinished(null) }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
