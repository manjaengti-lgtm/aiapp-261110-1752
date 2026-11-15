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
    val loadError: String? = null
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
        mediaStore.deleteFile(path)
        _draft.update { it.copy(photos = it.photos.filterNot { p -> p == path }) }
    }

    fun setVideo(path: String?) {
        _draft.update { d ->
            d.video?.let { old -> if (old != path) mediaStore.deleteFile(old) }
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
            id = UUID.randomUUID().toString(),
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

    fun sendCurrent(report: Report, subject: String, body: String, channel: SendChannel): Boolean {
        val wantsEmail = channel != SendChannel.SMS
        var ok = false
        var message: String
        if (wantsEmail) {
            ok = sendHelper.sendEmail(report, subject, body)
            message = when (sendHelper.lastEmailResult) {
                com.arin.civicreport.data.SendHelper.EmailResult.OPENED ->
                    "Email app opened with ${sendHelper.emailsOf(report.authorities).size} address(es) and your files. Press Send there."
                com.arin.civicreport.data.SendHelper.EmailResult.OPENED_WITHOUT_FILES ->
                    "Your email app would not take the files, so only the text was added. Use \"Share via any app\" for the files."
                com.arin.civicreport.data.SendHelper.EmailResult.NO_RECIPIENTS ->
                    "None of the selected offices has an email address. Use SMS, phone, or their portal."
                com.arin.civicreport.data.SendHelper.EmailResult.NO_EMAIL_APP ->
                    "No email app is set up on this phone. Install or sign in to Gmail, then try again."
            }
            // With email + SMS, SMS is sent with its own button so the two apps don't cover each other.
            if (!ok && channel == SendChannel.BOTH && sendHelper.sendSms(report, body)) {
                ok = true
                message = "$message SMS app opened instead."
            }
        } else {
            ok = sendHelper.sendSms(report, body)
            message = if (ok) "SMS app opened. Press Send there."
            else "None of the selected offices has a mobile number for SMS."
        }
        if (ok && settings.value.notificationsEnabled) {
            notifications.notifyReportSent(report.title, report.authorities.size)
        }
        _sendResult.value = message
        return ok
    }

    fun sendSmsOnly(report: Report, body: String): Boolean {
        val ok = sendHelper.sendSms(report, body)
        _sendResult.value = if (ok) "SMS app opened. Press Send there."
        else "None of the selected offices has a mobile number for SMS."
        return ok
    }

    fun shareAnywhere(report: Report, subject: String, body: String): Boolean =
        sendHelper.shareAnywhere(report, subject, body)

    suspend fun persist(report: Report) = container.reports.upsert(report)

    fun resend(report: Report): Boolean {
        val channel = when {
            report.sendEmail && report.sendSms -> SendChannel.BOTH
            report.sendSms -> SendChannel.SMS
            else -> SendChannel.EMAIL
        }
        val subject = "Complaint: ${report.title}"
        val body = sendHelper.buildBody(report)
        val ok = sendCurrent(report, subject, body, channel)
        if (ok) {
            viewModelScope.launch {
                container.reports.upsert(
                    report.copy(status = ReportStatus.SENT, lastSentAt = System.currentTimeMillis())
                )
            }
        }
        return ok
    }

    fun deleteReport(report: Report) {
        report.mediaUris.forEach { mediaStore.deleteFile(it) }
        report.videoUri?.let { mediaStore.deleteFile(it) }
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
