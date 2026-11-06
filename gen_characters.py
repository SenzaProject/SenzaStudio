import random
random.seed(7)

surnames = ["Lin","Xuan","Mu","Wen","Zhao","Chen","Han","Feng","Su","Ye","Bai","Qin","Jiang",
            "Luo","Xiao","Gu","Fang","Shen","Tang","Yun","Song","Zhou","Meng","Duan","Rong",
            "Xia","Yin","Cui","Pei","Kong","Sima","Ouyang","Murong","Nangong","Situ"]

given_male = ["Feng","Hao","Yu","Chen","Kai","Ming","Tian","Long","Yan","Xiao","Fei","Rui",
              "Zhan","Xing","Jun","Bo","Han","Lie","Qi","Wei","Yang","Zheng","Cang","Yao"]
given_female = ["Yue","Xue","Ling","Ruo","Mei","Yan","Xin","Qing","Ning","Rou","Zhu","Xi",
                 "Yun","Yao","Wan","Jiao","Lian","Shu","Rui","Ping","Hui","Zi","Xian","Bao"]

titles_flavor = ["", "", "", " Si Pedang Cepat", " Sang Peramal", " Si Tangan Besi",
                  " Penjaga Formasi", " Si Alkemis Muda", " Pewaris Klan", " Si Api Biru"]

realms = ["Pemurnian Tubuh","Pemurnian Qi","Pembentukan Fondasi","Pembentukan Inti","Roh Bayi",
          "Transformasi Jiwa","Pemurnian Kehampaan","Penyatuan Tubuh","Mahayana","Dewa Sejati",
          "Dewa Emas","Da Luo Immortal","Suci","Dewa Agung","Penguasa Kekacauan"]

stages = ["Awal","Menengah","Puncak","Sempurna"]

affiliations = ["Aliran Bayangan","Sekte Pedang Kabut Utara","Klan Mu","Klan Wen",
                "Sekte Petir Sembilan Langit","Kerajaan Yanwu","Sekte Naga Utara",
                "Sekte Phoenix Selatan","Kekaisaran Langit Tenggelam","Aliansi Sepuluh Sekte",
                "Dewan Bintang","Kota Heixuan","Pasar Hitam Seribu Pusaka","Klan Independen",
                "Penjaga Perbatasan Benua"]

roles = ["murid dalam","tetua sekte","kepala keluarga","pedagang pusaka","alkemis pengembara",
         "prajurit bayaran","pembentuk formasi","penjaga kuil kuno","utusan kerajaan",
         "pemburu iblis","perawat binatang roh","panglima perang sekte","pewaris klan",
         "mata-mata sekte rival","penulis kitab kultivasi","penjaga perbatasan"]

backgrounds = [
    "kehilangan keluarganya dalam perang sekte kecil dan bersumpah membangun kembali namanya",
    "lahir dari klan besar namun dibuang karena akar spiritual lemah, mirip dengan jalan Lin Xuan",
    "dulunya budak tambang yang naik status lewat kerja keras dan keberuntungan kecil",
    "murid kesayangan seorang guru yang menghilang misterius sebelum ajarannya selesai",
    "pewaris tunggal sebuah teknik terlarang yang diwariskan turun-temurun dalam kerahasiaan",
    "diselamatkan Lin Xuan dari serangan iblis rendahan saat masih murid luar",
    "tumbuh di pasar hitam, belajar bertahan hidup lewat kelicikan sebelum bertemu Lin Xuan",
    "anak dari tetua sekte yang tewas dalam perang perbatasan, mewarisi dendam yang belum selesai",
    "kultivator kesepian yang mengembara mencari makna setelah kehilangan seluruh sektenya",
    "mantan rival Lin Xuan di ujian murid luar yang akhirnya mengakui kemampuannya",
    "keturunan campuran darah binatang roh, disisihkan komunitasnya karena dianggap aneh",
    "korban selamat dari reruntuhan Kota Terlarang Beku yang membawa pengetahuan kuno",
]

turning_points = [
    ("mati", "gugur melindungi Lin Xuan saat Perang Sekte Kecil, jadi alasan MC membenci perang yang sia-sia"),
    ("mati", "tewas dalam Tribulasi terobosan yang gagal, meninggalkan pesan terakhir untuk Lin Xuan"),
    ("mati", "dibunuh mata-mata sekte rival, kematiannya memicu penyelidikan yang membuka Arc berikutnya"),
    ("berpisah", "memilih jalan berbeda setelah perbedaan prinsip soal Larangan/tabu yang dipakai Lin Xuan"),
    ("berpisah", "kembali ke klan asalnya untuk memenuhi tanggung jawab keluarga yang tak bisa ditinggalkan"),
    ("berpisah", "diculik/disegel musuh dan baru muncul kembali di Season berikutnya dengan status berubah"),
    ("berkhianat", "berbalik memihak musuh karena diperas lewat ancaman terhadap keluarganya"),
    ("bertahan", "tetap bersama Lin Xuan hingga akhir Season, jadi salah satu sahabat inti di Season 2"),
]

def make_name(gender):
    surname = random.choice(surnames)
    given = random.choice(given_male if gender == "M" else given_female)
    flavor = random.choice(titles_flavor)
    return f"{surname} {given}{flavor}"

def make_realm(min_idx, max_idx):
    idx = random.randint(min_idx, max_idx)
    realm = realms[idx]
    stage = random.choice(stages)
    return f"{realm} Tahap {stage}"

N = 380
lines = []
lines.append("# JALAN ABADI PENOLAK LANGIT\n## DAFTAR KARAKTER PENDUKUNG (±380 karakter)\n")
lines.append("*Format: Nama — Afiliasi — Tingkat Kultivasi — Peran — Latar Belakang — Titik Balik*\n")
lines.append("*Tingkat kultivasi di sini adalah level saat pertama muncul; sebagian naik seiring cerita berjalan.*\n")

used_names = set()
entries = []
for i in range(N):
    gender = random.choice(["M","F"])
    name = make_name(gender)
    while name in used_names:
        name = make_name(gender)
    used_names.add(name)

    # earlier characters skew lower realm, later ones can be higher (season spread)
    if i < N * 0.55:
        realm = make_realm(0, 4)   # Season 1 range
    elif i < N * 0.85:
        realm = make_realm(3, 9)   # Season 2 range
    else:
        realm = make_realm(8, 14)  # Season 3 range

    affil = random.choice(affiliations)
    role = random.choice(roles)
    bg = random.choice(backgrounds)
    tp_type, tp_desc = random.choice(turning_points)

    entries.append(f"{i+1}. **{name}** — {affil}, {role} — *{realm}*\n   Latar belakang: {bg}.\n   Titik balik: ({tp_type}) {tp_desc}.")

import os
output_path = os.path.expanduser("~/characters_raw.txt")
with open(output_path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n\n" + "\n\n".join(entries))

print("done", N)
print("Tersimpan di:", output_path)
