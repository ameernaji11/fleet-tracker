# The app module is built with minifyEnabled false, so today nothing here is
# consulted. This rule exists for the day that changes: the plugin class and the
# service are both looked up by name - the first in capacitor.plugins.json, the
# second in the manifest - and both break silently when they are renamed.
-keep class com.fleet.dms.** { *; }
