// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat

/**
 * 把向量圖示畫成「液態玻璃圓鈕」點陣圖（半透明漸層、頂部高光、漸層邊緣），
 * 給 HyperIsland / 焦點通知的圖示與按鈕使用，外觀與 App 內的玻璃卡片一致。
 */
object GlassIcons {
    private val cache = HashMap<String, Icon>()

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    /** @param base 玻璃底色（會以低透明度填入）　@param glyph 圖示顏色 */
    @Synchronized
    fun glass(ctx: Context, res: Int, base: Int = Color.WHITE, glyph: Int = Color.WHITE): Icon {
        val key = "$res:$base:$glyph"
        return cache.getOrPut(key) {
            val sz = 128
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = sz / 2f
            val r = sz / 2f - 3f
            val s = sz.toFloat()

            // 玻璃本體：上亮下淡的半透明漸層
            c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, s, alpha(base, 0x70), alpha(base, 0x24), Shader.TileMode.CLAMP)
            })
            // 頂部鏡面高光
            c.save()
            c.clipPath(Path().apply { addCircle(cx, cx, r, Path.Direction.CW) })
            c.drawRect(0f, 0f, s, s * 0.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, s * 0.55f, 0x70FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            c.restore()
            // 邊緣折光
            c.drawCircle(cx, cx, r - 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                shader = LinearGradient(
                    0f, 0f, s, s,
                    intArrayOf(0xD0FFFFFF.toInt(), 0x30FFFFFF, 0x90FFFFFF.toInt()), null, Shader.TileMode.CLAMP,
                )
            })
            // 圖示
            ContextCompat.getDrawable(ctx, res)?.mutate()?.let { d ->
                d.setTint(glyph)
                val m = 34
                d.setBounds(m, m, sz - m, sz - m)
                d.draw(c)
            }
            Icon.createWithBitmap(bmp)
        }
    }
}
