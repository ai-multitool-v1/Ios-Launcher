# SetBD Cloner keeps R8 disabled for release builds (isMinifyEnabled=false),
# because the container engine resolves guest components via reflection and
# runtime renaming would make hook diagnostics harder. Rules are kept for
# completeness in case minification is enabled in the future.

-keep class org.setbd.cloner.engine.** { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Application { *; }

# Guest APKs are loaded dynamically — never strip reflection helpers.
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**
