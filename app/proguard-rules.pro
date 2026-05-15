# Keep Entry model for JSON serialization
-keep class com.cryptex.app.Entry { *; }

# Keep BackupCrypto and its inner exception classes
-keep class com.cryptex.app.BackupCrypto { *; }
-keep class com.cryptex.app.BackupCrypto$* { *; }

# Keep AndroidX Security
-keep class androidx.security.crypto.** { *; }

# Fix for Missing classes detected while running R8 (Tink dependencies)
-dontwarn javax.annotation.**

# Keep javax.crypto used by BackupCrypto
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
