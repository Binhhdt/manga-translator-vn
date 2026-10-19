// Tệp build gốc: chỉ khai báo phiên bản plugin, áp dụng ở module :app.
// AGP 8.7.3 cần Gradle >= 8.9 và JDK 17. Kotlin 2.0.21 + plugin Compose Compiler cùng phiên bản.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
