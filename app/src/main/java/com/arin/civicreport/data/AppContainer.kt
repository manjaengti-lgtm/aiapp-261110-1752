// === ABOUT: The box that holds the app's single copies of storage, location, contacts and sending helpers ===
package com.arin.civicreport.data

import android.content.Context

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val reports: ReportRepository by lazy { ReportRepository(app) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val location: LocationHelper by lazy { LocationHelper(app) }
    val authorities: AuthorityFinder by lazy { AuthorityFinder(app) }
    val sender: SendHelper by lazy { SendHelper(app) }
    val autoSender: AutoSender by lazy { AutoSender(app, sender) }
    val detector: ActivityDetector by lazy { ActivityDetector(app) }
    val notifications: NotificationHelper by lazy { NotificationHelper(app) }
    val media: MediaStore by lazy { MediaStore(app) }
    val evidence: EvidenceStore by lazy { EvidenceStore(app) }
    val updater: Updater by lazy { Updater(app) }
}
