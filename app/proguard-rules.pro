# Tally release rules. Default proguard-android-optimize.txt handles the
# standard Android/Kotlin shrinking; below are the keep rules R8 needs
# for the libraries Tally uses (all verified against release deps).

# Room: keep entities/DAOs; generated code is referenced reflectively.
-keep class com.tally.steps.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# DataStore Preferences: serializer + proto schema stay intact.
-keep class androidx.datastore.** { *; }

# Health Connect client: request/response models cross process boundaries.
-keep class androidx.health.connect.client.** { *; }

# Glance widgets: receiver + widget classes referenced from manifest/ XML.
-keep class com.tally.steps.widget.** { *; }

# WorkManager: workers instantiated by class name.
-keep class com.tally.steps.engine.SyncWorker { *; }
-keep class androidx.work.** { *; }

# Kotlin coroutines / serialization service loaders.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Compose: keep nothing extra (compiler-generated); silence known noise.
-dontwarn androidx.compose.**
