package com.github.qdreaderexporter.moduleui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.qdreaderexporter.BuildConfig
import com.github.qdreaderexporter.hook.HookTargets
import com.highcapable.yukihookapi.YukiHookAPI

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = YukiHookAPI.Status.isModuleActive
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        fun addLine(text: String, size: Float = 15f, color: Int = Color.DKGRAY, bold: Boolean = false) {
            content.addView(
                TextView(this@MainActivity).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, pad / 4, 0, pad / 4)
                }
            )
        }

        addLine("QDReaderExporter", 22f, Color.BLACK, true)
        addLine("模块版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        addLine(
            if (active) "状态: 已激活 (LSPosed/Xposed)" else "状态: 未激活",
            16f,
            if (active) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"),
            true
        )
        addLine("")
        addLine("目标应用", 16f, Color.BLACK, true)
        addLine("包名: ${HookTargets.HOST_PACKAGE}")
        addLine(
            "钉死版本: ${HookTargets.EXPECTED_VERSION_NAME} " +
                "(versionCode ${HookTargets.EXPECTED_VERSION_CODE})"
        )
        addLine("")
        addLine("使用说明", 16f, Color.BLACK, true)
        addLine(
            "1. 在 LSPosed 中启用本模块\n" +
                "2. 作用域仅勾选 com.qidian.QDReader\n" +
                "3. 强行停止起点读书后重新打开\n" +
                "4. 进入小说阅读页，右下角出现「导出」按钮\n" +
                "5. 浏览章节后导出当前章 / 已缓存章\n" +
                "6. 导出目录: /sdcard/Documents/QDReaderExporter/"
        )
        addLine("")
        addLine("声明", 16f, Color.BLACK, true)
        addLine(
            "仅捕获宿主已解密、当前账号可阅读或已缓存的正文，" +
                "用于个人备份。请勿传播内容、勿用于绕过付费。"
        )
        addLine("")
        addLine("Logcat 过滤标签: QDReaderExporter")

        setContentView(ScrollView(this).apply { addView(content) })
    }
}
