import random
import os

random.seed(42)

# ============================================================
# KOSAKATA UMUM (dipakai lintas-arc, supaya variasi jauh lebih besar)
# ============================================================
VERBS = [
    "Bertahan di", "Melarikan Diri dari", "Bersembunyi di Balik", "Menggali Rahasia di",
    "Menangis di Depan", "Bersumpah di Hadapan", "Berjalan Menjauhi", "Menoleh ke",
    "Mengingat", "Menguburkan", "Menyusun Rencana di", "Menantang Musuh di",
    "Membuka Jalan Menuju", "Menyusuri", "Mengintai dari", "Bertaruh Nyawa di",
    "Menyelinap ke", "Diusir dari", "Diterima di", "Diuji oleh",
    "Menyingkap Rahasia di", "Membalas Dendam di", "Berdiri Sendirian di", "Menerobos Batas di",
    "Kehilangan Jejak di", "Menemukan Kembali", "Melindungi", "Mengorbankan Segalanya di",
    "Bertahan dari Petir di", "Menaklukkan Ketakutan di", "Menyusun Kepingan tentang",
    "Menatap Kembali", "Meninggalkan", "Kembali ke", "Membangun Kekuatan di",
    "Menghadapi Kenyataan di", "Menyelamatkan Diri dari", "Merenungkan", "Bangkit dari",
    "Terjebak di", "Meloloskan Diri dari", "Menyusun Formasi di", "Mengecoh Lawan di",
    "Menyusup ke", "Mempertaruhkan Segalanya di", "Menutup Babak di", "Membuka Gerbang Menuju",
    "Menyelesaikan Urusan di", "Berdamai dengan",
]

EMOTIONS = [
    "Ketakutan", "Kehilangan", "Kemarahan Terpendam", "Kesunyian", "Keputusasaan",
    "Tekad Pertama", "Air Mata Kering", "Dendam yang Lahir", "Keraguan", "Kepercayaan yang Tumbuh",
    "Rasa Malu", "Harga Diri", "Kesabaran yang Diuji", "Perhitungan Diam-diam", "Pertaruhan Kecil",
    "Kelegaan Sesaat", "Kecurigaan", "Harapan yang Rapuh", "Amarah yang Ditahan", "Kesetiaan yang Diuji",
    "Ketegangan yang Memuncak", "Kelelahan Batin", "Keberanian yang Dipaksakan", "Penyesalan",
    "Kebencian yang Terpendam", "Kekaguman Tersembunyi", "Ketidakpastian", "Kepedihan Lama",
    "Kegembiraan yang Singkat", "Rasa Bersalah", "Ambisi yang Tumbuh", "Kesepian di Tengah Keramaian",
    "Keteguhan Hati", "Kepasrahan", "Kewaspadaan", "Luka Lama yang Terbuka", "Kerinduan",
    "Ketakutan akan Kebenaran", "Kelegaan yang Getir", "Tekad yang Menguat", "Bayang-bayang Masa Lalu",
]

# ============================================================
# ARC: hanya "nouns" (tempat/objek/nama khas arc) yang dibedakan per-arc,
# supaya identitas arc tetap terasa, sementara verbs & emotions dipakai
# dari daftar besar di atas -> kombinasi jadi jauh lebih banyak & bervariasi.
# ============================================================
arcs = [
    {
        "name": "Reruntuhan Desa Xuan", "start": 1, "end": 80,
        "nouns": ["Reruntuhan Desa", "Kuil Bawah Tanah", "Abu Desa", "Langit yang Terbelah",
                  "Wajah Raksasa", "Sumpah di Atas Abu", "Jasad Orang Tua", "Api Unggun Terakhir",
                  "Bayangan Gunung", "Sisa Rumah", "Ladang Terbakar", "Sungai Kering"],
        "milestones": {1:"Hari Langit Terbelah",10:"Wajah di Angkasa",25:"Kuil yang Menyelamatkan",
                        40:"Sumpah di Atas Abu",60:"Penyintas Terakhir",80:"Langkah Pertama Meninggalkan Reruntuhan"},
    },
    {
        "name": "Kota Perbatasan Heixuan", "start": 81, "end": 180,
        "nouns": ["Kota Heixuan", "Gang Buruh", "Pasar Batu Spirit", "Aula Perekrutan",
                  "Kedai Pil Murah", "Menara Pengawas", "Serikat Kuli", "Jalan Berdebu",
                  "Gudang Tua", "Perbatasan Kota", "Rumah Kontrakan Sempit", "Kedai Teh Malam"],
        "milestones": {81:"Gerbang Kota Heixuan",100:"Manusia Tanpa Akar Spiritual",
                        120:"Rencana di Balik Kelemahan",150:"Tempaan Tubuh Pertama",180:"Nama yang Mulai Dikenal"},
    },
    {
        "name": "Aliran Bayangan", "start": 181, "end": 300,
        "nouns": ["Aliran Bayangan", "Gerbang Sekte Marjinal", "Ruang Latihan Bawah Tanah",
                  "Guru Tanpa Nama", "Kitab Metode Terlarang", "Aula Penerimaan Murid",
                  "Altar Sumpah Sekte", "Perpustakaan Terlupakan", "Halaman Belakang Sekte",
                  "Sumur Qi Tersembunyi", "Ruang Tetua", "Lorong Ujian Kecil"],
        "milestones": {181:"Sekte yang Menerima Orang Cacat",200:"Metode Kultivasi Tanpa Bakat",
                        230:"Qi Pertama yang Tertarik ke Dantian",260:"Guru yang Menyimpan Rahasia",
                        300:"Murid Resmi Aliran Bayangan"},
    },
    {
        "name": "Ujian Murid Luar", "start": 301, "end": 420,
        "nouns": ["Arena Ujian", "Formasi Jebakan", "Papan Peringkat Murid", "Hutan Ujian",
                  "Batu Ranking", "Aula Pertarungan", "Ruang Strategi", "Menara Pengawas Ujian",
                  "Jurang Ujian Terakhir", "Altar Kelulusan", "Kamp Persiapan", "Gerbang Ujian Akhir"],
        "milestones": {301:"Ujian Naik Status Dimulai",330:"Rival Pertama yang Meremehkan",
                        360:"Formasi Melawan Bakat",390:"Kemenangan yang Mengejutkan Sekte",
                        420:"Murid Dalam Aliran Bayangan"},
    },
    {
        "name": "Rahasia Kuil Bawah Tanah", "start": 421, "end": 540,
        "nouns": ["Kuil Lama", "Ukiran Kuno", "Ruang Tersembunyi", "Altar Retak",
                  "Segel Berdebu", "Lorong Bawah Tanah", "Prasasti Terlupakan", "Cahaya Redup Kuil",
                  "Peti Kuno", "Jejak Wajah Raksasa", "Relief Dinding Kuil", "Sumur Suci Kuil"],
        "milestones": {421:"Kembali ke Kuil yang Menyelamatkannya",450:"Ukiran yang Menyebut Langit",
                        480:"Terobosan Fondasi di Bawah Reruntuhan",510:"Segel yang Belum Bisa Dibuka",
                        540:"Fondasi yang Mengeras Sempurna"},
    },
    {
        "name": "Pasar Hitam Seribu Pusaka", "start": 541, "end": 650,
        "nouns": ["Pasar Hitam", "Lelang Pusaka", "Aula Alkemis", "Jaringan Informan",
                  "Gudang Penyelundup", "Kedai Pil Terlarang", "Ruang Lelang Rahasia", "Rute Dagang Gelap",
                  "Tungku Alkimia", "Buku Resep Kuno", "Gudang Bawah Tanah", "Kios Penukaran Pusaka"],
        "milestones": {541:"Gerbang Pasar Hitam",570:"Jaringan Informan Pertama",
                        600:"Alkimia sebagai Alat Bertahan",630:"Modal yang Tak Terduga Besar",
                        650:"Nama di Kalangan Pedagang Gelap"},
    },
    {
        "name": "Perang Sekte Kecil", "start": 651, "end": 770,
        "nouns": ["Medan Perang Sekte", "Benteng Perbatasan", "Aula Perang", "Formasi Pertahanan",
                  "Pasukan Murid", "Markas Musuh", "Garis Depan", "Ruang Komando",
                  "Jembatan Terputus", "Puncak Bukit Pertempuran", "Parit Pertahanan", "Reruntuhan Benteng"],
        "milestones": {651:"Perang Sekte Kecil Dimulai",680:"Strategi Pertama yang Menang",
                        710:"Korban yang Tak Terlupakan",740:"Puncak Fondasi di Tengah Perang",
                        770:"Perdamaian yang Dibeli dengan Darah"},
    },
    {
        "name": "Menara Ujian Inti", "start": 771, "end": 900,
        "nouns": ["Menara Ujian Inti", "Ruang Terobosan", "Altar Petir Sembilan", "Aula Persiapan",
                  "Pilar Ujian", "Ruang Meditasi Terakhir", "Gerbang Inti", "Jurang Tribulasi",
                  "Batu Pengukur Bakat", "Puncak Menara", "Ruang Isolasi Meditasi", "Halaman Petir"],
        "milestones": {771:"Ambang Pembentukan Inti",800:"Sembilan Gelegar yang Ditunggu",
                        830:"Bertahan dari Petir Fondasi",860:"Inti Emas yang Terbentuk",
                        900:"Elit Sejati yang Diakui"},
    },
    {
        "name": "Kota Terlarang Beku", "start": 901, "end": 1030,
        "nouns": ["Kota Terlarang Beku", "Reruntuhan Peradaban Kuno", "Es Abadi", "Patung Beku",
                  "Makam Tersembunyi", "Aula Es", "Jejak Ras Purba", "Lorong Beku",
                  "Altar yang Terkubur Es", "Puncak Gunung Es", "Danau Beku", "Istana Es Runtuh"],
        "milestones": {901:"Gerbang Kota Beku",930:"Jejak Peradaban yang Hilang",
                        960:"Makam Ras Purba",990:"Rahasia di Balik Es Abadi",
                        1030:"Pulang Membawa Sejarah Terkubur"},
    },
    {
        "name": "Bayangan di Balik Langit", "start": 1031, "end": 1150,
        "nouns": ["Bayangan di Langit", "Petunjuk yang Terserak", "Organisasi Tersembunyi", "Utusan Tanpa Wajah",
                  "Catatan Terlarang", "Peta Rahasia Langit", "Simbol Berulang", "Saksi Bisu",
                  "Jejak yang Disembunyikan", "Kabut Kebenaran", "Ruang Arsip Terlarang", "Menara Pengintai Langit"],
        "milestones": {1031:"Petunjuk Pertama tentang Wajah Raksasa",1060:"Bukan Bencana Alam",
                        1090:"Utusan dari Balik Langit",1120:"Puncak Pembentukan Inti",
                        1150:"Kebenaran yang Mulai Terlihat"},
    },
    {
        "name": "Gerbang Roh Bayi", "start": 1151, "end": 1270,
        "nouns": ["Gerbang Roh Bayi", "Dao Pribadi", "Ruang Perenungan", "Altar Iblis Hati",
                  "Bayangan Ketakutan Sendiri", "Cermin Batin", "Puncak Pencerahan", "Jurang Keraguan",
                  "Ambang Kegilaan", "Cahaya Roh Bayi", "Labirin Batin", "Kolam Perenungan"],
        "milestones": {1151:"Ambang Roh Bayi",1180:"Iblis Hati Menyerang",
                        1210:"Trauma Masa Kecil yang Kembali",1240:"Dao Perhitungan Ditemukan",
                        1270:"Roh Bayi yang Lahir dari Tekad"},
    },
    {
        "name": "Penjaga Perbatasan Benua", "start": 1271, "end": 1400,
        "nouns": ["Perbatasan Benua", "Aliansi Kerajaan", "Aula Perundingan", "Pasukan Penjaga",
                  "Wilayah Sengketa", "Menara Pengawas Benua", "Utusan Kerajaan", "Perjanjian Lama",
                  "Garis Pertahanan Terakhir", "Ibukota Kerajaan", "Ruang Singgasana", "Jalur Karavan Benua"],
        "milestones": {1271:"Diakui sebagai Kekuatan Regional",1300:"Utusan Kerajaan yang Mendekat",
                        1330:"Benih Ikatan yang Akan Tumbuh",1360:"Perbatasan yang Dipertaruhkan",
                        1400:"Nama Lin Xuan di Seluruh Benua"},
    },
    {
        "name": "Fajar Penolak Langit", "start": 1401, "end": 1500,
        "nouns": ["Puncak Roh Bayi", "Reruntuhan Kemenangan", "Langit Season Baru", "Ambang Dunia Lebih Besar",
                  "Fajar Terakhir Season", "Bayangan yang Menunggu", "Gerbang Dunia Baru", "Puncak Perjalanan Pertama",
                  "Medan Pertempuran Terakhir", "Cakrawala yang Menanti"],
        "milestones": {1401:"Menuju Puncak Roh Bayi",1430:"Pertempuran Penutup Season",
                        1460:"Reruntuhan yang Kini Miliknya",1490:"Berdiri Sendirian di Puncak",
                        1500:"Fajar Penolak Langit"},
    },
]

TEMPLATES = [
    lambda v, n: f"{v} {n}",
    lambda e, n: f"{e} di {n}",
    lambda n, e: f"{n} dan {e}",
]

WINDOW = 10  # jangan pakai kata/frasa yang sama dalam 10 bab terakhir

def build_arc_titles(arc):
    n_chapters = arc["end"] - arc["start"] + 1
    nouns = arc["nouns"]
    recent_words = []  # sliding window kata yang baru dipakai (verb/emotion/noun)
    recent_titles = set()
    titles = {}

    verbs_pool = VERBS.copy()
    emotions_pool = EMOTIONS.copy()
    random.shuffle(verbs_pool)
    random.shuffle(emotions_pool)

    for offset in range(n_chapters):
        idx = arc["start"] + offset
        if idx in arc["milestones"]:
            titles[idx] = arc["milestones"][idx]
            continue

        # coba sampai dapat kombinasi yang tidak memakai kata dari window terakhir
        for attempt in range(60):
            template_type = random.choice(["v", "e1", "e2"])
            noun = random.choice(nouns)
            if template_type == "v":
                word = random.choice(verbs_pool)
                title = f"{word} {noun}"
                key_words = [word, noun]
            elif template_type == "e1":
                word = random.choice(emotions_pool)
                title = f"{word} di {noun}"
                key_words = [word, noun]
            else:
                word = random.choice(emotions_pool)
                title = f"{noun} dan {word}"
                key_words = [word, noun]

            if title in recent_titles:
                continue
            if any(w in recent_words for w in key_words) and attempt < 55:
                continue
            break

        titles[idx] = title
        recent_titles.add(title)
        recent_words.extend(key_words)
        recent_words = recent_words[-WINDOW * 2:]

    return titles

lines = []
lines.append("# JALAN ABADI PENOLAK LANGIT\n## DAFTAR BAB SEASON 1 — \"Reruntuhan dan Fondasi\" (Bab 1–1500)\n")
lines.append("*Versi 2: kosakata diperluas + anti-pengulangan kata dalam jendela 10 bab, supaya judul terasa lebih beragam.*\n")

for arc in arcs:
    lines.append(f"\n---\n### ARC: {arc['name']} (Bab {arc['start']}–{arc['end']})\n")
    titles = build_arc_titles(arc)
    for idx in range(arc["start"], arc["end"] + 1):
        marker = " **[MILESTONE]**" if idx in arc["milestones"] else ""
        lines.append(f"Bab {idx}: {titles[idx]}{marker}")

output_path = os.path.expanduser("~/chapters_raw.txt")
with open(output_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print("done", len(lines))
print("Tersimpan di:", output_path)
