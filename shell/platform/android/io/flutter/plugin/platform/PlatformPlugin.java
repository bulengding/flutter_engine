// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import android.app.Activity;
import android.app.ActivityManager.TaskDescription;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.activity.OnBackPressedDispatcherOwner;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import io.flutter.Log;
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import java.io.FileNotFoundException;
import java.util.List;

/** Android implementation of the platform plugin. */
public class PlatformPlugin {

  private final Activity activity;
  private final PlatformChannel platformChannel;
  private final PlatformPluginDelegate platformPluginDelegate;
  private PlatformChannel.SystemChromeStyle currentTheme;
  private PlatformChannel.SystemUiMode currentSystemUiMode;
  private List<PlatformChannel.SystemUiOverlay> currentOverlays;
  private static final String TAG = "PlatformPlugin";

  /**
   * The {@link PlatformPlugin} generally has default behaviors implemented for platform
   * functionalities requested by the Flutter framework. However, functionalities exposed through
   * this interface could be customized by the more public-facing APIs that implement this interface
   * such as the {@link io.flutter.embedding.android.FlutterActivity} or the {@link
   * io.flutter.embedding.android.FlutterFragment}.
   */
  public interface PlatformPluginDelegate {
    /**
     * Allow implementer to customize the behavior needed when the Flutter framework calls to pop
     * the Android-side navigation stack.
     *
     * @return true if the implementation consumed the pop signal. If false, a default behavior of
     *     finishing the activity or sending the signal to {@link
     *     androidx.activity.OnBackPressedDispatcher} will be executed.
     */
    boolean popSystemNavigator();
  }

  @VisibleForTesting
  final PlatformChannel.PlatformMessageHandler mPlatformMessageHandler =
      new PlatformChannel.PlatformMessageHandler() {
        @Override
        public void playSystemSound(@NonNull PlatformChannel.SoundType soundType) {
          PlatformPlugin.this.playSystemSound(soundType);
        }

        @Override
        public void vibrateHapticFeedback(
            @NonNull PlatformChannel.HapticFeedbackType feedbackType) {
          PlatformPlugin.this.vibrateHapticFeedback(feedbackType);
        }

        @Override
        public void setPreferredOrientations(int androidOrientation) {
          setSystemChromePreferredOrientations(androidOrientation);
        }

        @Override
        public void setApplicationSwitcherDescription(
            @NonNull PlatformChannel.AppSwitcherDescription description) {
          setSystemChromeApplicationSwitcherDescription(description);
        }

        @Override
        public void showSystemOverlays(@NonNull List<PlatformChannel.SystemUiOverlay> overlays) {
          setSystemChromeEnabledSystemUIOverlays(overlays);
        }

        @Override
        public void showSystemUiMode(@NonNull PlatformChannel.SystemUiMode mode) {
          setSystemChromeEnabledSystemUIMode(mode);
        }

        @Override
        public void setSystemUiChangeListener() {
          setSystemChromeChangeListener();
        }

        @Override
        public void restoreSystemUiOverlays() {
          restoreSystemChromeSystemUIOverlays();
        }

        @Override
        public void setSystemUiOverlayStyle(
            @NonNull PlatformChannel.SystemChromeStyle systemUiOverlayStyle) {
          setSystemChromeSystemUIOverlayStyle(systemUiOverlayStyle);
        }

        @Override
        public void popSystemNavigator() {
          PlatformPlugin.this.popSystemNavigator();
        }

        @Override
        public CharSequence getClipboardData(
            @Nullable PlatformChannel.ClipboardContentFormat format) {
          return PlatformPlugin.this.getClipboardData(format);
        }

        @Override
        public void setClipboardData(@NonNull String text) {
          PlatformPlugin.this.setClipboardData(text);
        }

        @Override
        public boolean clipboardHasStrings() {
          return PlatformPlugin.this.clipboardHasStrings();
        }
      };

  public PlatformPlugin(Activity activity, PlatformChannel platformChannel) {
    this(activity, platformChannel, null);
  }

  public PlatformPlugin(
      Activity activity, PlatformChannel platformChannel, PlatformPluginDelegate delegate) {
    this.activity = activity;
    this.platformChannel = platformChannel;
    this.platformChannel.setPlatformMessageHandler(mPlatformMessageHandler);
    this.platformPluginDelegate = delegate;

    currentSystemUiMode = PlatformChannel.SystemUiMode.EDGE_TO_EDGE;
  }

  /**
   * Releases all resources held by this {@code PlatformPlugin}.
   *
   * <p>Do not invoke any methods on a {@code PlatformPlugin} after invoking this method.
   */
  public void destroy() {
    this.platformChannel.setPlatformMessageHandler(null);
  }

  private void playSystemSound(PlatformChannel.SoundType soundType) {
    if (soundType == PlatformChannel.SoundType.CLICK) {
      View view = activity.getWindow().getDecorView();
      view.playSoundEffect(SoundEffectConstants.CLICK);
    }
  }

  @VisibleForTesting
  /* package */ void vibrateHapticFeedback(PlatformChannel.HapticFeedbackType feedbackType) {
    View view = activity.getWindow().getDecorView();
    switch (feedbackType) {
      case STANDARD:
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        break;
      case LIGHT_IMPACT:
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        break;
      case MEDIUM_IMPACT:
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        break;
      case HEAVY_IMPACT:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        }
        break;
      case SELECTION_CLICK:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        break;
    }
  }

  private void setSystemChromePreferredOrientations(int androidOrientation) {
    activity.setRequestedOrientation(androidOrientation);
  }

  @SuppressWarnings("deprecation")
  private void setSystemChromeApplicationSwitcherDescription(
      PlatformChannel.AppSwitcherDescription description) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return;
    }

    // Linter refuses to believe we're only executing this code in API 28 unless we
    // use distinct if
    // blocks and
    // hardcode the API 28 constant.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        && Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
      activity.setTaskDescription(
          new TaskDescription(description.label, /* icon= */ null, description.color));
    }
    if (Build.VERSION.SDK_INT >= 28) {
      TaskDescription taskDescription =
          new TaskDescription(description.label, 0, description.color);
      activity.setTaskDescription(taskDescription);
    }
  }

  private void setSystemChromeChangeListener() {
    // Set up a listener to notify the framework when the system ui has changed.
    View decorView = activity.getWindow().getDecorView();
    decorView.setOnSystemUiVisibilityChangeListener(
        new View.OnSystemUiVisibilityChangeListener() {
          @Override
          public void onSystemUiVisibilityChange(int visibility) {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
              // The system bars are visible. Make any desired adjustments to
              // your UI, such as showing the action bar or other navigational
              // controls. Another common action is to set a timer to dismiss
              // the system bars and restore the fullscreen mode that was
              // previously enabled.
              platformChannel.systemChromeChanged(false);
            } else {
              // The system bars are NOT visible. Make any desired adjustments
              // to your UI, such as hiding the action bar or other
              // navigational controls.
              platformChannel.systemChromeChanged(true);
            }
          }
        });
  }

  private void setSystemChromeEnabledSystemUIMode(PlatformChannel.SystemUiMode systemUiMode) {
    int enabledOverlays;

    Window window = activity.getWindow();
    View view = window.getDecorView();
    WindowInsetsControllerCompat windowInsetsControllerCompat =
        new WindowInsetsControllerCompat(window, view);

    if (systemUiMode == PlatformChannel.SystemUiMode.LEAN_BACK) {
      // LEAN BACK
      // Available starting at SDK 20, due to the backwards compatibility provided by the
      // WindowInsetsControllerCompat class for setting the behavior of system bars.
      // Should not show overlays, tap to reveal overlays, needs onChange callback
      // When the overlays come in on tap, the app does not receive the gesture and does not know
      // the system overlay has changed. The overlays cannot be dismissed, so adding the callback
      // support will allow users to restore the system ui and dismiss the overlays.
      // Not compatible with top/bottom overlays enabled.
      windowInsetsControllerCompat.setSystemBarsBehavior(
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_TOUCH);
      windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.systemBars());
      WindowCompat.setDecorFitsSystemWindows(window, false);
    } else if (systemUiMode == PlatformChannel.SystemUiMode.IMMERSIVE) {
      // IMMERSIVE
      // Available starting at SDK 20, due to the backwards compatibility provided by the
      // WindowInsetsControllerCompat class for setting the behavior of system bars.
      // Should not show overlays, swipe from edges to reveal overlays, needs onChange callback
      // When the overlays come in on swipe, the app does not receive the gesture and does not know
      // the system overlay has changed. The overlays cannot be dismissed, so adding callback
      // support will allow users to restore the system ui and dismiss the overlays.
      // Not compatible with top/bottom overlays enabled.
      windowInsetsControllerCompat.setSystemBarsBehavior(
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_SWIPE);
      windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.systemBars());
      WindowCompat.setDecorFitsSystemWindows(window, false);
    } else if (systemUiMode == PlatformChannel.SystemUiMode.IMMERSIVE_STICKY) {
      // STICKY IMMERSIVE
      // Available starting at SDK 20, due to the backwards compatibility provided by the
      // WindowInsetsControllerCompat class for setting the behavior of system bars.
      // Should not show overlays, swipe from edges to reveal overlays. The app will also receive
      // the swipe gesture. The overlays cannot be dismissed, so adding callback support will
      // allow users to restore the system ui and dismiss the overlays.
      // Not compatible with top/bottom overlays enabled.
      windowInsetsControllerCompat.setSystemBarsBehavior(
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.systemBars());
      WindowCompat.setDecorFitsSystemWindows(window, false);
    } else if (systemUiMode == PlatformChannel.SystemUiMode.EDGE_TO_EDGE
        && Build.VERSION.SDK_INT >= 29) {
      // EDGE TO EDGE
      // Available starting at SDK 29. See issue for context:
      // https://github.com/flutter/flutter/issues/89774.
      // Will apply a translucent body scrim behind 2/3 button navigation bars
      // to ensure contrast with buttons on the nav and status bars, unless the contrast is not
      // enforced in the overlay styling.
      WindowCompat.setDecorFitsSystemWindows(window, false);
    } else {
      // When none of the conditions are matched, return without updating the system UI overlays.
      return;
    }
    currentSystemUiMode = systemUiMode;
  }

  private void setSystemChromeEnabledSystemUIOverlays(
      List<PlatformChannel.SystemUiOverlay> overlaysToShow) {
    Window window = activity.getWindow();
    View view = window.getDecorView();
    WindowInsetsControllerCompat windowInsetsControllerCompat =
        new WindowInsetsControllerCompat(window, view);

    // Start by assuming we want to hide all system overlays (like an immersive
    // game).
    windowInsetsControllerCompat.hide(WindowInsetsCompat.Type.systemBars());
    WindowCompat.setDecorFitsSystemWindows(window, false);

    // We apply sticky immersive mode if desired. Available starting at SDK 20.
    if (overlaysToShow.size() == 0) {
      currentSystemUiMode = PlatformChannel.SystemUiMode.IMMERSIVE_STICKY;

      windowInsetsControllerCompat.setSystemBarsBehavior(
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // Re-add any desired system overlays.
    for (int i = 0; i < overlaysToShow.size(); ++i) {
      PlatformChannel.SystemUiOverlay overlayToShow = overlaysToShow.get(i);
      switch (overlayToShow) {
        case TOP_OVERLAYS:
          windowInsetsControllerCompat.show(WindowInsetsCompat.Type.statusBars());
          break;
        case BOTTOM_OVERLAYS:
          windowInsetsControllerCompat.show(WindowInsetsCompat.Type.navigationBars());
          break;
      }
    }
    currentOverlays = overlaysToShow;
  }

  /**
   * Refreshes Android's window system UI (AKA system chrome) to match Flutter's desired {@link
   * PlatformChannel.SystemChromeStyle}.
   *
   * <p>Updating the system UI Overlays is accomplished by altering the decor view of the {@link
   * Window} associated with the {@link android.app.Activity} that was provided to this {@code
   * PlatformPlugin}.
   */
  public void updateSystemUiOverlays() {
    setSystemChromeEnabledSystemUIMode(currentSystemUiMode);

    if (currentOverlays != null) {
      setSystemChromeEnabledSystemUIOverlays(currentOverlays);
    } else if (currentTheme != null) {
      setSystemChromeSystemUIOverlayStyle(currentTheme);
    }
  }

  private void restoreSystemChromeSystemUIOverlays() {
    updateSystemUiOverlays();
  }

  private void setSystemChromeSystemUIOverlayStyle(
      PlatformChannel.SystemChromeStyle systemChromeStyle) {
    Window window = activity.getWindow();
    View view = window.getDecorView();
    WindowInsetsControllerCompat windowInsetsControllerCompat =
        new WindowInsetsControllerCompat(window, view);

    // SYSTEM STATUS BAR -------------------------------------------------------------------
    // You can't change the color of the system status bar until SDK 21, and you can't change the
    // color of the status icons until SDK 23. We only allow both starting at 23 to ensure buttons
    // and icons can be visible when changing the background color.
    // If transparent, SDK 29 and higher may apply a translucent scrim behind the bar to ensure
    // proper contrast. This can be overridden with
    // SystemChromeStyle.systemStatusBarContrastEnforced.
    if (Build.VERSION.SDK_INT >= 23) {
      if (systemChromeStyle.statusBarIconBrightness != null) {
        switch (systemChromeStyle.statusBarIconBrightness) {
          case DARK:
            // Dark status bar icon brightness.
            // Light status bar appearance.
            windowInsetsControllerCompat.setAppearanceLightStatusBars(true);
            break;
          case LIGHT:
            // Light status bar icon brightness.
            // Dark status bar appearance.
            windowInsetsControllerCompat.setAppearanceLightStatusBars(false);
            break;
        }
      }

      if (systemChromeStyle.statusBarColor != null) {
        window.setStatusBarColor(systemChromeStyle.statusBarColor);
      }
    }
    // You can't override the enforced contrast for a transparent status bar until SDK 29.
    // This overrides the translucent scrim that may be placed behind the bar on SDK 29+ to ensure
    // contrast is appropriate when using full screen layout modes like Edge to Edge.
    if (systemChromeStyle.systemStatusBarContrastEnforced != null && Build.VERSION.SDK_INT >= 29) {
      window.setStatusBarContrastEnforced(systemChromeStyle.systemStatusBarContrastEnforced);
    }

    // SYSTEM NAVIGATION BAR --------------------------------------------------------------
    // You can't change the color of the system navigation bar until SDK 21, and you can't change
    // the color of the navigation buttons until SDK 26. We only allow both starting at 26 to
    // ensure buttons can be visible when changing the background color.
    // If transparent, SDK 29 and higher may apply a translucent scrim behind 2/3 button navigation
    // bars to ensure proper contrast. This can be overridden with
    // SystemChromeStyle.systemNavigationBarContrastEnforced.
    if (Build.VERSION.SDK_INT >= 26) {
      if (systemChromeStyle.systemNavigationBarIconBrightness != null) {
        switch (systemChromeStyle.systemNavigationBarIconBrightness) {
          case DARK:
            // Dark navigation bar icon brightness.
            // Light navigation bar appearance.
            windowInsetsControllerCompat.setAppearanceLightNavigationBars(true);
            break;
          case LIGHT:
            // Light navigation bar icon brightness.
            // Dark navigation bar appearance.
            windowInsetsControllerCompat.setAppearanceLightNavigationBars(false);
            break;
        }
      }

      if (systemChromeStyle.systemNavigationBarColor != null) {
        window.setNavigationBarColor(systemChromeStyle.systemNavigationBarColor);
      }
    }
    // You can't change the color of the navigation bar divider color until SDK 28.
    if (systemChromeStyle.systemNavigationBarDividerColor != null && Build.VERSION.SDK_INT >= 28) {
      window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
      window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
      window.setNavigationBarDividerColor(systemChromeStyle.systemNavigationBarDividerColor);
    }

    // You can't override the enforced contrast for a transparent navigation bar until SDK 29.
    // This overrides the translucent scrim that may be placed behind 2/3 button navigation bars on
    // SDK 29+ to ensure contrast is appropriate when using full screen layout modes like
    // Edge to Edge.
    if (systemChromeStyle.systemNavigationBarContrastEnforced != null
        && Build.VERSION.SDK_INT >= 29) {
      window.setNavigationBarContrastEnforced(
          systemChromeStyle.systemNavigationBarContrastEnforced);
    }

    currentTheme = systemChromeStyle;
  }

  private void popSystemNavigator() {
    if (platformPluginDelegate != null && platformPluginDelegate.popSystemNavigator()) {
      // A custom behavior was executed by the delegate. Don't execute default behavior.
      return;
    }

    if (activity instanceof OnBackPressedDispatcherOwner) {
      ((OnBackPressedDispatcherOwner) activity).getOnBackPressedDispatcher().onBackPressed();
    } else {
      activity.finish();
    }
  }

  private CharSequence getClipboardData(PlatformChannel.ClipboardContentFormat format) {
    ClipboardManager clipboard =
        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);

    if (!clipboard.hasPrimaryClip()) return null;

    try {
      ClipData clip = clipboard.getPrimaryClip();
      if (clip == null) return null;
      if (format == null || format == PlatformChannel.ClipboardContentFormat.PLAIN_TEXT) {
        ClipData.Item item = clip.getItemAt(0);
        if (item.getUri() != null)
          activity.getContentResolver().openTypedAssetFileDescriptor(item.getUri(), "text/*", null);
        return item.coerceToText(activity);
      }
    } catch (SecurityException e) {
      Log.w(
          TAG,
          "Attempted to get clipboard data that requires additional permission(s).\n"
              + "See the exception details for which permission(s) are required, and consider adding them to your Android Manifest as described in:\n"
              + "https://developer.android.com/guide/topics/permissions/overview",
          e);
      return null;
    } catch (FileNotFoundException e) {
      return null;
    }

    return null;
  }

  private void setClipboardData(String text) {
    ClipboardManager clipboard =
        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText("text label?", text);
    clipboard.setPrimaryClip(clip);
  }

  private boolean clipboardHasStrings() {
    ClipboardManager clipboard =
        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    // Android 12 introduces a toast message that appears when an app reads the clipboard. To avoid
    // unintended access, call the appropriate APIs to receive information about the current content
    // that's on the clipboard (rather than the actual content itself).
    if (!clipboard.hasPrimaryClip()) {
      return false;
    }
    ClipDescription description = clipboard.getPrimaryClipDescription();
    if (description == null) {
      return false;
    }
    return description.hasMimeType("text/*");
  }
}
