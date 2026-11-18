# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI del AAR WebRTC VAD y puntos de entrada que instancia Android.
-keep class com.konovalov.vad.webrtc.** { *; }
-keep class com.example.assist.PingAutoSendAccessibilityService { *; }
-keep class com.example.assist.PingVoiceInteractionService { *; }
-keep class com.example.assist.PingVoiceInteractionSessionService { *; }
-keep class com.example.assist.PingAssistWidgetProvider { *; }
-keep class com.example.phone.NotificationAccessService { *; }
-keep class com.example.phone.MediaAccessService { *; }

# ViewModelProvider resuelve este constructor por reflexión.
-keepclassmembers class com.example.ui.MainViewModel {
    public <init>(android.app.Application);
}
-keep class org.json.** { *; }
