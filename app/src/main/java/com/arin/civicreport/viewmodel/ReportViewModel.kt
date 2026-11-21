package com.arin.civicreport.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arin.civicreport.CivicReportApp
import com.arin.civicreport.data.AuthorityRules
import android.net.Uri
import com.arin.civicreport.model.AppSettings
import com.arin.civicreport.model.Attachment
import com.arin.civicreport.model.Authority
import com.arin.civicreport.model.AuthorityLevel
import com.arin.civicreport.model.IncidentType
import com.arin.civicreport.model.Report
import com.arin.civicreport.model.ReportLocation
import com.arin.civicreport.model.ReportStatus
import com.arin.civicreport.model.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DraftState(
    val types: Set<IncidentType> = emptySet(),
    val location: ReportLocation = ReportLocation(),
    val notes: String = "",
    val photos: List<String> = emptyList(),
    val video: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val authorities: List<Authority> = emptyList(),
    val customAuthorities: List<Authority> = emptyList(),
    val matchedDistrict: String? = null,
    val matchedState: String? = null,
    val directoryDate: String = "",
    val selectedAuthorityIds: Set<String> = emptySet(),
    val locating: Boolean = false,
    val locationError: String? = null,
    val searchingAuthorities: Boolean = false,
    val loadError: String? = null,
    /** Set once the draft has been saved, so saving again updates the same complaint. */
    val draftId: String? = null
) {
    val canContinue: Boolean get() = types.isNotEmpty() && location.hasFix
}

class ReportViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as CivicReportApp).container

    val settingsStore = container.settings
    val locationHelper = container.location
    val authorityFinder = container.authorities
    val sendHelper = container.sender
    val detector = container.detector
    val notifications = container.notifications
    val mediaStore = container.media

    private val _draft = MutableStateFlow(DraftState())
    val draft: StateFlow<DraftState> = _draft.asStateFlow()

    val reports: StateFlow<List<Report>> = container.reports.reports

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _sendResult = MutableStateFlow<String?>(null)
    val sendResult: StateFlow<String?> = _sendResult.asStateFlow()

    val defaultChannel: StateFlow<SendChannel> = settings
        .map { it.defaultChannel }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SendChannel.BOTH)

    init {
        viewModelScope.launch { container.reports.load() }
        container.notifications.ensureChannel()
    }

    // ---------- draft editing ----------

    fun toggleType(type: IncidentType) {
        _draft.update { d ->
            val next = if (type in d.types) d.types - type else d.types + type
            d.copy(types = next, authorities = emptyList(), selectedAuthorityIds = emptySet())
        }
        refreshLocationAndAuthorities()
    }

    fun setNotes(text: String) = _draft.update { it.copy(notes = text) }

    fun addPhoto(path: String) = _draft.update { it.copy(photos = it.photos + path) }

    fun removePhoto(path: String) {
        // Saved evidence stays in "Saved photos & videos"; only private copies are deleted.
        if (!evidence.contains(path)) mediaStore.deleteFile(path)
        _draft.update { it.copy(photos = it.photos.filterNot { p -> p == path }) }
    }

    fun setVideo(path: String?) {
        _draft.update { d ->
            d.video?.let { old -> if (old != path && !evidence.contains(old)) mediaStore.deleteFile(old) }
            d.copy(video = path)
        }
    }

    /** Adds files of any type and size; they are referenced, not copied. */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val added = uris.map { mediaStore.attach(it) }
        _draft.update { d ->
            val known = d.attachments.map { it.uri }.toSet()
            d.copy(attachments = d.attachments + added.filter { it.uri !in known })
        }
    }

    /** Adds a file already copied into the app (for example a second video) as an attachment. */
    fun addLocalFile(path: String, mime: String) {
        val f = java.io.File(path)
        val a = Attachment(uri = mediaStore.uriFor(f).toString(), name = f.name, mimeType = mime, sizeBytes = f.length())
        _draft.update { d -> if (d.attachments.any { it.uri == a.uri }) d else d.copy(attachments = d.attachments + a) }
    }

    fun removeAttachment(attachment: Attachment) {
        mediaStore.release(attachment)
        _draft.update { it.copy(attachments = it.attachments.filterNot { a -> a.uri == attachment.uri }) }
    }

    /** An office the person knows about that is not in the directory. */
    fun addCustomAuthority(name: String, email: String, phone: String, level: AuthorityLevel) {
        val a = Authority(
            id = "custom-" + UUID.randomUUID().toString(),
            name = name.trim(),
            category = "Added by you",
            email = email.trim().ifBlank { null },
            phone = phone.trim().ifBlank { null },
            level = level,
            custom = true,
            tier = com.arin.civicreport.model.AuthorityTier.RESPONSIBLE
        )
        _draft.update { d ->
            d.copy(
                customAuthorities = d.customAuthorities + a,
                authorities = (d.authorities + a).sortedWith(compareBy({ it.tier.order }, { it.level.order })),
                selectedAuthorityIds = d.selectedAuthorityIds + a.id
            )
        }
    }

    fun toggleAuthority(id: String) = _draft.update { d ->
        val next =
            if (id in d.selectedAuthorityIds) d.selectedAuthorityIds - id else d.selectedAuthorityIds + id
        d.copy(selectedAuthorityIds = next)
    }

    fun selectAllAuthorities(select: Boolean) = _draft.update { d ->
        d.copy(selectedAuthorityIds = if (select) d.authorities.map { it.id }.toSet() else emptySet())
    }

    fun updateLocation(location: ReportLocation) = _draft.update { it.copy(location = location) }

    fun clearDraft() = _draft.update { DraftState() }

    // ---------- saved photos and videos ----------

    val evidence = container.evidence
    val evidenceItems: StateFlow<List<com.arin.civicreport.data.EvidenceItem>> = evidence.items

    private val _quickFix = MutableStateFlow(ReportLocation())
    /** Location used by the quick-capture screen, which works before any complaint is started. */
    val quickFix: StateFlow<ReportLocation> = _quickFix.asStateFlow()
    private val _quickLocating = MutableStateFlow(false)
    val quickLocating: StateFlow<Boolean> = _quickLocating.asStateFlow()

    fun locateForCapture() {
        if (_quickLocating.value) return
        _quickLocating.value = true
        viewModelScope.launch {
            val pos = locationHelper.currentPosition(settings.value.highAccuracy)
            if (pos != null) {
                _quickFix.value = ReportLocation(pos.latitude, pos.longitude, pos.accuracy)
                _quickFix.value = locationHelper.reverseGeocode(pos.latitude, pos.longitude).copy(accuracy = pos.accuracy)
            }
            _quickLocating.value = false
        }
    }

    suspend fun saveCapturedPhoto(file: java.io.File, loc: ReportLocation) =
        evidence.addPhoto(file, loc, settings.value.saveToGallery)

    suspend fun saveCapturedVideo(file: java.io.File, loc: ReportLocation, startedAt: Long) =
        evidence.addVideo(file, loc, startedAt, settings.value.saveToGallery)

    /**
     * Puts saved photos and videos into the complaint. The place where they were taken
     * becomes the complaint's location, so drafting later at home still reports the right spot.
     * Returns true when the location was taken from the evidence.
     */
    fun useEvidence(items: List<com.arin.civicreport.data.EvidenceItem>): Boolean {
        if (items.isEmpty()) return false
        val photos = items.filter { !it.isVideo }.map { it.path }
        val videos = items.filter { it.isVideo }.map { it.path }
        _draft.update { d ->
            val firstVideo = d.video ?: videos.firstOrNull()
            val extraVideos = videos.filter { it != firstVideo }
                .map { path ->
                    val f = java.io.File(path)
                    Attachment(
                        uri = mediaStore.uriFor(f).toString(),
                        name = f.name,
                        mimeType = "video/mp4",
                        sizeBytes = f.length()
                    )
                }
            val knownAtt = d.attachments.map { it.uri }.toSet()
            d.copy(
                photos = (d.photos + photos).distinct(),
                video = firstVideo,
                attachments = d.attachments + extraVideos.filter { it.uri !in knownAtt }
            )
        }
        val located = items.firstOrNull { it.hasLocation } ?: return false
        val here = _draft.value.location
        val sameSpot = here.hasFix &&
            kotlin.math.abs(here.latitude - located.latitude) < 0.0005 &&
            kotlin.math.abs(here.longitude - located.longitude) < 0.0005
        if (!sameSpot) {
            _draft.update {
                it.copy(location = ReportLocation(located.latitude, located.longitude, located.accuracy, address = located.address))
            }
            refineLocation(located.latitude, located.longitude)
            _draft.update { it.copy(location = it.location.copy(accuracy = located.accuracy)) }
        }
        return true
    }

    fun deleteEvidence(items: List<com.arin.civicreport.data.EvidenceItem>, alsoFromGallery: Boolean) {
        val paths = items.map { it.path }.toSet()
        _draft.update { d ->
            d.copy(
                photos = d.photos.filterNot { it in paths },
                video = d.video?.takeIf { it !in paths },
                attachments = d.attachments.filterNot { a -> paths.any { p -> a.uri.endsWith(java.io.File(p).name) } }
            )
        }
        viewModelScope.launch { evidence.delete(items, alsoFromGallery) }
    }

    // ---------- drafts ----------

    /** Saves whatever has been gathered so far, even without a problem type or offices. */
    suspend fun saveDraft(): Report {
        val d = _draft.value
        val id = d.draftId ?: UUID.randomUUID().toString()
        val existing = reports.value.firstOrNull { it.id == id }
        val channel = settings.value.defaultChannel
        val report = Report(
            id = id,
            types = d.types.toList(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            location = d.location,
            notes = d.notes.trim(),
            mediaUris = d.photos,
            videoUri = d.video,
            attachments = d.attachments,
            authorities = d.authorities.filter { it.id in d.selectedAuthorityIds },
            sendEmail = channel != SendChannel.SMS,
            sendSms = channel != SendChannel.EMAIL,
            status = ReportStatus.DRAFT
        )
        container.reports.upsert(report)
        _draft.update { it.copy(draftId = id) }
        return report
    }

    /** Opens a saved draft so it can be finished and sent. */
    fun loadDraft(report: Report) {
        _draft.value = DraftState(
            types = report.types.toSet(),
            location = report.location,
            notes = report.notes,
            photos = report.mediaUris.filter { java.io.File(it).exists() },
            video = report.videoUri?.takeIf { java.io.File(it).exists() },
            attachments = report.attachments,
            customAuthorities = report.authorities.filter { it.custom },
            selectedAuthorityIds = report.authorities.map { it.id }.toSet(),
            draftId = report.id
        )
        if (report.types.isNotEmpty() && report.location.hasFix) runAuthoritySearch()
    }

    fun clearSendResult() {
        _sendResult.value = null
    }

    // ---------- location ----------

    fun refreshLocationAndAuthorities() {
        if (_draft.value.types.isEmpty()) return
        if (_draft.value.location.hasFix) {
            searchAuthorities()
            return
        }
        _draft.update { it.copy(locating = true, locationError = null) }
        viewModelScope.launch {
            val setting = settings.value
            val pos = locationHelper.currentPosition(setting.highAccuracy)
            if (pos == null) {
                _draft.update {
                    it.copy(
                        locating = false,
                        locationError = "Could not get a GPS fix. Turn on location and try again."
                    )
                }
                return@launch
            }
            val geocoded = locationHelper.reverseGeocode(pos.latitude, pos.longitude)
                .copy(accuracy = pos.accuracy)
            _draft.update { it.copy(location = geocoded, locating = false, locationError = null) }
            runAuthoritySearch()
        }
    }

    /** Moves the pin and re-detects the jurisdiction and authorities. */
    fun refineLocation(latitude: Double, longitude: Double) {
        _draft.update {
            it.copy(
                locating = true,
                location = it.location.copy(latitude = latitude, longitude = longitude)
            )
        }
        viewModelScope.launch {
            val geocoded = locationHelper.reverseGeocode(latitude, longitude)
            _draft.update { it.copy(location = geocoded, locating = false) }
            runAuthoritySearch()
        }
    }

    fun searchAuthorities() {
        viewModelScope.launch { runAuthoritySearch() }
    }

    private suspend fun runAuthoritySearch() {
        val d = _draft.value
        if (d.types.isEmpty()) return
        _draft.update { it.copy(searchingAuthorities = true, authorities = emptyList()) }
        val result = authorityFinder.find(
            types = d.types.toList(),
            location = d.location,
            includeNational = settings.value.includeNationalAuthorities
        )
        val found = (result.authorities + _draft.value.customAuthorities)
            .sortedWith(compareBy({ it.tier.order }, { it.level.order }))
        _draft.update {
            it.copy(
                authorities = found,
                selectedAuthorityIds = found.filter { a -> a.defaultSelected || a.custom }.map { a -> a.id }.toSet(),
                matchedDistrict = result.matchedDistrict,
                matchedState = result.matchedState,
                directoryDate = result.verifiedOn,
                searchingAuthorities = false,
                loadError = if (found.isEmpty()) {
                    "No office matched this place. Move the pin or add an office yourself."
                } else null
            )
        }
    }

    fun detectActivitySuggestion(): List<IncidentType> = detector.suggestedTypes()

    fun rolesFor(type: IncidentType) = AuthorityRules.rolesFor(type)

    // ---------- sending ----------

    fun buildReport(): Report? {
        val d = _draft.value
        if (d.types.isEmpty() || !d.location.hasFix) return null
        val selected = d.authorities.filter { it.id in d.selectedAuthorityIds }
        val channel = settings.value.defaultChannel
        return Report(
            id = d.draftId ?: UUID.randomUUID().toString(),
            types = d.types.toList(),
            createdAt = System.currentTimeMillis(),
            location = d.location,
            notes = d.notes.trim(),
            mediaUris = d.photos,
            videoUri = d.video,
            attachments = d.attachments,
            authorities = selected,
            sendEmail = channel != SendChannel.SMS,
            sendSms = channel != SendChannel.EMAIL
        )
    }

    val autoSender = container.autoSender
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private val _mailAccount = MutableStateFlow(autoSender.accounts.load())
    val mailAccount: StateFlow<com.arin.civicreport.data.MailAccount> = _mailAccount.asStateFlow()

    fun saveMailAccount(a: com.arin.civicreport.data.MailAccount) {
        autoSender.accounts.save(a)
        _mailAccount.value = autoSender.accounts.load()
    }

    fun clearMailAccount() {
        autoSender.accounts.clear()
        _mailAccount.value = autoSender.accounts.load()
    }

    /** First step of "Continue with Google": either access is already there, or Google's approval screen must be shown. */
    suspend fun startGoogleSignIn(): com.arin.civicreport.data.GoogleMailer.Auth = autoSender.google.authorize()

    /** Finishes sign-in with the token; returns an error message, or null when the account is saved. */
    suspend fun finishGoogleSignIn(auth: com.arin.civicreport.data.GoogleMailer.Auth): String? = when (auth) {
        is com.arin.civicreport.data.GoogleMailer.Auth.Granted -> {
            val email = autoSender.google.emailFor(auth.token)
            if (email == null) "Signed in, but could not read the Gmail address. Check your internet and try again."
            else {
                autoSender.accounts.saveGoogle(email)
                _mailAccount.value = autoSender.accounts.load()
                null
            }
        }
        is com.arin.civicreport.data.GoogleMailer.Auth.Failed -> auth.message
        is com.arin.civicreport.data.GoogleMailer.Auth.NeedsApproval -> "Approval is still needed."
    }

    fun googleResult(data: android.content.Intent?) = autoSender.google.resultFrom(data)

    suspend fun testMailAccount(a: com.arin.civicreport.data.MailAccount): String? =
        autoSender.test(a.copy(password = a.password.replace(" ", "")))

    /**
     * Sends the complaint by itself: email from the person's own mailbox and SMS from the SIM.
     * Nothing else opens. Returns true if at least one office was reached.
     */
    suspend fun sendCurrent(report: Report, subject: String, body: String, channel: SendChannel): Boolean {
        val wantsEmail = channel != SendChannel.SMS
        val wantsSms = channel != SendChannel.EMAIL
        val lines = mutableListOf<String>()
        var mail = com.arin.civicreport.data.AutoSender.Outcome()
        if (wantsEmail) {
            mail = autoSender.sendEmail(report, subject, body, settings.value.senderName) { _progress.value = it }
            if (mail.emailedTo.isNotEmpty()) {
                lines += "Email sent to ${mail.emailedTo.size} office(s)" +
                    (if (mail.emailParts > 1) " in ${mail.emailParts} parts" else "") + "."
                if (mail.skippedFiles.isNotEmpty()) lines += "Too large to email: ${mail.skippedFiles.joinToString()}."
            } else mail.error?.let { lines += "Email not sent: $it" }
        }
        var sms = com.arin.civicreport.data.AutoSender.Outcome()
        if (wantsSms) {
            val text = autoSender.smsText(report, mail.emailedTo.isNotEmpty(), _mailAccount.value.email)
            sms = autoSender.sendSms(report, text) { _progress.value = it }
            when {
                sms.smsSentTo.isNotEmpty() -> lines += "SMS sent to ${sms.smsSentTo.size} number(s)." +
                    if (sms.smsFailed.isNotEmpty()) " ${sms.smsFailed.size} failed." else ""
                sms.error != null -> lines += "SMS not sent: ${sms.error}"
                sms.smsFailed.isNotEmpty() -> lines += "SMS failed. Check balance and signal."
                !wantsEmail -> lines += "None of the selected offices has a mobile number for SMS."
            }
        }
        _progress.value = null
        val ok = mail.anySent || sms.anySent
        val channels = buildList {
            if (mail.anySent) add("EMAIL")
            if (sms.anySent) add("SMS")
        }
        if (ok) {
            container.reports.upsert(
                report.copy(
                    status = ReportStatus.SENT,
                    lastSentAt = System.currentTimeMillis(),
                    sentChannels = channels
                )
            )
            if (settings.value.notificationsEnabled) notifications.notifyReportSent(report.title, report.authorities.size)
        }
        _sendResult.value = lines.joinToString(" ").ifBlank { "Nothing was sent." }
        return ok
    }

    fun shareAnywhere(report: Report, subject: String, body: String): Boolean =
        sendHelper.shareAnywhere(report, subject, body)

    suspend fun persist(report: Report) = container.reports.upsert(report)

    suspend fun resend(report: Report): Boolean {
        val channel = when {
            report.sendEmail && report.sendSms -> SendChannel.BOTH
            report.sendSms -> SendChannel.SMS
            else -> SendChannel.EMAIL
        }
        val subject = "Complaint: ${report.title}"
        val body = sendHelper.buildBody(report)
        return sendCurrent(report, subject, body, channel)
    }

    fun deleteReport(report: Report) {
        // Photos and videos in "Saved photos & videos" are kept; delete them there.
        report.mediaUris.filterNot { evidence.contains(it) }.forEach { mediaStore.deleteFile(it) }
        report.videoUri?.takeIf { !evidence.contains(it) }?.let { mediaStore.deleteFile(it) }
        report.attachments.forEach { mediaStore.release(it) }
        viewModelScope.launch { container.reports.delete(report.id) }
    }

    // ---------- settings ----------

    fun saveSettings(next: AppSettings) {
        viewModelScope.launch { settingsStore.update { next } }
    }

    fun previewAuthorities(): List<Authority> {
        val d = _draft.value
        if (d.types.isEmpty()) return emptyList()
        return authorityFinder.findAuthorities(d.types.toList(), d.location, true)
    }
}
