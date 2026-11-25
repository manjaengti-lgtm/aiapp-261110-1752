package com.arin.analyst

import com.arin.data.Attachment

/**
 * Attached files already land in `/root/files` on the phone. This builds the per-turn list that tells
 * every tab the path and file name, which on its own makes attachments usable today: the tab can open
 * them with Bash even when it can't look at pictures.
 *
 * A tab ignoring the list is not an error.
 */
object AttachmentManifest {

    /** Where attachments land inside the phone's Linux. */
    const val DIR = "/root/files"

    /** Max 12 images per turn (the rest are still listed, just not sent as pictures). */
    const val MAX_IMAGES = 12

    fun linuxPath(name: String): String = "$DIR/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The block handed to the model for this turn, or "" when nothing was attached. */
    fun build(attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return ""
        return buildString {
            appendLine("FILES ATTACHED THIS TURN (they are on the phone, open them with Bash):")
            attachments.forEach { a ->
                append("- ").append(linuxPath(a.name))
                append(" (").append(a.name).append(", ").append(a.kind.name.lowercase())
                if (a.isBinary) append(", binary") else append(", text")
                appendLine(")")
            }
            appendLine("A video is never sent as video: take frames with ffmpeg and look at those.")
            if (attachments.count { it.kind == Attachment.IMAGE } > MAX_IMAGES) {
                appendLine("More than $MAX_IMAGES pictures: only the first $MAX_IMAGES are sent as pictures, the rest only as paths.")
            }
        }.trimEnd()
    }
}
