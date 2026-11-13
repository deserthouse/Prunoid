# Prunoid ProGuard/R8 rules

# kotlinx-serialization: keep serializers of rule models
# 批X8（M24）：旧命名空间 sdkpruner 的 keep 规则已删——指向不存在的包（现命名
# prunoid），实际未保持任何符号；Release 冷启/账本解析/订阅解析在 0.30.x 验证正常，
# kotlinx-serialization 插件与库自带 R8 规则覆盖现命名空间。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
# Xposed hook entry (referenced only by assets/xposed_init at runtime — R8 cannot see it)
-keep class io.github.deserthouse.prunoid.hook.PrunoidHook { *; }
