package com.senza.studio

import java.io.File

/** Info satu <activity> di manifest. */
class ActInfo(val name: String, val launcher: Boolean, val start: Int, val end: Int)

/** Operasi berbasis teks pada AndroidManifest.xml (format dan komentar tetap terjaga). */
object ManifestTools {

    val COMMON_PERMISSIONS = listOf(
        "android.permission.INTERNET" to "Internet",
        "android.permission.ACCESS_NETWORK_STATE" to "Status jaringan",
        "android.permission.ACCESS_WIFI_STATE" to "Status Wi-Fi",
        "android.permission.CAMERA" to "Kamera",
        "android.permission.RECORD_AUDIO" to "Rekam suara (mikrofon)",
        "android.permission.ACCESS_FINE_LOCATION" to "Lokasi akurat (GPS)",
        "android.permission.ACCESS_COARSE_LOCATION" to "Lokasi perkiraan",
        "android.permission.READ_EXTERNAL_STORAGE" to "Baca penyimpanan",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "Tulis penyimpanan",
        "android.permission.READ_MEDIA_IMAGES" to "Baca gambar (Android 13+)",
        "android.permission.READ_CONTACTS" to "Baca kontak",
        "android.permission.VIBRATE" to "Getar",
        "android.permission.POST_NOTIFICATIONS" to "Kirim notifikasi (Android 13+)",
        "android.permission.WAKE_LOCK" to "Cegah perangkat tidur",
        "android.permission.FOREGROUND_SERVICE" to "Foreground service",
        "android.permission.RECEIVE_BOOT_COMPLETED" to "Jalan saat perangkat menyala",
        "android.permission.BLUETOOTH_CONNECT" to "Sambung Bluetooth (Android 12+)"
    )

    private val permRe = Regex("<uses-permission\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"")
    private val actRe = Regex("<activity\\b[^>]*?(?:/>|>[\\s\\S]*?</activity>)")
    private val nameRe = Regex("android:name\\s*=\\s*\"([^\"]+)\"")

    fun permissions(text: String): List<String> = permRe.findAll(text).map { it.groupValues[1] }.toList()

    private fun appStart(text: String): Int {
        val i = text.indexOf("<application")
        if (i < 0) return -1
        var s = i
        while (s > 0 && text[s - 1] != '\n') s--
        return s
    }

    fun setPermission(text: String, perm: String, on: Boolean): String {
        val has = permissions(text).contains(perm)
        if (on && has) return text
        if (!on && !has) return text
        if (on) {
            val line = "    <uses-permission android:name=\"$perm\" />\n"
            val at = appStart(text)
            if (at >= 0) return text.substring(0, at) + line + text.substring(at)
            val end = text.lastIndexOf("</manifest>")
            return if (end >= 0) text.substring(0, end) + line + text.substring(end) else text
        }
        val re = Regex("(?m)^[ \\t]*<uses-permission\\b[^>]*?android:name\\s*=\\s*\"" + Regex.escape(perm) + "\"[^>]*?/>[ \\t]*\\n?")
        return re.replaceFirst(text, "")
    }

    fun activities(text: String): List<ActInfo> =
        actRe.findAll(text).map { m ->
            val block = m.value
            ActInfo(
                nameRe.find(block)?.groupValues?.get(1) ?: "?",
                block.contains("android.intent.category.LAUNCHER"),
                m.range.first, m.range.last + 1
            )
        }.toList()

    private fun lineStartOf(text: String, i: Int): Int {
        var s = i
        while (s > 0 && (text[s - 1] == ' ' || text[s - 1] == '\t')) s--
        return s
    }

    fun addActivity(text: String, cls: String, launcher: Boolean): String {
        val end = text.indexOf("</application>")
        if (end < 0) throw IllegalArgumentException("Tag </application> tidak ditemukan di manifest.")
        val sb = StringBuilder()
        if (launcher) {
            sb.append("        <activity android:name=\".$cls\" android:exported=\"true\">\n")
            sb.append("            <intent-filter>\n")
            sb.append("                <action android:name=\"android.intent.action.MAIN\" />\n")
            sb.append("                <category android:name=\"android.intent.category.LAUNCHER\" />\n")
            sb.append("            </intent-filter>\n")
            sb.append("        </activity>\n")
        } else {
            sb.append("        <activity android:name=\".$cls\" android:exported=\"false\" />\n")
        }
        val at = lineStartOf(text, end)
        val prefix = if (at > 0 && text[at - 1] != '\n') "\n" else ""
        return text.substring(0, at) + prefix + sb + text.substring(at)
    }

    fun removeActivity(text: String, name: String): String {
        val a = activities(text).firstOrNull { it.name == name } ?: return text
        var s = lineStartOf(text, a.start)
        var e = a.end
        if (e < text.length && text[e] == '\n') e++
        if (s > 0 && text[s - 1] != '\n') s = a.start
        return text.substring(0, s) + text.substring(e)
    }

    private val filterRe = Regex("[ \\t]*<intent-filter>[\\s\\S]*?</intent-filter>[ \\t]*\\n?")
    private const val LAUNCHER_FILTER =
        "            <intent-filter>\n" +
            "                <action android:name=\"android.intent.action.MAIN\" />\n" +
            "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
            "            </intent-filter>\n"

    fun setLauncher(text: String, name: String): String {
        var t = text
        // 1. lepas peluncur dari semua activity
        for (a in activities(t).reversed()) {
            if (!a.launcher) continue
            val block = t.substring(a.start, a.end)
            val cleaned = filterRe.replace(block) { m -> if (m.value.contains("android.intent.category.LAUNCHER")) "" else m.value }
            t = t.substring(0, a.start) + cleaned + t.substring(a.end)
        }
        // 2. pasang di activity tujuan
        val target = activities(t).firstOrNull { it.name == name } ?: return t
        val block = t.substring(target.start, target.end)
        val newBlock = if (block.endsWith("/>")) {
            block.removeSuffix("/>").trimEnd().let { open ->
                val o = if (open.contains("android:exported")) open else "$open android:exported=\"true\""
                "$o>\n$LAUNCHER_FILTER        </activity>"
            }
        } else {
            val close = block.lastIndexOf("</activity>")
            block.substring(0, close).trimEnd() + "\n" + LAUNCHER_FILTER + "        " + block.substring(close)
        }
        return t.substring(0, target.start) + newBlock + t.substring(target.end)
    }

    fun snake(camel: String): String =
        Regex("([a-z0-9])([A-Z])").replace(camel, "$1_$2").lowercase()

    fun layoutNameFor(cls: String): String {
        val base = cls.removeSuffix("Activity")
        return "activity_" + (if (base.isEmpty()) "new" else snake(base))
    }

    /** Buat kelas Activity Java + layout dasar. Mengembalikan path relatif file kelas. */
    fun createActivityFiles(proj: File, pkg: String, cls: String): String {
        val rel = "src/" + pkg.replace('.', '/') + "/$cls.java"
        val f = File(proj, rel)
        require(!f.exists()) { "Kelas $cls sudah ada." }
        val layout = layoutNameFor(cls)
        f.parentFile?.mkdirs()
        f.writeText(
            "package $pkg;\n\nimport android.app.Activity;\nimport android.os.Bundle;\n\n" +
                "public class $cls extends Activity {\n\n    @Override\n    protected void onCreate(Bundle savedInstanceState) {\n" +
                "        super.onCreate(savedInstanceState);\n        setContentView(R.layout.$layout);\n    }\n}\n"
        )
        val lf = File(proj, "res/layout/$layout.xml")
        if (!lf.exists()) {
            lf.parentFile?.mkdirs()
            lf.writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n" +
                    "    android:orientation=\"vertical\"\n    android:gravity=\"center\"\n    android:padding=\"24dp\">\n\n" +
                    "    <TextView\n        android:layout_width=\"wrap_content\"\n        android:layout_height=\"wrap_content\"\n" +
                    "        android:text=\"$cls\"\n        android:textSize=\"22sp\" />\n\n</LinearLayout>\n"
            )
        }
        return rel
    }
}

/** Template proyek baru. */
object ProjectTemplates {

    val NAMES = listOf(
        "Kosong (Hello Senza)",
        "Daftar (RecyclerView)",
        "Multi-layar (2 Activity)",
        "Navigasi tab bawah"
    )

    val DESCRIPTIONS = listOf(
        "Satu layar dengan tombol. Cocok untuk mulai dari nol.",
        "Daftar yang bisa digulir dan diketuk. Memakai library androidx.recyclerview (diunduh saat build pertama, butuh internet).",
        "Dua layar; layar pertama mengirim teks ke layar kedua lewat Intent.",
        "Tiga halaman dengan bilah tab di bawah. Tanpa library tambahan."
    )

    fun create(store: ProjectStore, name: String, pkg: String, id: Int): File {
        val d = store.create(name, pkg)
        if (id <= 0) return d
        val path = pkg.replace('.', '/')
        File(d, "src/$path/MainActivity.java").delete()

        fun w(rel: String, text: String) {
            File(d, rel).apply { parentFile?.mkdirs() }.writeText(text.replace("__PKG__", pkg))
        }

        when (id) {
            1 -> {
                GradleLite.addDependency(d, "androidx.recyclerview:recyclerview:1.3.2")
                w("src/$path/MainActivity.java", RECYCLER_MAIN)
                w("src/$path/ItemAdapter.java", RECYCLER_ADAPTER)
                w("res/layout/activity_main.xml", RECYCLER_LAYOUT)
                w("res/layout/item_row.xml", RECYCLER_ROW)
                w("AndroidManifest.xml", manifest(pkg, listOf("MainActivity")))
            }
            2 -> {
                w("src/$path/MainActivity.java", MULTI_MAIN)
                w("src/$path/SecondActivity.java", MULTI_SECOND)
                w("res/layout/activity_main.xml", MULTI_LAYOUT_MAIN)
                w("res/layout/activity_second.xml", MULTI_LAYOUT_SECOND)
                w("AndroidManifest.xml", manifest(pkg, listOf("MainActivity", "SecondActivity")))
            }
            3 -> {
                w("src/$path/MainActivity.java", NAV_MAIN)
                w("res/layout/activity_main.xml", NAV_LAYOUT)
                w("AndroidManifest.xml", manifest(pkg, listOf("MainActivity")))
            }
        }
        return d
    }

    private fun manifest(pkg: String, acts: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    package=\"$pkg\">\n")
        sb.append("    <application android:label=\"@string/app_name\">\n")
        acts.forEachIndexed { i, a ->
            if (i == 0) {
                sb.append("        <activity android:name=\".$a\" android:exported=\"true\">\n")
                sb.append("            <intent-filter>\n")
                sb.append("                <action android:name=\"android.intent.action.MAIN\" />\n")
                sb.append("                <category android:name=\"android.intent.category.LAUNCHER\" />\n")
                sb.append("            </intent-filter>\n")
                sb.append("        </activity>\n")
            } else {
                sb.append("        <activity android:name=\".$a\" android:exported=\"false\" />\n")
            }
        }
        sb.append("    </application>\n</manifest>\n")
        return sb.toString()
    }

    private val RECYCLER_MAIN = """
package __PKG__;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        List<String> data = new ArrayList<String>();
        for (int i = 1; i <= 30; i++) {
            data.add("Item ke-" + i);
        }

        RecyclerView list = (RecyclerView) findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new ItemAdapter(data, new ItemAdapter.OnItemClick() {
            @Override
            public void onClick(String item) {
                Toast.makeText(MainActivity.this, item, Toast.LENGTH_SHORT).show();
            }
        }));
    }
}
""".trimStart()

    private val RECYCLER_ADAPTER = """
package __PKG__;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.Holder> {

    public interface OnItemClick {
        void onClick(String item);
    }

    private final List<String> items;
    private final OnItemClick listener;

    public ItemAdapter(List<String> items, OnItemClick listener) {
        this.items = items;
        this.listener = listener;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        final String item = items.get(position);
        holder.title.setText(item);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;

        Holder(View itemView) {
            super(itemView);
            title = (TextView) itemView.findViewById(R.id.item_title);
        }
    }
}
""".trimStart()

    private val RECYCLER_LAYOUT = """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:text="Daftar"
        android:textSize="22sp"
        android:textStyle="bold" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>
""".trimStart()

    private val RECYCLER_ROW = """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">

    <TextView
        android:id="@+id/item_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:text="Item"
        android:textSize="16sp" />

</LinearLayout>
""".trimStart()

    private val MULTI_MAIN = """
package __PKG__;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText input = (EditText) findViewById(R.id.input);
        Button open = (Button) findViewById(R.id.btn_open);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, SecondActivity.class);
                i.putExtra("pesan", input.getText().toString());
                startActivity(i);
            }
        });
    }
}
""".trimStart()

    private val MULTI_SECOND = """
package __PKG__;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class SecondActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);

        TextView message = (TextView) findViewById(R.id.message);
        String text = getIntent().getStringExtra("pesan");
        message.setText(text == null || text.length() == 0 ? "(kosong)" : text);

        Button back = (Button) findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
""".trimStart()

    private val MULTI_LAYOUT_MAIN = """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="24dp">

    <EditText
        android:id="@+id/input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Tulis pesan" />

    <Button
        android:id="@+id/btn_open"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="Buka layar kedua" />

</LinearLayout>
""".trimStart()

    private val MULTI_LAYOUT_SECOND = """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="24dp">

    <TextView
        android:id="@+id/message"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Pesan"
        android:textSize="24sp"
        android:textStyle="bold" />

    <Button
        android:id="@+id/btn_back"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="Kembali" />

</LinearLayout>
""".trimStart()

    private val NAV_MAIN = """
package __PKG__;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class MainActivity extends Activity {

    private View[] pages;
    private Button[] tabs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pages = new View[] {
            findViewById(R.id.page_home),
            findViewById(R.id.page_search),
            findViewById(R.id.page_profile)
        };
        tabs = new Button[] {
            (Button) findViewById(R.id.tab_home),
            (Button) findViewById(R.id.tab_search),
            (Button) findViewById(R.id.tab_profile)
        };
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(index);
                }
            });
        }
        show(0);
    }

    private void show(int index) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs[i].setEnabled(i != index);
        }
    }
}
""".trimStart()

    private val NAV_LAYOUT = """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <LinearLayout
            android:id="@+id/page_home"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Beranda"
                android:textSize="26sp"
                android:textStyle="bold" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/page_search"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Cari"
                android:textSize="26sp"
                android:textStyle="bold" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/page_profile"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Profil"
                android:textSize="26sp"
                android:textStyle="bold" />
        </LinearLayout>
    </FrameLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <Button
            android:id="@+id/tab_home"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Beranda" />

        <Button
            android:id="@+id/tab_search"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Cari" />

        <Button
            android:id="@+id/tab_profile"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Profil" />
    </LinearLayout>

</LinearLayout>
""".trimStart()
}
