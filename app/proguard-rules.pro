# R8 rules for the release build. Libraries (OkHttp, Coil, Compose, Room, kotlinx.serialization)
# ship their own consumer rules; these cover what reflection reaches in this app.

# kotlinx.serialization: generated serializers of the app's @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.zmby.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.zmby.**$$serializer { *; }

# pinyin4j reads its tables through the class loader and references optional XML classes.
-keep class net.sourceforge.pinyin4j.** { *; }
-keep class com.hp.hpl.sparta.** { *; }
-dontwarn net.sourceforge.pinyin4j.**
-dontwarn com.hp.hpl.sparta.**

# Crash stacks on the LAN log stay readable.
-keepattributes SourceFile, LineNumberTable
