package com.senza.studio

/** Hasil menggabungkan defaultConfig + product flavor + build type. */
class EffectiveVariant(
    val applicationId: String,
    val versionName: String,
    val buildType: String,
    val flavor: String,
    val fields: Map<String, BuildField>
)

/**
 * Varian build sederhana dan BuildConfig.
 * Urutan prioritas: defaultConfig < product flavor < build type (sama seperti Gradle).
 * Dipahami: applicationIdSuffix, versionNameSuffix, buildConfigField. Satu flavor per build, tanpa dimensi.
 */
object Variants {

    private val ID_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private fun dotted(s: String?): String = when {
        s.isNullOrEmpty() -> ""
        s.startsWith(".") -> s
        else -> ".$s"
    }

    fun typeName(release: Boolean) = if (release) "release" else "debug"

    /** applicationId efektif tanpa validasi (dipakai untuk placeholder manifest sebelum merge). */
    fun applicationId(info: GradleInfo, basePkg: String, release: Boolean, flavor: String): String =
        basePkg + dotted(info.flavors[flavor]?.idSuffix) + dotted(info.buildTypes[typeName(release)]?.idSuffix)

    fun resolve(info: GradleInfo, basePkg: String, versionName: String, release: Boolean, flavor: String): EffectiveVariant {
        val fl = info.flavors[flavor]
        val ty = info.buildTypes[typeName(release)]
        val id = applicationId(info, basePkg, release, flavor)
        if (!ID_RE.matches(id)) {
            throw BuildError(BuildError.Kind.OTHER, "applicationId hasil gabungan tidak valid: \"$id\". Cek applicationIdSuffix di build.gradle.")
        }
        val fields = LinkedHashMap<String, BuildField>()
        fields.putAll(info.defaultFields)
        fl?.let { fields.putAll(it.fields) }
        ty?.let { fields.putAll(it.fields) }
        return EffectiveVariant(
            id,
            versionName + (fl?.versionSuffix ?: "") + (ty?.versionSuffix ?: ""),
            typeName(release),
            if (fl != null) flavor else "",
            fields
        )
    }

    private fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val BUILTIN = setOf("DEBUG", "APPLICATION_ID", "BUILD_TYPE", "FLAVOR", "VERSION_CODE", "VERSION_NAME")

    /** Isi BuildConfig.java. Mengembalikan teks dan daftar peringatan. */
    fun buildConfigJava(pkg: String, e: EffectiveVariant, versionCode: Int, release: Boolean, warn: MutableList<String>): String {
        val sb = StringBuilder()
        sb.append("/** Dibuat otomatis oleh Senza Studio. Jangan diubah. */\n")
        sb.append("package ").append(pkg).append(";\n\n")
        sb.append("public final class BuildConfig {\n")
        sb.append("    private BuildConfig() {}\n")
        sb.append("    public static final boolean DEBUG = ").append(!release).append(";\n")
        sb.append("    public static final String APPLICATION_ID = ").append(lit(e.applicationId)).append(";\n")
        sb.append("    public static final String BUILD_TYPE = ").append(lit(e.buildType)).append(";\n")
        sb.append("    public static final String FLAVOR = ").append(lit(e.flavor)).append(";\n")
        sb.append("    public static final int VERSION_CODE = ").append(versionCode).append(";\n")
        sb.append("    public static final String VERSION_NAME = ").append(lit(e.versionName)).append(";\n")
        for (f in e.fields.values) {
            if (f.name in BUILTIN) { warn.add("buildConfigField ${f.name} bentrok dengan field bawaan, dilewati."); continue }
            sb.append("    public static final ").append(f.type).append(' ').append(f.name).append(" = ").append(f.value).append(";\n")
        }
        sb.append("}\n")
        return sb.toString()
    }
}
