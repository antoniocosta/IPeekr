# Project-specific R8 rules. Glance, WorkManager and Compose ship their own consumer rules.

# Glance creates widget click callbacks by reflection (no-arg constructor)
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Release builds don't log: verbose/debug/info/warning calls are removed (errors stay)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
