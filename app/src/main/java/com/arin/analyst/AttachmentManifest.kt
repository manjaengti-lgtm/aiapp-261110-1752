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

    /** Extensions whose contents are plain text, so they can be read without a special tool. */
    private val textExtensions = setOf(
        "txt", "md", "markdown", "json", "xml", "csv", "tsv", "kt", "kts", "java", "log", "html", "htm",
        "css", "js", "ts", "yml", "yaml", "toml", "gradle", "properties", "sh", "py", "svg", "ini", "conf",
    )

    fun linuxPath(name: String): String = "$DIR/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The block handed to the model for this turn, or "" when nothing was attached. */
    fun build(attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return ""
        return buildString {
            appendLine("FILES ATTACHED THIS TURN (they are on the phone, open them with Bash):")
            attachments.forEach { a ->
                append("- ").append(linuxPath(a.name))
                append(" (").append(a.name).append(", ").append(kindOf(a))
                append(if (isBinary(a)) ", binary" else ", text")
                appendLine(")")
            }
            appendLine("A video is never sent as video: take frames with ffmpeg and look at those.")
            if (attachments.size > MAX_IMAGES) {
                appendLine("More than $MAX_IMAGES pictures: only the first $MAX_IMAGES are sent as pictures, the rest only as paths.")
            }
        }.trimEnd()
    }

    /** The attachment's kind, in lower case, whichever shape the kind value has. */
    private fun kindOf(a: Attachment): String = a.kind.toString().lowercase().ifBlank { "file" }

    /** Whether the file is binary (pictures, video, sound, archives) rather than plain text. */
    private fun isBinary(a: Attachment): Boolean {
        if (a.name.substringAfterLast('.', "").lowercase() in textExtensions) return false
        return when (kindOf(a).uppercase()) {
            "TEXT", "URL", "LINK", "DOCUMENT" -> false
            else -> true
        }
    }
}
