package com.example.asqachat

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Msg(val from: String, val text: String, val ts: Long, val mine: Boolean)

class MainActivity : AppCompatActivity() {

    private lateinit var http: OkHttpClient
    private var ws: WebSocket? = null

    private var myLogin = ""
    private var serverAddr = ""
    private var currentChat = ""               // "" = общий чат, иначе логин собеседника

    private val online = LinkedHashSet<String>()
    private val unread = mutableMapOf<String, Int>()
    private val dmPartners = LinkedHashSet<String>()

    private lateinit var loginScreen: LinearLayout
    private lateinit var chatScreen: LinearLayout
    private lateinit var etServer: EditText
    private lateinit var etLogin: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvTitle: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button

    private lateinit var adapter: MsgAdapter

    // ---------- ЗВУК УВЕДОМЛЕНИЯ ----------
    private fun playNotify() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            else
                @Suppress("DEPRECATION") v.vibrate(60)
        } catch (e: Exception) { /* нет звука — не беда */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        adapter = MsgAdapter()
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = adapter

        btnLogin.setOnClickListener { doLogin() }
        btnSend.setOnClickListener { sendMessage() }

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == adapter.itemCount - itemCount)
                    rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
            }
        })
    }

    // ---------- ВХОД ----------
    private fun doLogin() {
        val addr = etServer.text.toString().trim().removeSuffix("/")
        val login = etLogin.text.toString().trim()
        val pass = etPass.text.toString()
        if (!addr.startsWith("http")) { toast("Адрес сервера должен начинаться с http"); return }
        if (!login.matches(Regex("\\d{3,}"))) { toast("Логин — только цифры, минимум 3"); return }
        if (pass.length < 4) { toast("Пароль — минимум 4 символа"); return }

        serverAddr = addr
        val body = JSONObject().put("login", login).put("password", pass).toString()
        http.newCall(Request.Builder()
            .url("$addr/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    runOnUiThread { toast("Сервер недоступен: ${e.message}") }
                override fun onResponse(call: Call, resp: Response) {
                    val json = JSONObject(resp.body!!.string())
                    runOnUiThread {
                        if (json.optBoolean("ok")) {
                            myLogin = login
                            loginScreen.visibility = View.GONE
                            chatScreen.visibility = View.VISIBLE
                            connectWs()
                        } else toast(json.optString("error", "Ошибка входа"))
                    }
                }
            })
    }

    private fun connectWs() {
        val wsUrl = serverAddr.replaceFirst("http", "ws") +
                "/ws?user=" + URLEncoder.encode(myLogin, "UTF-8")
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) =
                runOnUiThread { handleServerMessage(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                runOnUiThread { toast("Соединение потеряно") }
            override fun onClosed(webSocket: WebSocket, response: Response) =
                runOnUiThread { toast("Соединение закрыто") }
        })
    }

    // ---------- СООБЩЕНИЯ С СЕРВЕРА ----------
    private fun handleServerMessage(text: String) {
        val obj = try { JSONObject(text) } catch (e: Exception) { return }
        when (obj.optString("type")) {
            "msg" -> {
                val from = obj.optString("from")
                val to = obj.optString("to")
                val msgText = obj.optString("text")
                val ts = (obj.optDouble("ts") * 1000).toLong()

                if (to == currentChat) {
                    adapter.add(Msg(from, msgText, ts, from == myLogin))
                    if (from != myLogin)
                        ws?.send(JSONObject().put("type", "read").put("to", to).toString())
                } else if (from == myLogin && to.isNotEmpty()) {
                    dmPartners.add(to)
                } else if (from != myLogin) {
                    dmPartners.add(from)
                    unread[from] = (unread[from] ?: 0) + 1
                    toast("Новое сообщение от $from")
                }
                if (from != myLogin) playNotify()
                refreshChips()
            }
            "online" -> {
                online.clear()
                val arr = obj.optJSONArray("users") ?: JSONArray()
                for (i in 0 until arr.length()) online.add(arr.getString(i))
                refreshChips()
            }
            "unread" -> {
                val counts = obj.optJSONObject("counts") ?: return
                unread.clear()
                for (k in counts.keys()) unread[k] = counts.optInt(k)
                refreshChips()
            }
            "history" -> {
                val arr = obj.optJSONArray("messages") ?: return
                val list = mutableListOf<Msg>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    list.add(Msg(m.optString("from"), m.optString("text"),
                        (m.optDouble("ts") * 1000).toLong(), m.optString("from") == myLogin))
                    if (m.optString("to").isNotEmpty()) {
                        dmPartners.add(m.optString("from"))
                        dmPartners.add(m.optString("to"))
                    }
                }
                adapter.setData(list)
            }
        }
    }

    // ---------- ОТПРАВКА ----------
    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty() || ws == null) return
        ws?.send(JSONObject().put("type", "msg").put("to", currentChat).put("text", text).toString())
        if (currentChat.isNotEmpty()) dmPartners.add(currentChat)
        etMessage.setText("")
        refreshChips()
    }

    // ---------- ПЕРЕКЛЮЧЕНИЕ ЧАТОВ ----------
    private fun openChat(name: String) {
        currentChat = name
        tvTitle.text = if (name.isEmpty()) "Общий чат" else "Диалог с $name"
        adapter.setData(emptyList())
        refreshChips()
        val url = "$serverAddr/history?user=${URLEncoder.encode(myLogin, "UTF-8")}" +
                "&peer=${URLEncoder.encode(name, "UTF-8")}"
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, resp: Response) {
                val json = JSONObject(resp.body!!.string())
                runOnUiThread {
                    val arr = json.optJSONArray("messages") ?: return@runOnUiThread
                    val list = mutableListOf<Msg>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        list.add(Msg(m.optString("from"), m.optString("text"),
                            (m.optDouble("ts") * 1000).toLong(), m.optString("from") == myLogin))
                    }
                    adapter.setData(list)
                    val counts = json.optJSONObject("unread")
                    if (counts != null) {
                        unread.clear()
                        for (k in counts.keys()) unread[k] = counts.optInt(k)
                        refreshChips()
                    }
                }
            }
        })
    }

    // ---------- ЧИПЫ ----------
    @SuppressLint("ResourceType")
    private fun refreshChips() {
        chipsRow.removeAllViews()
        val names = linkedSetOf("")
        dmPartners.forEach { if (it != myLogin) names.add(it) }
        online.forEach { if (it != myLogin) names.add(it) }

        for (n in names) {
            val cnt = unread[n] ?: 0
            val label = if (n.isEmpty()) "Общий чат" else n + (if (cnt > 0) " ●$cnt" else "")
            val tv = TextView(this)
            tv.text = label
            tv.setPadding(dp(14), dp(6), dp(14), dp(6))
            tv.setBackgroundResource(if (n == currentChat) R.drawable.chip_active else R.drawable.chip_bg)
            tv.setTextColor(if (n == currentChat) 0xFF5C1616.toInt() else 0xFFF0C05A.toInt())
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (cnt > 0) tv.setTypeface(null, Typeface.BOLD)
            tv.setOnClickListener { openChat(n) }
            chipsRow.addView(tv)
            val sp = View(this)
            sp.layoutParams = LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.WRAP_CONTENT)
            chipsRow.addView(sp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, null)
        http.dispatcher.executorService.shutdown()
    }
}

class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
    private val items = mutableListOf<Msg>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(list: List<Msg>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun add(m: Msg) { items.add(m); notifyItemInserted(items.size - 1) }
    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.msgRoot)
        val bubble: TextView = v.findViewById(R.id.tvBubble)
        val time: TextView = v.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_msg, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.bubble.text = m.text
        h.time.text = fmt.format(Date(m.ts))
        val g = if (m.mine) Gravity.END else Gravity.START
        h.root.gravity = g
        h.time.gravity = g
        if (m.mine) {
            h.bubble.setBackgroundResource(R.drawable.bubble_mine)
            h.bubble.setTextColor(0xFFF0C05A.toInt())
            h.time.setTextColor(0xFF8A6D3B.toInt())
        } else {
            h.bubble.setBackgroundResource(R.drawable.bubble_other)
            h.bubble.setTextColor(0xFF3A2A1A.toInt())
            h.time.setTextColor(0xFF8A6D3B.toInt())
        }
    }
}
