package org.thoughtcrime.securesms.mediasend;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.ZoomState;
import androidx.camera.video.FileDescriptorOutputOptions;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.CameraController;
import androidx.camera.view.PreviewView;
import androidx.camera.view.video.AudioConfig;
import androidx.core.util.Consumer;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.util.Executors;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.mediasend.camerax.CameraXModePolicy;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.ContextUtil;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.MemoryFileDescriptor;
import org.thoughtcrime.securesms.video.VideoUtil;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@RequiresApi(26)
class CameraXVideoCaptureHelper implements CameraButtonView.VideoCaptureListener {

  private static final String TAG               = CameraXVideoCaptureHelper.class.getName();
  private static final String VIDEO_DEBUG_LABEL = "video-capture";
  private static final long   VIDEO_SIZE        = 10 * 1024 * 1024;

  private final @NonNull Fragment             fragment;
  private final @NonNull PreviewView          previewView;
  private final @NonNull CameraController     cameraController;
  private final @NonNull Callback             callback;
  private final @NonNull MemoryFileDescriptor memoryFileDescriptor;
  private final @NonNull ValueAnimator        updateProgressAnimator;
  private final @NonNull Debouncer            debouncer;
  private final @NonNull CameraXModePolicy    cameraXModePolicy;

  private ValueAnimator cameraMetricsAnimator;

  private @Nullable Recording activeRecording = null;

  private final Consumer<VideoRecordEvent> videoSavedListener = new Consumer<>() {
    @Override
    public void accept(VideoRecordEvent videoRecordEvent) {
      Log.d(TAG, "Received recording event: " + videoRecordEvent.getClass().getSimpleName());

      if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
        VideoRecordEvent.Finalize event = (VideoRecordEvent.Finalize) videoRecordEvent;

        if (event.hasError()) {
          Log.w(TAG, "Hit an error while recording! Error code: " + event.getError(), event.getCause());
          debouncer.clear();
          callback.onVideoError(event.getCause());
        } else {
          try {
            debouncer.clear();
            cameraController.setZoomRatio(Objects.requireNonNull(cameraController.getZoomState().getValue()).getMinZoomRatio());
            memoryFileDescriptor.seek(0);
            callback.onVideoSaved(memoryFileDescriptor.getFileDescriptor());
          } catch (IOException e) {
            callback.onVideoError(e);
          }
        }
      }
    }
  };

  CameraXVideoCaptureHelper(@NonNull Fragment fragment,
                            @NonNull CameraButtonView captureButton,
                            @NonNull CameraController cameraController,
                            @NonNull PreviewView previewView,
                            @NonNull MemoryFileDescriptor memoryFileDescriptor,
                            @NonNull CameraXModePolicy cameraXModePolicy,
                            int maxVideoDurationSec,
                            @NonNull Callback callback)
  {
    this.fragment               = fragment;
    this.cameraController       = cameraController;
    this.previewView            = previewView;
    this.memoryFileDescriptor   = memoryFileDescriptor;
    this.callback               = callback;

    float animationScale = ContextUtil.getAnimationScale(fragment.requireContext());
    long  baseDuration   = TimeUnit.SECONDS.toMillis(maxVideoDurationSec);
    long  scaledDuration = Math.round(animationScale > 0f ? (baseDuration * (1f / animationScale)) : baseDuration);

    this.updateProgressAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(scaledDuration);
    this.debouncer              = new Debouncer(TimeUnit.SECONDS.toMillis(maxVideoDurationSec));
    this.cameraXModePolicy      = cameraXModePolicy;

    updateProgressAnimator.setInterpolator(new LinearInterpolator());
    updateProgressAnimator.addUpdateListener(anim -> {
      captureButton.setProgress(anim.getAnimatedFraction());
    });
  }

  @Override
  public void onVideoCaptureStarted() {
    Log.d(TAG, "onVideoCaptureStarted");

    if (canRecordAudio()) {
      beginCameraRecording();
    } else {
      displayAudioRecordingPermissionsDialog();
    }
  }

  private boolean canRecordAudio() {
    return Permissions.hasAll(fragment.requireContext(), Manifest.permission.RECORD_AUDIO);
  }

  private void displayAudioRecordingPermissionsDialog() {
    Permissions.with(fragment)
               .request(Manifest.permission.RECORD_AUDIO)
               .ifNecessary()
               .withRationaleDialog(fragment.getString(R.string.ConversationActivity_enable_the_microphone_permission_to_capture_videos_with_sound), R.drawable.ic_mic_solid_24)
               .withPermanentDenialDialog(fragment.getString(R.string.ConversationActivity_signal_needs_the_recording_permissions_to_capture_video))
               .onAnyDenied(() -> Toast.makeText(fragment.requireContext(), R.string.ConversationActivity_signal_needs_recording_permissions_to_capture_video, Toast.LENGTH_LONG).show())
               .execute();
  }

  @SuppressLint("RestrictedApi")
  private void beginCameraRecording() {
    cameraXModePolicy.setToVideo(cameraController);
    this.cameraController.setZoomRatio(Objects.requireNonNull(this.cameraController.getZoomState().getValue()).getMinZoomRatio());
    callback.onVideoRecordStarted();
    shrinkCaptureArea();

    FileDescriptorOutputOptions outputOptions = new FileDescriptorOutputOptions.Builder(memoryFileDescriptor.getParcelFileDescriptor()).build();
    AudioConfig                 audioConfig   = AudioConfig.create(true);

    activeRecording = cameraController.startRecording(outputOptions, audioConfig, Executors.mainThreadExecutor(), videoSavedListener);

    updateProgressAnimator.start();
    debouncer.publish(this::onVideoCaptureComplete);
  }

  private void shrinkCaptureArea() {
    Size  screenSize              = getScreenSize();
    Size  videoRecordingSize      = VideoUtil.getVideoRecordingSize();
    float scale                   = getSurfaceScaleForRecording();
    float targetWidthForAnimation = videoRecordingSize.getWidth() * scale;
    float scaleX                  = targetWidthForAnimation / screenSize.getWidth();

    if (scaleX == 1f) {
      float targetHeightForAnimation = videoRecordingSize.getHeight() * scale;

      if (screenSize.getHeight() == targetHeightForAnimation) {
        return;
      }

      cameraMetricsAnimator = ValueAnimator.ofFloat(screenSize.getHeight(), targetHeightForAnimation);
    } else {

      if (screenSize.getWidth() == targetWidthForAnimation) {
        return;
      }

      cameraMetricsAnimator = ValueAnimator.ofFloat(screenSize.getWidth(), targetWidthForAnimation);
    }

    ViewGroup.LayoutParams params = previewView.getLayoutParams();
    cameraMetricsAnimator.setInterpolator(new LinearInterpolator());
    cameraMetricsAnimator.setDuration(200);
    cameraMetricsAnimator.addUpdateListener(animation -> {
      if (scaleX == 1f) {
        params.height = Math.round((float) animation.getAnimatedValue());
      } else {
        params.width = Math.round((float) animation.getAnimatedValue());
      }
      previewView.setLayoutParams(params);
    });
    cameraMetricsAnimator.start();
  }

  private Size getScreenSize() {
    DisplayMetrics metrics = previewView.getResources().getDisplayMetrics();
    return new Size(metrics.widthPixels, metrics.heightPixels);
  }

  private float getSurfaceScaleForRecording() {
    Size videoRecordingSize = VideoUtil.getVideoRecordingSize();
    Size screenSize         = getScreenSize();
    return Math.min(screenSize.getHeight(), screenSize.getWidth()) / (float) Math.min(videoRecordingSize.getHeight(), videoRecordingSize.getWidth());
  }

  @Override
  public void onVideoCaptureComplete() {
    if (!canRecordAudio()) {
      Log.w(TAG, "Can't record audio!");
      return;
    }

    if (activeRecording == null) {
      Log.w(TAG, "No active recording!");
      return;
    }

    Log.d(TAG, "onVideoCaptureComplete");
    activeRecording.close();
    activeRecording = null;

    if (cameraMetricsAnimator != null && cameraMetricsAnimator.isRunning()) {
      cameraMetricsAnimator.reverse();
    }

    updateProgressAnimator.cancel();
    debouncer.clear();
    cameraXModePolicy.setToImage(cameraController);
  }

  @Override
  public void onZoomIncremented(float increment) {
    ZoomState zoomState = Objects.requireNonNull(cameraController.getZoomState().getValue());
    float range = zoomState.getMaxZoomRatio() - zoomState.getMinZoomRatio();
    cameraController.setZoomRatio((range * increment) + zoomState.getMinZoomRatio());
  }

  @Override
  protected void finalize() throws Throwable {
    if (activeRecording != null) {
      Log.w(TAG, "Dangling recording left open in finalize()! Attempting to close.");
      activeRecording.close();
    }

    super.finalize();
  }

  static MemoryFileDescriptor createFileDescriptor(@NonNull Context context) throws MemoryFileDescriptor.MemoryFileException {
    return MemoryFileDescriptor.newMemoryFileDescriptor(
        context,
        VIDEO_DEBUG_LABEL,
        VIDEO_SIZE
    );
  }

  interface Callback {
    void onVideoRecordStarted();

    void onVideoSaved(@NonNull FileDescriptor fd);

    void onVideoError(@Nullable Throwable cause);
  }
}
