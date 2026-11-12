# Keep the app's own classes intact (libraries are still shrunk and optimised).
-keep class com.javaram.gallery.** { *; }
-dontwarn org.slf4j.**
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
