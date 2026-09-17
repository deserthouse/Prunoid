# SDK-Pruner ProGuard/R8 rules

# kotlinx-serialization: keep serializers of rule models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class io.github.deserthouse.sdkpruner.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.deserthouse.sdkpruner.** {
    kotlinx.serialization.KSerializer serializer(...);
}
