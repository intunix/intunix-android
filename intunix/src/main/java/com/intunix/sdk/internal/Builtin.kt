package com.intunix.sdk.internal

/** Built-in texts per language; port of @intunix/core i18n/builtin.ts (names are the TS field names). */
internal data class BuiltinTexts(
    val submit: String, val next: String, val back: String, val done: String,
    /** Templates with {n} and {total}. */
    val pageOf: String, val questionOf: String,
    val thanksTitle: String, val thanksSubtitle: String,
    val npsLow: String, val npsHigh: String, val enpsLow: String, val enpsHigh: String, val cesLow: String, val cesHigh: String,
    val csat: List<String>,
    /** Keys: very, somewhat, not. */
    val pmf: Map<String, String>,
    val send: String, val skip: String, val end: String, val typing: String, val log: String,
    /** Placeholder of a free-text answer box. */
    val answer: String, val language: String, val close: String,
)

private fun pmfLabel(v: String) = Defaults.PMF.first { it.value == v }.label

private val EN = BuiltinTexts(
    submit = Defaults.SUBMIT, next = Defaults.NEXT, back = Defaults.BACK, done = "Done",
    pageOf = "Page {n} of {total}", questionOf = "Question {n} of {total}",
    thanksTitle = Defaults.THANKS_TITLE, thanksSubtitle = Defaults.THANKS_SUBTITLE,
    npsLow = Defaults.NPS.first, npsHigh = Defaults.NPS.second, enpsLow = Defaults.ENPS.first, enpsHigh = Defaults.ENPS.second,
    cesLow = Defaults.CES.first, cesHigh = Defaults.CES.second,
    csat = Defaults.CSAT_LABELS,
    pmf = mapOf("very" to pmfLabel("very"), "somewhat" to pmfLabel("somewhat"), "not" to pmfLabel("not")),
    send = Defaults.CHAT_SEND, skip = Defaults.CHAT_SKIP, end = Defaults.CHAT_END, typing = Defaults.CHAT_TYPING, log = Defaults.CHAT_LOG,
    answer = Defaults.ANSWER, language = "Language", close = "Close",
)

// typing/log carry `{name}` (the assistant's name, never translated; "Nia" by default).
private val TABLE: Map<String, BuiltinTexts> = linkedMapOf(
    "en" to EN,
    "bn" to
    BuiltinTexts(
        submit = "জমা দিন", next = "পরবর্তী", back = "পিছনে", done = "সম্পন্ন",
        pageOf = "পৃষ্ঠা {n} / {total}", questionOf = "প্রশ্ন {n} / {total}",
        thanksTitle = "প্রতিক্রিয়া দেওয়ার জন্য ধন্যবাদ!", thanksSubtitle = "আমরা প্রতিটি উত্তর পড়ি — সত্যিই।",
        npsLow = "সম্ভাবনা কম", npsHigh = "খুবই সম্ভাবনা", enpsLow = "অসম্ভাব্য", enpsHigh = "খুবই সম্ভাব্য",
        cesLow = "খুব কঠিন", cesHigh = "খুব সহজ",
        csat = listOf("খুবই অসন্তুষ্ট", "অসন্তুষ্ট", "নিরপেক্ষ", "সন্তুষ্ট", "খুবই সন্তুষ্ট"),
        pmf = mapOf("very" to "খুব হতাশ হব", "somewhat" to "কিছুটা হতাশ হব", "not" to "হতাশ হব না"),
        send = "পাঠান", skip = "এড়িয়ে যান", end = "কথোপকথন শেষ করুন", typing = "{name} লিখছে", log = "{name}-এর সাথে কথোপকথন",
        answer = "আপনার উত্তর লিখুন…", language = "ভাষা", close = "বন্ধ করুন",
    ),
    "hi" to
    BuiltinTexts(
        submit = "जमा करें", next = "आगे", back = "वापस", done = "पूरा हुआ",
        pageOf = "पृष्ठ {n} / {total}", questionOf = "प्रश्न {n} / {total}",
        thanksTitle = "फ़ीडबैक के लिए धन्यवाद!", thanksSubtitle = "हम हर प्रतिक्रिया पढ़ते हैं — सच में।",
        npsLow = "संभावना कम", npsHigh = "बेहद संभावित", enpsLow = "असंभावित", enpsHigh = "बहुत संभावित",
        cesLow = "बहुत कठिन", cesHigh = "बहुत आसान",
        csat = listOf("बहुत असंतुष्ट", "असंतुष्ट", "तटस्थ", "संतुष्ट", "बहुत संतुष्ट"),
        pmf = mapOf("very" to "बहुत निराश", "somewhat" to "कुछ हद तक निराश", "not" to "निराश नहीं"),
        send = "भेजें", skip = "छोड़ें", end = "बातचीत समाप्त करें", typing = "{name} टाइप कर रही है", log = "{name} के साथ बातचीत",
        answer = "अपना उत्तर लिखें…", language = "भाषा", close = "बंद करें",
    ),
    "ur" to
    BuiltinTexts(
        submit = "جمع کرائیں", next = "اگلا", back = "پیچھے", done = "مکمل",
        pageOf = "صفحہ {n} از {total}", questionOf = "سوال {n} از {total}",
        thanksTitle = "آپ کی رائے کا شکریہ!", thanksSubtitle = "ہم ہر جواب پڑھتے ہیں — واقعی۔",
        npsLow = "امکان کم", npsHigh = "انتہائی امکان", enpsLow = "امکان نہیں", enpsHigh = "بہت امکان",
        cesLow = "بہت مشکل", cesHigh = "بہت آسان",
        csat = listOf("بہت غیر مطمئن", "غیر مطمئن", "غیر جانبدار", "مطمئن", "بہت مطمئن"),
        pmf = mapOf("very" to "بہت مایوس", "somewhat" to "کسی حد تک مایوس", "not" to "مایوس نہیں"),
        send = "بھیجیں", skip = "چھوڑیں", end = "گفتگو ختم کریں", typing = "{name} ٹائپ کر رہی ہے", log = "{name} کے ساتھ گفتگو",
        answer = "اپنا جواب لکھیں…", language = "زبان", close = "بند کریں",
    ),
    "ar" to
    BuiltinTexts(
        submit = "إرسال", next = "التالي", back = "السابق", done = "تم",
        pageOf = "صفحة {n} من {total}", questionOf = "سؤال {n} من {total}",
        thanksTitle = "شكرًا على ملاحظاتك!", thanksSubtitle = "نقرأ كل رد — حقًا.",
        npsLow = "احتمال ضعيف", npsHigh = "محتمل للغاية", enpsLow = "غير محتمل", enpsHigh = "محتمل جدًا",
        cesLow = "صعب جدًا", cesHigh = "سهل جدًا",
        csat = listOf("غير راضٍ إطلاقًا", "غير راضٍ", "محايد", "راضٍ", "راضٍ جدًا"),
        pmf = mapOf("very" to "سأشعر بخيبة أمل كبيرة", "somewhat" to "سأشعر بخيبة أمل نوعًا ما", "not" to "لن أشعر بخيبة أمل"),
        send = "أرسل", skip = "تخطي", end = "إنهاء المحادثة", typing = "{name} تكتب", log = "محادثة مع {name}",
        answer = "اكتب إجابتك…", language = "اللغة", close = "إغلاق",
    ),
    "es" to
    BuiltinTexts(
        submit = "Enviar", next = "Siguiente", back = "Atrás", done = "Listo",
        pageOf = "Página {n} de {total}", questionOf = "Pregunta {n} de {total}",
        thanksTitle = "¡Gracias por tus comentarios!", thanksSubtitle = "Leemos cada respuesta, de verdad.",
        npsLow = "Poco probable", npsHigh = "Muy probable", enpsLow = "Improbable", enpsHigh = "Muy probable",
        cesLow = "Muy difícil", cesHigh = "Muy fácil",
        csat = listOf("Muy insatisfecho", "Insatisfecho", "Neutral", "Satisfecho", "Muy satisfecho"),
        pmf = mapOf("very" to "Muy decepcionado", "somewhat" to "Algo decepcionado", "not" to "Nada decepcionado"),
        send = "Enviar", skip = "Omitir", end = "Finalizar conversación", typing = "{name} está escribiendo", log = "Conversación con {name}",
        answer = "Escribe tu respuesta…", language = "Idioma", close = "Cerrar",
    ),
    "fr" to
    BuiltinTexts(
        submit = "Envoyer", next = "Suivant", back = "Retour", done = "Terminé",
        pageOf = "Page {n} sur {total}", questionOf = "Question {n} sur {total}",
        thanksTitle = "Merci pour votre avis !", thanksSubtitle = "Nous lisons chaque réponse, vraiment.",
        npsLow = "Peu probable", npsHigh = "Extrêmement probable", enpsLow = "Improbable", enpsHigh = "Très probable",
        cesLow = "Très difficile", cesHigh = "Très facile",
        csat = listOf("Très insatisfait", "Insatisfait", "Neutre", "Satisfait", "Très satisfait"),
        pmf = mapOf("very" to "Très déçu", "somewhat" to "Plutôt déçu", "not" to "Pas déçu"),
        send = "Envoyer", skip = "Passer", end = "Terminer la conversation", typing = "{name} est en train d’écrire", log = "Conversation avec {name}",
        answer = "Saisissez votre réponse…", language = "Langue", close = "Fermer",
    ),
    "de" to
    BuiltinTexts(
        submit = "Absenden", next = "Weiter", back = "Zurück", done = "Fertig",
        pageOf = "Seite {n} von {total}", questionOf = "Frage {n} von {total}",
        thanksTitle = "Danke für Ihr Feedback!", thanksSubtitle = "Wir lesen jede Antwort – wirklich.",
        npsLow = "Nicht wahrscheinlich", npsHigh = "Äußerst wahrscheinlich", enpsLow = "Unwahrscheinlich", enpsHigh = "Sehr wahrscheinlich",
        cesLow = "Sehr schwierig", cesHigh = "Sehr einfach",
        csat = listOf("Sehr unzufrieden", "Unzufrieden", "Neutral", "Zufrieden", "Sehr zufrieden"),
        pmf = mapOf("very" to "Sehr enttäuscht", "somewhat" to "Etwas enttäuscht", "not" to "Nicht enttäuscht"),
        send = "Senden", skip = "Überspringen", end = "Unterhaltung beenden", typing = "{name} schreibt", log = "Unterhaltung mit {name}",
        answer = "Ihre Antwort eingeben…", language = "Sprache", close = "Schließen",
    ),
    "pt" to
    BuiltinTexts(
        submit = "Enviar", next = "Próximo", back = "Voltar", done = "Concluído",
        pageOf = "Página {n} de {total}", questionOf = "Pergunta {n} de {total}",
        thanksTitle = "Obrigado pelo seu feedback!", thanksSubtitle = "Lemos todas as respostas — de verdade.",
        npsLow = "Pouco provável", npsHigh = "Extremamente provável", enpsLow = "Improvável", enpsHigh = "Muito provável",
        cesLow = "Muito difícil", cesHigh = "Muito fácil",
        csat = listOf("Muito insatisfeito", "Insatisfeito", "Neutro", "Satisfeito", "Muito satisfeito"),
        pmf = mapOf("very" to "Muito decepcionado", "somewhat" to "Um pouco decepcionado", "not" to "Nada decepcionado"),
        send = "Enviar", skip = "Pular", end = "Encerrar conversa", typing = "{name} está digitando", log = "Conversa com {name}",
        answer = "Digite sua resposta…", language = "Idioma", close = "Fechar",
    ),
    "id" to
    BuiltinTexts(
        submit = "Kirim", next = "Berikutnya", back = "Kembali", done = "Selesai",
        pageOf = "Halaman {n} dari {total}", questionOf = "Pertanyaan {n} dari {total}",
        thanksTitle = "Terima kasih atas masukan Anda!", thanksSubtitle = "Kami membaca setiap tanggapan — sungguh.",
        npsLow = "Kecil kemungkinan", npsHigh = "Sangat mungkin", enpsLow = "Tidak mungkin", enpsHigh = "Sangat mungkin",
        cesLow = "Sangat sulit", cesHigh = "Sangat mudah",
        csat = listOf("Sangat tidak puas", "Tidak puas", "Netral", "Puas", "Sangat puas"),
        pmf = mapOf("very" to "Sangat kecewa", "somewhat" to "Cukup kecewa", "not" to "Tidak kecewa"),
        send = "Kirim", skip = "Lewati", end = "Akhiri percakapan", typing = "{name} sedang mengetik", log = "Percakapan dengan {name}",
        answer = "Ketik jawaban Anda…", language = "Bahasa", close = "Tutup",
    ),
    "tr" to
    BuiltinTexts(
        submit = "Gönder", next = "İleri", back = "Geri", done = "Bitti",
        pageOf = "Sayfa {n} / {total}", questionOf = "Soru {n} / {total}",
        thanksTitle = "Geri bildiriminiz için teşekkürler!", thanksSubtitle = "Her yanıtı okuyoruz — gerçekten.",
        npsLow = "Olası değil", npsHigh = "Son derece olası", enpsLow = "Düşük ihtimal", enpsHigh = "Çok olası",
        cesLow = "Çok zor", cesHigh = "Çok kolay",
        csat = listOf("Çok memnuniyetsiz", "Memnun değil", "Nötr", "Memnun", "Çok memnun"),
        pmf = mapOf("very" to "Çok hayal kırıklığına uğrarım", "somewhat" to "Biraz hayal kırıklığına uğrarım", "not" to "Hayal kırıklığına uğramam"),
        send = "Gönder", skip = "Atla", end = "Görüşmeyi bitir", typing = "{name} yazıyor", log = "{name} ile görüşme",
        answer = "Cevabınızı yazın…", language = "Dil", close = "Kapat",
    ),
    "zh" to
    BuiltinTexts(
        submit = "提交", next = "下一步", back = "返回", done = "完成",
        pageOf = "第 {n} 页，共 {total} 页", questionOf = "第 {n} 题，共 {total} 题",
        thanksTitle = "感谢您的反馈！", thanksSubtitle = "我们会阅读每一条回复，真的。",
        npsLow = "不太可能", npsHigh = "极有可能", enpsLow = "不太可能", enpsHigh = "很有可能",
        cesLow = "非常困难", cesHigh = "非常容易",
        csat = listOf("非常不满意", "不满意", "一般", "满意", "非常满意"),
        pmf = mapOf("very" to "非常失望", "somewhat" to "有些失望", "not" to "不失望"),
        send = "发送", skip = "跳过", end = "结束对话", typing = "{name} 正在输入", log = "与 {name} 的对话",
        answer = "输入您的回答…", language = "语言", close = "关闭",
    ),
    "ja" to
    BuiltinTexts(
        submit = "送信", next = "次へ", back = "戻る", done = "完了",
        pageOf = "{total}ページ中{n}ページ", questionOf = "{total}問中{n}問",
        thanksTitle = "ご意見ありがとうございます！", thanksSubtitle = "お寄せいただいた回答はすべて拝見しています。",
        npsLow = "可能性は低い", npsHigh = "可能性が非常に高い", enpsLow = "可能性は低い", enpsHigh = "とても高い",
        cesLow = "とても難しい", cesHigh = "とても簡単",
        csat = listOf("とても不満", "不満", "どちらでもない", "満足", "とても満足"),
        pmf = mapOf("very" to "とてもがっかり", "somewhat" to "ややがっかり", "not" to "がっかりしない"),
        send = "送る", skip = "スキップ", end = "会話を終了", typing = "{name} が入力中", log = "{name} との会話",
        answer = "回答を入力…", language = "言語", close = "閉じる",
    ),
)

internal val BUILTIN_LANGUAGES: List<String> = TABLE.keys.toList()

internal fun builtinTexts(lang: String): BuiltinTexts = TABLE[matchLanguage(lang, BUILTIN_LANGUAGES) ?: "en"]!!
