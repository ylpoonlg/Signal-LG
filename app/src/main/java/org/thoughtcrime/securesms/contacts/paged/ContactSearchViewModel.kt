package org.thoughtcrime.securesms.contacts.paged

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.signal.paging.LivePagedData
import org.signal.paging.PagedData
import org.signal.paging.PagingConfig
import org.signal.paging.PagingController
import org.thoughtcrime.securesms.groups.SelectionLimits
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.livedata.Store
import org.whispersystems.signalservice.api.util.Preconditions

/**
 * Simple, reusable view model that manages a ContactSearchPagedDataSource as well as filter and expansion state.
 */
class ContactSearchViewModel(
  private val selectionLimits: SelectionLimits,
  private val contactSearchRepository: ContactSearchRepository
) : ViewModel() {

  private val disposables = CompositeDisposable()

  private val pagingConfig = PagingConfig.Builder()
    .setBufferPages(1)
    .setPageSize(20)
    .setStartIndex(0)
    .build()

  private val pagedData = MutableLiveData<LivePagedData<ContactSearchKey, ContactSearchData>>()
  private val configurationStore = Store(ContactSearchState())
  private val selectionStore = Store<Set<ContactSearchKey>>(emptySet())

  val controller: LiveData<PagingController<ContactSearchKey>> = Transformations.map(pagedData) { it.controller }
  val data: LiveData<List<ContactSearchData>> = Transformations.switchMap(pagedData) { it.data }
  val configurationState: LiveData<ContactSearchState> = configurationStore.stateLiveData
  val selectionState: LiveData<Set<ContactSearchKey>> = selectionStore.stateLiveData

  override fun onCleared() {
    disposables.clear()
  }

  fun setConfiguration(contactSearchConfiguration: ContactSearchConfiguration) {
    val pagedDataSource = ContactSearchPagedDataSource(contactSearchConfiguration)
    pagedData.value = PagedData.createForLiveData(pagedDataSource, pagingConfig)
  }

  fun setQuery(query: String?) {
    configurationStore.update { it.copy(query = query) }
  }

  fun expandSection(sectionKey: ContactSearchConfiguration.SectionKey) {
    configurationStore.update { it.copy(expandedSections = it.expandedSections + sectionKey) }
  }

  fun setKeysSelected(contactSearchKeys: Set<ContactSearchKey>) {
    disposables += contactSearchRepository.filterOutUnselectableContactSearchKeys(contactSearchKeys).subscribe { results ->
      if (results.any { !it.isSelectable }) {
        // TODO [alex] -- Pop an error.
        return@subscribe
      }

      val newSelectionEntries = results.filter { it.isSelectable }.map { it.key } - getSelectedContacts()
      val newSelectionSize = newSelectionEntries.size + getSelectedContacts().size

      if (selectionLimits.hasRecommendedLimit() && getSelectedContacts().size < selectionLimits.recommendedLimit && newSelectionSize >= selectionLimits.recommendedLimit) {
        // Pop a warning
      } else if (selectionLimits.hasHardLimit() && newSelectionSize > selectionLimits.hardLimit) {
        // Pop an error
        return@subscribe
      }

      selectionStore.update { state -> state + newSelectionEntries }
    }
  }

  fun setKeysNotSelected(contactSearchKeys: Set<ContactSearchKey>) {
    selectionStore.update { it - contactSearchKeys }
  }

  fun getSelectedContacts(): Set<ContactSearchKey> {
    return selectionStore.state
  }

  fun addToVisibleGroupStories(groupStories: Set<ContactSearchKey.RecipientSearchKey.Story>) {
    configurationStore.update { state ->
      state.copy(
        groupStories = state.groupStories + groupStories.map {
          val recipient = Recipient.resolved(it.recipientId)
          ContactSearchData.Story(recipient, recipient.participants.size)
        }
      )
    }
  }

  fun removeGroupStory(story: ContactSearchData.Story) {
    Preconditions.checkArgument(story.recipient.isGroup)
    setKeysNotSelected(setOf(story.contactSearchKey))
    disposables += contactSearchRepository.unmarkDisplayAsStory(story.recipient.requireGroupId()).subscribe {
      configurationStore.update { state ->
        state.copy(
          groupStories = state.groupStories.filter { it.recipient.id == story.recipient.id }.toSet()
        )
      }
      refresh()
    }
  }

  fun deletePrivateStory(story: ContactSearchData.Story) {
    Preconditions.checkArgument(story.recipient.isDistributionList && !story.recipient.isMyStory)
    setKeysNotSelected(setOf(story.contactSearchKey))
    disposables += contactSearchRepository.deletePrivateStory(story.recipient.requireDistributionListId()).subscribe {
      refresh()
    }
  }

  fun refresh() {
    controller.value?.onDataInvalidated()
  }

  class Factory(private val selectionLimits: SelectionLimits, private val repository: ContactSearchRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(ContactSearchViewModel(selectionLimits, repository)) as T
    }
  }
}
