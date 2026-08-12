# Nothing here is required to make the app work. aapt2 generates, from the merged
# manifest:
#   -keep class dev.screenclip.{MainActivity,SetupActivity,ConsentActivity,
#                               CaptureService,CaptureTile,ShotService} { <init>(); }
#   -keep class androidx.core.content.FileProvider { <init>(); }
#
# -keep pins the NAME, which is what res/xml/shortcuts.xml's literal
# android:targetClass="dev.screenclip.SetupActivity" silently depends on. Keeping
# <init>() also marks each class instantiated, so every framework override
# (onServiceConnected, onStartListening, TakeScreenshotCallback.onSuccess, …) is
# pinned automatically. OverlayRoot is built in code, never inflated from XML, so it
# needs no (Context, AttributeSet) rule. The app uses no reflection at all.
#
# NEVER add -repackageclasses or -flattenpackagehierarchy: either would rename
# SetupActivity out from under that literal string in shortcuts.xml.

# Readable release stack traces without having to retrace by hand. -keepnames is
# -keep,allowshrinking, so dead code is still removed; it only blocks inlining and
# merging of our own handful of classes.
-keepnames class dev.screenclip.** { *; }
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Deliberately NOT stripping logging. `adb shell run-as` stops working on a
# non-debuggable build, so logcat becomes the only diagnostic channel — and the
# "clipboard write confirmed" line is the only evidence that a clipboard write
# actually landed rather than being silently refused.
#   DO NOT ADD: -assumenosideeffects class android.util.Log { ... }
