package com.copiloto.auto.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.copiloto.core.apps.AppNotification

/**
 * Lee las notificaciones del móvil (Gmail, Outlook, Flashscore, banco…) para que Copiloto
 * pueda contártelas por voz en el coche. El usuario debe activarlo en
 * Ajustes → Notificaciones → Acceso a notificaciones → Copiloto.
 * No se guarda nada en disco: solo las últimas notificaciones, en memoria.
 */
class NotificationReader : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { add(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = add(sbn)

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS ||
            n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_NAVIGATION
        ) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val item = AppNotification(sbn.packageName, appLabel(sbn.packageName), title, text, sbn.postTime)
        synchronized(store) {
            store.removeAll { it.packageName == item.packageName && it.title == item.title && it.text == item.text }
            store.addFirst(item)
            while (store.size > MAX) store.removeLast()
        }
    }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    companion object {
        private const val MAX = 60
        private val store = ArrayDeque<AppNotification>()
        private val labels = HashMap<String, String>()

        /** Notificaciones de las últimas 24 h, de la más nueva a la más antigua. */
        fun recent(): List<AppNotification> {
            val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            return synchronized(store) { store.filter { it.timeMillis >= since } }
        }

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
