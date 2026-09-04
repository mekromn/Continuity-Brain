# Continuity Brain intentionally avoids reflection-heavy persistence and network
# frameworks. Keep this file minimal so R8 can aggressively remove dead code.

# Preserve useful source information in crash traces while stripping private
# archive data (which is never compiled into the APK in the first place).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
