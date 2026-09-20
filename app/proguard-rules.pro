# Prunoid ProGuard/R8 rules

# kotlinx-serialization: keep serializers of rule models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class io.github.deserthouse.sdkpruner.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.deserthouse.sdkpruner.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Xposed hook entry (referenced only by assets/xposed_init at runtime — R8 cannot see it)
-keep class io.github.deserthouse.prunoid.hook.PrunoidHook { *; }
