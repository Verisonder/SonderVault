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
