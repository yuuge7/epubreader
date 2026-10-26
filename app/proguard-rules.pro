# R8 rules for the app. Room, Hilt, Compose, Coil, DataStore and the WebKit library ship
# their own consumer rules; these cover what this app reaches by name at run time.

# The reader's page script calls the bridge's methods by name (Android.tap(), ...).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# XmlPullParser and org.json are the platform's own.
-dontwarn org.xmlpull.**

# Keep line numbers for readable crash reports; the file names are hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
