package org.thoughtcrime.securesms.mediasend.v2.capture

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Flowable
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.util.SingleLiveEvent
import org.thoughtcrime.securesms.util.rx.RxStore
import java.io.FileDescriptor
import java.util.Optional

class MediaCaptureViewModel(private val repository: MediaCaptureRepository) : ViewModel() {

  private val store: RxStore<MediaCaptureState> = RxStore(MediaCaptureState())

  private val internalEvents: SingleLiveEvent<MediaCaptureEvent> = SingleLiveEvent()

  val events: LiveData<MediaCaptureEvent> = internalEvents

  init {
    repository.getMostRecentItem { media ->
      store.update { state ->
        state.copy(mostRecentMedia = media)
      }
    }
  }

  fun onImageCaptured(data: ByteArray, width: Int, height: Int) {
    repository.renderImageToMedia(data, width, height, this::onMediaRendered, this::onMediaRenderFailed)
  }

  fun onVideoCaptured(fd: FileDescriptor) {
    repository.renderVideoToMedia(fd, this::onMediaRendered, this::onMediaRenderFailed)
  }

  fun getMostRecentMedia(): Flowable<Optional<Media>> {
    return store.stateFlowable.map { Optional.ofNullable(it.mostRecentMedia) }
  }

  private fun onMediaRendered(media: Media) {
    internalEvents.postValue(MediaCaptureEvent.MediaCaptureRendered(media))
  }

  private fun onMediaRenderFailed() {
    internalEvents.postValue(MediaCaptureEvent.MediaCaptureRenderFailed)
  }

  class Factory(private val repository: MediaCaptureRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(MediaCaptureViewModel(repository)))
    }
  }
}
