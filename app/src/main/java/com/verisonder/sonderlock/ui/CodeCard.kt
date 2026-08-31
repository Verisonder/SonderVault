package com.verisonder.sonderlock.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.verisonder.sonderlock.crypto.RecoveryPhrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The six words, shown once.
 *
 * The QR is there because typing twelve syllables into another phone is where mistakes
 * happen, and scanning is free. It carries the same words and nothing else — no hint of
 * what it opens, so a photograph of the screen is not a photograph of the vault.
 */
@Composable
fun CodeCard(
    words: List<String>,
    onSaveToFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val text = RecoveryPhrase.canonical(words)
    val qr by produceState<ImageBitmap?>(initialValue = null, text) {
        value = withContext(Dispatchers.Default) { runCatching { qrCode(text) }.getOrNull() }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            qr?.let {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(8.dp),
                ) {
                    Image(bitmap = it, contentDescription = null, modifier = Modifier.size(184.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                TextButton(onClick = onSaveToFile) { Text("Save as file") }
            }
        }
    }
}

private fun qrCode(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
