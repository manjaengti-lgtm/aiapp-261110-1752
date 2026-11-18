package com.fakelocation.app

import android.content.Context
import android.content.Intent

object Share {
    private val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun whatsappPackage(ctx: Context): String? =
        WHATSAPP.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    private fun mapsLink(p: Pt) = "https://maps.google.com/?q=%.6f,%.6f".format(p.lat, p.lng)

    /** Sends the location as a Google Maps link. [toWhatsApp] = straight to WhatsApp, else app chooser. */
    fun sendLocation(ctx: Context, p: Pt, toWhatsApp: Boolean): Boolean {
        val text = "📍 My location\n${mapsLink(p)}"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return try {
            val wa = whatsappPackage(ctx)
            if (toWhatsApp && wa != null) {
                ctx.startActivity(send.setPackage(wa).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                ctx.startActivity(
                    Intent.createChooser(send, "Send location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens WhatsApp so the user can start "Share live location" in a chat. */
    fun openWhatsApp(ctx: Context): Boolean {
        val wa = whatsappPackage(ctx) ?: return false
        return try {
            ctx.startActivity(
                ctx.packageManager.getLaunchIntentForPackage(wa)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
