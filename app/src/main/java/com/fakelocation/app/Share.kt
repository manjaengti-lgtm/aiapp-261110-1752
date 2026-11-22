package com.fakelocation.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object Share {
    private val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun whatsappPackage(ctx: Context): String? =
        WHATSAPP.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    private fun mapsLink(p: Pt) = "https://maps.google.com/?q=%.6f,%.6f".format(p.lat, p.lng)

    /**
     * Sends the location as a Google Maps link. [toWhatsApp] = straight to WhatsApp (tries 3 ways),
     * otherwise the normal share menu.
     */
    fun sendLocation(ctx: Context, p: Pt, toWhatsApp: Boolean): Boolean {
        val text = "📍 My location\n${mapsLink(p)}"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (toWhatsApp) {
            for (pkg in WHATSAPP) {
                try { ctx.startActivity(Intent(send).setPackage(pkg)); return true } catch (_: Exception) { }
            }
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?text=" + Uri.encode(text)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Exception) { }
        }
        return try {
            ctx.startActivity(Intent.createChooser(send, "Send location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens WhatsApp's app settings (to give it Location permission). */
    fun openWhatsAppPermissions(ctx: Context) {
        val pkg = whatsappPackage(ctx) ?: WHATSAPP[0]
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    /** Opens WhatsApp so the user can start "Share live location" in a chat. */
    fun openWhatsApp(ctx: Context): Boolean {
        for (pkg in WHATSAPP) {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { }
        }
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
