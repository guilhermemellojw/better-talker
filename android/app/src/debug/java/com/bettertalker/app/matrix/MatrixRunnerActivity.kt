package com.bettertalker.app.matrix

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bettertalker.app.data.db.DbProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Porta de entrada debug-only do spike C2.6. Pode iniciar a matriz direto
 * (`--ez start true`) ou semear a nota de fixture para screenshots/a11y.
 */
class MatrixRunnerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply {
            text = "Matriz C2.6 (debug)"
            textSize = 22f
            setTextColor(Color.BLACK)
        }
        status = TextView(this).apply {
            text = "Pronto."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 24, 0, 24)
        }
        root.addView(title)
        root.addView(status)
        root.addView(button("Iniciar matriz (baixar + rodar)") { startMatrix() })
        root.addView(button("Semear nota de fixture (UI)") { seedUi() })
        root.addView(button("Abrir Home do app") {
            startActivity(Intent().setClassName(packageName, "com.bettertalker.app.MainActivity"))
        })
        root.addView(button("Ver log") { showLog() })
        val scroll = ScrollView(this).apply {
            addView(
                TextView(this@MatrixRunnerActivity).apply {
                    textSize = 11f
                    setTextColor(Color.BLACK)
                    setPadding(0, 24, 0, 24)
                    tag = "log"
                },
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        if (intent?.getBooleanExtra("start", false) == true) startMatrix()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    private fun startMatrix() {
        status.text = "Iniciando serviço…"
        val intent = Intent(this, MatrixRunnerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        status.text = "Serviço iniciado. Acompanhe pelo log/notification."
    }

    private fun seedUi() {
        status.text = "Semeando nota de fixture…"
        scope.launch {
            try {
                val id = UiFixtureSeeder.seed(applicationContext, DbProvider.get(applicationContext))
                runOnUiThread {
                    status.text = "Nota criada: $id\nBusque por '${UiFixtureSeeder.TITLE}' na Home."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falha ao semear: ${e.message}" }
            }
        }
    }

    private fun showLog() {
        val text = runCatching {
            File(filesDir, MatrixRunnerService.LOG_NAME).readLines().takeLast(80).joinToString("\n")
        }.getOrElse { "sem log: ${it.message}" }
        status.text = "Resultados em files/${MatrixCsv.RESULT_NAME} e ${MatrixCsv.SUMMARY_NAME}"
        (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup)
            ?.findViewWithTag<TextView>("log")?.text = text.ifBlank { "(vazio)" }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
