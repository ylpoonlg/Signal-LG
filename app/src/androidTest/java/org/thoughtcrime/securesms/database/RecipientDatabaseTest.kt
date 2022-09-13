package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.core.util.CursorUtil
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.SignalActivityRule

@RunWith(AndroidJUnit4::class)
class RecipientDatabaseTest {

  @get:Rule
  val harness = SignalActivityRule()

  @Test
  fun givenABlockedRecipient_whenIQueryAllContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    SignalDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    SignalDatabase.recipients.setBlocked(blockedRecipient, true)

    val results = SignalDatabase.recipients.queryAllContacts("Blocked")!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenABlockedRecipient_whenIGetSignalContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    SignalDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    SignalDatabase.recipients.setBlocked(blockedRecipient, true)

    val results: MutableList<RecipientId> = SignalDatabase.recipients.getSignalContacts(false)?.use {
      val ids = mutableListOf<RecipientId>()
      while (it.moveToNext()) {
        ids.add(RecipientId.from(CursorUtil.requireLong(it, RecipientDatabase.ID)))
      }

      ids
    }!!

    assertNotEquals(0, results.size)
    assertFalse(blockedRecipient in results)
  }

  @Test
  fun givenABlockedRecipient_whenIQuerySignalContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    SignalDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    SignalDatabase.recipients.setBlocked(blockedRecipient, true)

    val results = SignalDatabase.recipients.querySignalContacts("Blocked", false)!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenABlockedRecipient_whenIQueryNonGroupContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    SignalDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    SignalDatabase.recipients.setBlocked(blockedRecipient, true)

    val results = SignalDatabase.recipients.queryNonGroupContacts("Blocked", false)!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenABlockedRecipient_whenIGetNonGroupContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    SignalDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    SignalDatabase.recipients.setBlocked(blockedRecipient, true)

    val results: MutableList<RecipientId> = SignalDatabase.recipients.getNonGroupContacts(false)?.use {
      val ids = mutableListOf<RecipientId>()
      while (it.moveToNext()) {
        ids.add(RecipientId.from(CursorUtil.requireLong(it, RecipientDatabase.ID)))
      }

      ids
    }!!

    assertNotEquals(0, results.size)
    assertFalse(blockedRecipient in results)
  }
}
