package com.example.aibrowser

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {

    private const val MODEL = "claude-sonnet-5-5"

    fun ask(apiKey: String, system: String, messages: JSONArray): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1200)
            .put("system", system)
            .put("messages", messages)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)

            if (code !in 200..299) {
                val msg = json.optJSONObject("error")?.optString("message") ?: raw
                return "Error $code: $msg"
            }
            val content = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.getString("text"))
            }
            return sb.toString().ifBlank { "(jawaban kosong)" }
        } catch (e: Exception) {
            return "Gagal: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }
}
