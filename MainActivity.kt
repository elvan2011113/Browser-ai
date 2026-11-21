package com.example.aibrowser

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var answer: TextView
    private lateinit var question: EditText

    private val history = mutableListOf<Pair<String, String>>()
    private var busy = false

    private val system =
        "Kamu asisten AI di dalam aplikasi browser. Pengguna sedang membuka sebuah halaman web; " +
        "isi teksnya diberikan bersama pertanyaan. Jawab dalam bahasa Indonesia, singkat dan jelas, " +
        "berdasarkan isi halaman. Jangan pakai format markdown (tanpa tanda bintang atau pagar); " +
        "tulis teks biasa. Jika halaman tidak memuat jawabannya, katakan terus terang."

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- bar alamat ---
        urlBar = EditText(this).apply {
            hint = "Ketik alamat atau kata pencarian"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        val goBtn = Button(this).apply { text = "Buka"; setOnClickListener { go() } }
        val keyBtn = Button(this).apply { text = "Key"; setOnClickListener { showKeyDialog() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(24), dp(8), 0)
            addView(urlBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(goBtn)
            addView(keyBtn)
        }
        root.addView(bar)

        // --- web ---
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    urlBar.setText(url)
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- panel AI ---
        answer = TextView(this).apply {
            text = "Buka sebuah halaman, lalu tanya AI."
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(answer) }

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "Ringkas"
                setOnClickListener { ask("Ringkas halaman ini dalam beberapa poin penting.") }
            })
            addView(Button(this@MainActivity).apply {
                text = "Jelaskan sederhana"
                setOnClickListener { ask("Jelaskan isi halaman ini dengan bahasa yang sederhana.") }
            })
        }

        question = EditText(this).apply {
            hint = "Tanya soal halaman ini..."
            setOnEditorActionListener { _, _, _ -> sendQuestion(); true }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine()
        }
        val askBtn = Button(this).apply { text = "Tanya"; setOnClickListener { sendQuestion() } }
        val askRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(question, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(askBtn)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFEFEFEF.toInt())
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)))
            addView(chips)
            addView(askRow)
        }
        root.addView(panel)

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        web.loadUrl("https://www.google.com")
        if (apiKey().isEmpty()) showKeyDialog()
    }

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun apiKey() = prefs().getString("key", "") ?: ""

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            hint = "sk-ant-..."
            setSingleLine()
            setText(apiKey())
        }
        AlertDialog.Builder(this)
            .setTitle("API key Anthropic")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                prefs().edit().putString("key", input.text.toString().trim()).apply()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun go() {
        val t = urlBar.text.toString().trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
        hideKeyboard()
    }

    private fun sendQuestion() {
        val q = question.text.toString().trim()
        if (q.isEmpty()) return
        question.setText("")
        hideKeyboard()
        ask(q)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun msg(role: String, text: String) =
        JSONObject().put("role", role).put("content", text)

    private fun ask(userQ: String) {
        val key = apiKey()
        if (key.isEmpty()) { showKeyDialog(); return }
        if (busy) return
        busy = true
        answer.text = "Membaca halaman..."

        web.evaluateJavascript(
            "(function(){return document.body ? document.body.innerText : ''})()"
        ) { v ->
            val pageText = (runCatching { JSONTokener(v).nextValue() as? String }.getOrNull() ?: "")
                .take(20000)
            val content = "URL: ${web.url}\nJudul: ${web.title}\n\n" +
                "Isi halaman:\n$pageText\n\nPermintaan: $userQ"
            val past = history.takeLast(6).toList()

            answer.text = "Menganalisis..."
            Thread {
                val msgs = JSONArray()
                past.forEach { msgs.put(msg("user", it.first)); msgs.put(msg("assistant", it.second)) }
                msgs.put(msg("user", content))
                val res = ClaudeClient.ask(key, system, msgs)
                runOnUiThread {
                    answer.text = res
                    if (!res.startsWith("Error") && !res.startsWith("Gagal")) history.add(userQ to res)
                    busy = false
                }
            }.start()
        }
    }
}
