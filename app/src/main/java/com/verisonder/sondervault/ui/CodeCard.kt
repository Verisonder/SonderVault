package com.verisonder.sondervault.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.display
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.verisonder.sondervault.crypto.RecoveryPhrase
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

    Column(
        modifier = modifier.fillMaxWidth().clip(blockShape).background(Palette.panel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Kicker("Your six words", Modifier.align(Alignment.Start).padding(bottom = 16.dp))
        qr?.let {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(8.dp),
            ) {
                Image(bitmap = it, contentDescription = null, modifier = Modifier.size(184.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
        // The words as numbered chips, so each one is easy to copy down in order.
        words.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { i, w ->
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.card2)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${row * 2 + i + 1}", style = display(12), color = Palette.accentText)
                        Spacer(Modifier.width(10.dp))
                        Text(w, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Copy", filled = false) { clipboard.setText(AnnotatedString(text)) }
            PillButton("Save as file", filled = false, onClick = onSaveToFile)
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
