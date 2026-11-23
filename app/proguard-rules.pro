# Keep XML/manifest entry points through the standard Android optimizer rules.
-keepattributes Signature,InnerClasses,EnclosingMethod

# SharedPreferences retains listeners weakly; do not inline away the owner reference.
-keepclassmembers class com.nexttv.core.data.Prefs {
    android.content.SharedPreferences$OnSharedPreferenceChangeListener motionListener;
}
