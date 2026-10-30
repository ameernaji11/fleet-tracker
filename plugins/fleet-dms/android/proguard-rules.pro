# The app module is built with minifyEnabled false, so today nothing here is
# consulted. These rules exist for the day that changes: MediaPipe reaches its
# native libraries by class name, and the plugin class itself is looked up by
# name in capacitor.plugins.json at start-up. Both break silently when they are
# renamed.
-keep class com.fleet.dms.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
