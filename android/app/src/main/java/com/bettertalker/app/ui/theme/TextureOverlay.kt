package com.bettertalker.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/**
 * Texturas de fundo (Performance First): tudo desenhado proceduralmente —
 * sem PNG grande, sem asset no APK. Os pontos/linhas usam [ink] (onSurface)
 * com opacidade baixíssima; papel/ruído usam um bitmap de ruído minúsculo
 * (64×64) gerado UMA vez e repetido com `BitmapShader` + tint do tema.
 */

/** Bitmap de ruído determinístico (64×64, branco com alpha variável). */
private fun noiseBitmap(seed0: Long, maxAlpha: Int): ImageBitmap {
    val size = 64
    val pixels = IntArray(size * size)
    var seed = seed0
    for (i in pixels.indices) {
        seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
        val a = ((seed shr 16) and 0xFF).toInt()
        val alpha = (a * maxAlpha / 255).coerceIn(0, 255)
        pixels[i] = (alpha shl 24) or 0x00FFFFFF
    }
    return android.graphics.Bitmap
        .createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Ruído fino (grão) e papel (fibra mais densa) — gerados 1× no processo. */
private val NoiseFine: ImageBitmap by lazy { noiseBitmap(12345L, 90) }
private val NoisePaper: ImageBitmap by lazy { noiseBitmap(987654321L, 140) }

/**
 * Fundo global texturizado: pinta [background] e a textura por cima (atrás do
 * conteúdo). As telas com superfícies opacas (cards/app bars) ficam nítidas;
 * a textura aparece nas áreas de fundo.
 *
 * [intensity] (0..1, default 5%) escala o alpha da textura: 0 = invisível,
 * 1 = bem evidente. Cada textura tem um peso relativo (o grid é mais suave
 * que os pontos; o ruído, mais ainda).
 */
fun Modifier.texturedBackground(
    texture: TextureId,
    background: Color,
    ink: Color,
    intensity: Float = 0.05f,
): Modifier = drawBehind {
    drawRect(color = background)
    val k = intensity.coerceIn(0f, 1f)
    when (texture) {
        TextureId.NONE -> Unit

        TextureId.DOTS -> {
            val step = 22.dp.toPx()
            val radius = 1.4.dp.toPx()
            val color = ink.copy(alpha = k)
            var y = step / 2f
            while (y < size.height) {
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(color = color, radius = radius, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }

        TextureId.GRID -> {
            val step = 28.dp.toPx()
            val color = ink.copy(alpha = k * 0.8f)
            val stroke = 1.dp.toPx()
            var x = step
            while (x < size.width) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
                y += step
            }
        }

        TextureId.PAPER -> {
            drawRect(
                brush = ShaderBrush(
                    android.graphics.BitmapShader(
                        NoisePaper.asAndroidBitmap(),
                        android.graphics.Shader.TileMode.REPEAT,
                        android.graphics.Shader.TileMode.REPEAT,
                    )
                ),
                colorFilter = ColorFilter.tint(ink.copy(alpha = k), BlendMode.SrcIn),
            )
        }

        TextureId.NOISE -> {
            drawRect(
                brush = ShaderBrush(
                    android.graphics.BitmapShader(
                        NoiseFine.asAndroidBitmap(),
                        android.graphics.Shader.TileMode.REPEAT,
                        android.graphics.Shader.TileMode.REPEAT,
                    )
                ),
                colorFilter = ColorFilter.tint(ink.copy(alpha = k * 0.7f), BlendMode.SrcIn),
            )
        }
    }
}
