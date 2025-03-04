package org.thoughtcrime.securesms.messages

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.AliceClient
import org.thoughtcrime.securesms.testing.BobClient
import org.thoughtcrime.securesms.testing.Entry
import org.thoughtcrime.securesms.testing.FakeClientHelpers
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.testing.awaitFor
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import android.util.Log as AndroidLog

/**
 * Sends N messages from Bob to Alice to track performance of Alice's processing of messages.
 */
@Ignore("Ignore test in normal testing as it's a performance test with no assertions")
@RunWith(AndroidJUnit4::class)
class MessageProcessingPerformanceTest {

  companion object {
    private val TAG = Log.tag(MessageProcessingPerformanceTest::class.java)
    private val TIMING_TAG = "TIMING_$TAG".substring(0..23)
  }

  @get:Rule
  val harness = SignalActivityRule()

  private val trustRoot: ECKeyPair = Curve.generateKeyPair()

  @Before
  fun setup() {
    mockkStatic(UnidentifiedAccessUtil::class)
    every { UnidentifiedAccessUtil.getCertificateValidator() } returns FakeClientHelpers.noOpCertificateValidator

    mockkStatic(MessageContentProcessor::class)
    every { MessageContentProcessor.create(harness.application) } returns TimingMessageContentProcessor(harness.application)
  }

  @After
  fun after() {
    unmockkStatic(UnidentifiedAccessUtil::class)
    unmockkStatic(MessageContentProcessor::class)
  }

  @Test
  fun testPerformance() {
    val aliceClient = AliceClient(
      serviceId = harness.self.requireServiceId(),
      e164 = harness.self.requireE164(),
      trustRoot = trustRoot
    )

    val bob = Recipient.resolved(harness.others[0])
    val bobClient = BobClient(
      serviceId = bob.requireServiceId(),
      e164 = bob.requireE164(),
      identityKeyPair = harness.othersKeys[0],
      trustRoot = trustRoot,
      profileKey = ProfileKey(bob.profileKey)
    )

    // Send message from Bob to Alice (self)

    val firstPreKeyMessageTimestamp = System.currentTimeMillis()
    val encryptedEnvelope = bobClient.encrypt(firstPreKeyMessageTimestamp)

    val aliceProcessFirstMessageLatch = harness
      .inMemoryLogger
      .getLockForUntil(TimingMessageContentProcessor.endTagPredicate(firstPreKeyMessageTimestamp))

    Thread { aliceClient.process(encryptedEnvelope, System.currentTimeMillis()) }.start()
    aliceProcessFirstMessageLatch.awaitFor(15.seconds)

    // Send message from Alice to Bob
    val aliceNow = System.currentTimeMillis()
    bobClient.decrypt(aliceClient.encrypt(aliceNow, bob), aliceNow)

    // Build N messages from Bob to Alice

    val messageCount = 100
    val envelopes = ArrayList<Envelope>(messageCount)
    var now = System.currentTimeMillis()
    for (i in 0..messageCount) {
      envelopes += bobClient.encrypt(now)
      now += 3
    }

    val firstTimestamp = envelopes.first().timestamp
    val lastTimestamp = envelopes.last().timestamp

    // Alice processes N messages

    val aliceProcessLastMessageLatch = harness
      .inMemoryLogger
      .getLockForUntil(TimingMessageContentProcessor.endTagPredicate(lastTimestamp))

    Thread {
      for (envelope in envelopes) {
        Log.i(TIMING_TAG, "Retrieved envelope! ${envelope.timestamp}")
        aliceClient.process(envelope, envelope.timestamp)
      }
    }.start()

    // Wait for Alice to finish processing messages
    aliceProcessLastMessageLatch.awaitFor(1.minutes)
    harness.inMemoryLogger.flush()

    // Process logs for timing data
    val entries = harness.inMemoryLogger.entries()

    // Calculate decryption average

    val decrypts = entries
      .filter { it.tag == AliceClient.TAG }
      .drop(1)

    val totalDecryptDuration = decrypts.sumOf { it.message!!.toLong() }

    AndroidLog.w(TAG, "Decryption: Average runtime: ${totalDecryptDuration.toFloat() / decrypts.size.toFloat()}ms")

    // Calculate MessageContentProcessor

    val takeLast: List<Entry> = entries.filter { it.tag == TimingMessageContentProcessor.TAG }.drop(2)
    val iterator = takeLast.iterator()
    var processCount = 0L
    var processDuration = 0L
    while (iterator.hasNext()) {
      val start = iterator.next()
      val end = iterator.next()
      processCount++
      processDuration += end.timestamp - start.timestamp
    }

    AndroidLog.w(TAG, "MessageContentProcessor.process: Average runtime: ${processDuration.toFloat() / processCount.toFloat()}ms")

    // Calculate messages per second from "retrieving" first message post session initialization to processing last message

    val start = entries.first { it.message == "Retrieved envelope! $firstTimestamp" }
    val end = entries.first { it.message == TimingMessageContentProcessor.endTag(lastTimestamp) }

    val duration = (end.timestamp - start.timestamp).toFloat() / 1000f
    val messagePerSecond = messageCount.toFloat() / duration

    AndroidLog.w(TAG, "Processing $messageCount messages took ${duration}s or ${messagePerSecond}m/s")
  }
}
