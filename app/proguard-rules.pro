# Bouncy Castle carries optional JCE provider registration and JDK-version-specific
# classes that are not present on Android. Nothing here uses them: Argon2 is reached
# through the lightweight API directly.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Argon2BytesGenerator and its parameters are reached by name from Kotlin, not by
# reflection, so R8 can shrink the rest of the library freely.
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }

# Shrink but do not rename. A crash on a test build is read out of the app itself, and an
# obfuscated stack trace turns that back into guesswork. Costs a little APK size and gives
# up nothing that matters: the source is public anyway.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
