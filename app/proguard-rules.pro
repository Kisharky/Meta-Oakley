# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.squareup.retrofit2.http.* <methods>;
}
