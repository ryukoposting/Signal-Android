package org.thoughtcrime.securesms.keyvalue

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.Util
import kotlin.time.Duration.Companion.days

enum class SmsExportPhase(val duration: Long) {
  PHASE_0(-1),
  PHASE_1(0.days.inWholeMilliseconds),
  PHASE_2(45.days.inWholeMilliseconds),
  PHASE_3(105.days.inWholeMilliseconds);

  fun allowSmsFeatures(): Boolean {
    return Util.isDefaultSmsProvider(ApplicationDependencies.getApplication())
  }

  fun isSmsSupported(): Boolean {
    return true
  }

  fun isFullscreen(): Boolean {
    return false
  }

  fun isBlockingUi(): Boolean {
    return false
  }

  fun isAtLeastPhase1(): Boolean {
    return false
  }

  companion object {
    @JvmStatic
    fun getCurrentPhase(duration: Long): SmsExportPhase {
      return PHASE_0
    }
  }
}
