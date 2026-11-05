# R8 rules for the Bimeron release build. See docs/RELEASE.md.
#
# bimeron-core.aar is a gomobile binding: libgojni.so resolves Java classes, fields and methods
# by their exact JNI names (go/Seq, go/Universe, org/bimeron/core/androidcore/*) and calls back
# into Java objects through those names. Nothing on the Java side references them by reflection,
# so R8 cannot see these uses; renaming or removing any member breaks the core at runtime with
# NoSuchMethodError/ClassNotFoundException from native code. The AAR ships the same two -keep
# rules in its proguard.txt; they are repeated here so the release build stays correct even if
# a rebuilt AAR drops its consumer rules. (-keep class also matches interfaces.)
-keep class go.** { *; }
-keep class org.bimeron.core.** { *; }

# App classes passed to the core as gomobile interfaces (e.g. SocketProtector.protect). Native
# code invokes them by name through the interface; keep the implementing members unrenamed.
-keepclassmembers class * implements org.bimeron.core.** { *; }
-keepclassmembers class * implements go.Seq$Proxy { *; }

# Any JNI entry points.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Readable release stack traces; mapping.txt is archived with every release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
