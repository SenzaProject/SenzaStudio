plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.senza.studio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.senza.studio"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.0"
        ndk { abiFilters += "arm64-v8a" }   // aapt2 yang dipakai hanya ARM64
    }

    packaging {
        // wajib: supaya libaapt2.so diekstrak ke nativeLibraryDir dan bisa dieksekusi
        jniLibs { useLegacyPackaging = true }
        resources {
            pickFirsts += setOf("META-INF/**", "kotlin/**", "**/*.kotlin_builtins")
            // Jar bertanda tangan (ECJ, BouncyCastle, JGit) membawa META-INF/*.SF|*.RSA|*.DSA dan MANIFEST.MF
            // yang tidak cocok satu sama lain. Di dalam APK ini membuat JarFile melempar SecurityException
            // ("Invalid signature file digest") setiap resource dibaca lewat class loader (ECJ, D8, dst).
            excludes += setOf("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC", "META-INF/MANIFEST.MF")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.eclipse.jdt:ecj:3.33.0")              // compiler Java
    implementation("com.android.tools:r8:8.1.72")             // berisi D8 (class -> dex)
    implementation("com.android.tools.build:apksig:8.1.4")    // tanda tangan APK
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")                 // sertifikat keystore + tanda tangan AAB
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r") // Git (versi 5.x: kompatibel Android)
    implementation("org.slf4j:slf4j-nop:1.7.36")                              // JGit butuh binding slf4j
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24") // compiler Kotlin (dijalankan di proses terpisah)
}
