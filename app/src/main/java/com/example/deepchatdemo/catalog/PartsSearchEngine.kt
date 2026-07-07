package com.example.deepchatdemo.catalog

import android.util.Log
import java.text.Normalizer
import java.util.Locale

object PartsSearchEngine {
    fun search(parts: List<PartItem>, plan: SearchPlan): List<ScoredPartItem> {
        val limit = plan.safeLimit.takeIf { it > 0 } ?: SearchPlan.DEFAULT_LIMIT
        val scored = parts.asSequence()
            .mapNotNull { part -> scorePart(IndexedPart(part), plan) }
            .sortedWith(
                compareByDescending<ScoredPartItem> { it.score }
                    .thenBy { it.part.code.length }
                    .thenBy { it.part.code }
            )
            .take(limit.coerceAtMost(SearchPlan.MAX_LIMIT))
            .toList()

        val topScore = scored.firstOrNull()?.score ?: 0
        val results = if (topScore < MIN_EFFECTIVE_SCORE) emptyList() else scored
        Log.d(
            TAG,
            "Parts search complete: rawMatches=${scored.size}, results=${results.size}, topScore=$topScore"
        )
        return results
    }

    fun simpleSearch(
        parts: List<PartItem>,
        rawQuery: String,
        limit: Int = SearchPlan.DEFAULT_LIMIT
    ): List<ScoredPartItem> {
        val plan = createSimplePlan(rawQuery, limit)
        return if (plan.isPartsIntent()) {
            search(parts, plan)
        } else {
            Log.d(TAG, "Parts simpleSearch skipped: query did not look like a parts request")
            emptyList()
        }
    }

    fun createSimplePlan(
        rawQuery: String,
        limit: Int = SearchPlan.DEFAULT_LIMIT
    ): SearchPlan {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) {
            return SearchPlan.generalChat(rawQuery = rawQuery, confidence = 0.2)
        }

        val alnumTokens = ALNUM_TOKEN_REGEX.findAll(trimmed)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeCompact(it) }
            .toList()
        val codes = CODE_WITH_SEPARATOR_REGEX.findAll(trimmed)
            .map { it.value.trim() }
            .filter { it.any(Char::isDigit) }
            .filter { normalizeCompact(it).length >= 6 }
            .distinctBy { normalizeCompact(it) }
            .toList()
        val codePrefixes = buildList {
            addAll(codes.mapNotNull { codePrefix(it) })
            addAll(
                alnumTokens
                    .filter { it.any(Char::isDigit) && normalizeCompact(it).length >= 4 }
                    .map { it.trim() }
            )
        }.distinctBy { normalizeCompact(it) }
        val cjkTerms = CJK_TERM_REGEX.findAll(trimmed)
            .map { it.value.trim() }
            .filter { it.length >= 2 }
            .toList()
        val englishTerms = englishPartTerms(trimmed)
        val synonyms = synonymTerms(trimmed)

        val models = alnumTokens
            .filter { token ->
                val normalized = normalizeCompact(token)
                (normalized.any(Char::isDigit) && normalized.length in 4..12) ||
                    normalized in KNOWN_SHORT_MODELS
            }
            .distinctBy { normalizeCompact(it) }
        val brands = alnumTokens
            .filter { normalizeCompact(it) in KNOWN_BRANDS }
            .distinctBy { normalizeCompact(it) }

        val partNamesCn = (cjkTerms + synonyms.chinese)
            .filterNot { looksLikeQuestionOnly(it) }
            .distinctBy { normalizeCompact(it) }
        val partNamesEn = (englishTerms + synonyms.english)
            .distinctBy { normalizeCompact(it) }
        val keywords = (alnumTokens + partNamesCn + partNamesEn)
            .filter { it.length >= 2 }
            .distinctBy { normalizeCompact(it) }

        val looksLikeParts = codes.isNotEmpty() ||
            codePrefixes.isNotEmpty() ||
            models.isNotEmpty() ||
            partNamesCn.isNotEmpty() ||
            partNamesEn.isNotEmpty() ||
            PARTS_HINT_REGEX.containsMatchIn(trimmed)

        if (!looksLikeParts) {
            return SearchPlan.generalChat(rawQuery = rawQuery, confidence = 0.45)
        }

        val queryType = when {
            PRICE_HINT_REGEX.containsMatchIn(trimmed) -> SearchPlan.QUERY_PRICE
            codes.isNotEmpty() || codePrefixes.isNotEmpty() -> SearchPlan.QUERY_CODE
            models.isNotEmpty() && partNamesCn.isEmpty() && partNamesEn.isEmpty() -> SearchPlan.QUERY_MODEL
            partNamesCn.isNotEmpty() || partNamesEn.isNotEmpty() -> SearchPlan.QUERY_PART_NAME
            brands.isNotEmpty() -> SearchPlan.QUERY_BRAND
            else -> SearchPlan.QUERY_UNKNOWN
        }
        val intent = when {
            queryType == SearchPlan.QUERY_PRICE -> SearchPlan.INTENT_PRICE_LOOKUP
            queryType == SearchPlan.QUERY_MODEL -> SearchPlan.INTENT_MODEL_PARTS_LOOKUP
            else -> SearchPlan.INTENT_PART_LOOKUP
        }

        return SearchPlan(
            intent = intent,
            queryType = queryType,
            rawQuery = rawQuery,
            codes = codes,
            codePrefixes = codePrefixes,
            models = models,
            brands = brands,
            partNamesCn = partNamesCn,
            partNamesEn = partNamesEn,
            keywords = keywords,
            mustHave = (codes + models).distinctBy { normalizeCompact(it) },
            shouldHave = (partNamesCn + partNamesEn + brands).distinctBy { normalizeCompact(it) },
            limit = limit.coerceIn(0, SearchPlan.MAX_LIMIT),
            confidence = 0.35
        )
    }

    private fun scorePart(index: IndexedPart, plan: SearchPlan): ScoredPartItem? {
        var score = 0
        val matches = mutableListOf<String>()

        plan.codes.normalizedTerms().forEach { code ->
            val rawCode = normalizeSpaced(code)
            val compactCode = normalizeCompact(code)
            when {
                rawCode.isNotBlank() && normalizeSpaced(index.part.code) == rawCode -> {
                    score += 200
                    matches.add("code exact:$code")
                }
                compactCode.isNotBlank() && index.codeCompact == compactCode -> {
                    score += 180
                    matches.add("code normalized:$code")
                }
                compactCode.length >= 4 && index.codeCompact.contains(compactCode) -> {
                    score += 100
                    matches.add("code fragment:$code")
                }
            }
        }

        plan.codePrefixes.normalizedTerms().forEach { prefix ->
            val compactPrefix = normalizeCompact(prefix)
            if (compactPrefix.length >= 3 && index.codeCompact.startsWith(compactPrefix)) {
                score += 150
                matches.add("code prefix:$prefix")
            } else if (compactPrefix.length >= 4 && index.codeCompact.contains(compactPrefix)) {
                score += 100
                matches.add("code contains:$prefix")
            }
        }

        codeFragments(plan).forEach { fragment ->
            val compactFragment = normalizeCompact(fragment)
            if (
                compactFragment.length >= 4 &&
                index.codeCompact.contains(compactFragment) &&
                matches.none { it.endsWith(":$fragment") }
            ) {
                score += 100
                matches.add("code fragment:$fragment")
            }
        }

        val mustTerms = plan.mustHave.normalizedTerms()
        if (mustTerms.isNotEmpty() && mustTerms.all { index.containsMustTerm(it, plan) }) {
            score += 80
            matches.add("mustHave:${mustTerms.joinToString("|")}")
        }

        plan.models.normalizedTerms().forEach { model ->
            if (index.containsModel(model)) {
                score += 70
                matches.add("model:$model")
            }
        }

        plan.partNamesCn.normalizedTerms().forEach { name ->
            if (index.containsNameCn(name) || index.containsAnyText(name)) {
                score += 60
                matches.add("nameCn:$name")
            }
        }

        plan.partNamesEn.normalizedTerms().forEach { name ->
            if (index.containsNameEn(name) || index.containsAnyText(name)) {
                score += 55
                matches.add("nameEn:$name")
            }
        }

        plan.shouldHave.normalizedTerms().forEach { term ->
            if (index.containsAnyText(term)) {
                score += 35
                matches.add("shouldHave:$term")
            }
        }

        plan.brands.normalizedTerms().forEach { brand ->
            if (index.containsBrand(brand)) {
                score += 25
                matches.add("brand:$brand")
            }
        }

        val fieldTerms = (plan.keywords + plan.mustHave + plan.shouldHave +
            plan.partNamesCn + plan.partNamesEn + plan.models + plan.brands)
            .normalizedTerms()
            .filter { normalizeCompact(it).length >= 2 }

        fieldTerms.forEach { term ->
            if (index.containsSpec(term)) {
                score += 20
                matches.add("spec:$term")
            }
            if (index.containsRemark(term)) {
                score += 10
                matches.add("remark:$term")
            }
        }

        return if (score > 0) {
            ScoredPartItem(index.part, score, matches.distinct())
        } else {
            null
        }
    }

    private fun codeFragments(plan: SearchPlan): List<String> {
        return (plan.keywords + plan.mustHave + plan.shouldHave)
            .filter { term ->
                val compact = normalizeCompact(term)
                compact.any(Char::isDigit) && compact.length >= 4
            }
            .distinctBy { normalizeCompact(it) }
    }

    private fun List<String>.normalizedTerms(): List<String> {
        return map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeCompact(it) }
    }

    private fun IndexedPart.containsAnyText(term: String): Boolean {
        return containsNormalized(searchSpaced, searchCompact, term)
    }

    private fun IndexedPart.containsMustTerm(term: String, plan: SearchPlan): Boolean {
        val compactTerm = normalizeCompact(term)
        return when {
            plan.models.any { normalizeCompact(it) == compactTerm } -> containsModel(term)
            plan.brands.any { normalizeCompact(it) == compactTerm } -> containsBrand(term)
            plan.codes.any { normalizeCompact(it) == compactTerm } -> codeCompact == compactTerm
            plan.codePrefixes.any { normalizeCompact(it) == compactTerm } -> {
                codeCompact.startsWith(compactTerm) || codeCompact.contains(compactTerm)
            }
            else -> containsAnyText(term)
        }
    }

    private fun IndexedPart.containsModel(term: String): Boolean {
        return containsNormalized(modelsSpaced, modelsCompact, term)
    }

    private fun IndexedPart.containsNameCn(term: String): Boolean {
        return containsNormalized(nameCnSpaced, nameCnCompact, term)
    }

    private fun IndexedPart.containsNameEn(term: String): Boolean {
        return containsNormalized(nameEnSpaced, nameEnCompact, term)
    }

    private fun IndexedPart.containsBrand(term: String): Boolean {
        return containsNormalized(brandSpaced, brandCompact, term)
    }

    private fun IndexedPart.containsSpec(term: String): Boolean {
        return containsNormalized(specSpaced, specCompact, term)
    }

    private fun IndexedPart.containsRemark(term: String): Boolean {
        return containsNormalized(remarkSpaced, remarkCompact, term)
    }

    private fun containsNormalized(spacedText: String, compactText: String, term: String): Boolean {
        val spacedTerm = normalizeSpaced(term)
        val compactTerm = normalizeCompact(term)
        return (spacedTerm.isNotBlank() && spacedText.contains(spacedTerm)) ||
            (compactTerm.isNotBlank() && compactText.contains(compactTerm))
    }

    private fun normalizeSpaced(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT)
            .replace(SEPARATOR_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeCompact(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT)
            .replace(SEPARATOR_REGEX, "")
            .trim()
    }

    private fun codePrefix(code: String): String? {
        val parts = normalizeSpaced(code).split(" ").filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.take(2).joinToString("-") else null
    }

    private fun englishPartTerms(rawQuery: String): List<String> {
        val tokens = Regex("[A-Za-z]+").findAll(rawQuery)
            .map { it.value.lowercase(Locale.ROOT) }
            .filter { it !in ENGLISH_STOP_WORDS }
            .toList()
        val terms = mutableListOf<String>()
        tokens.forEach { terms.add(it) }
        tokens.windowed(2).forEach { terms.add(it.joinToString(" ")) }
        tokens.windowed(3).forEach { terms.add(it.joinToString(" ")) }
        return terms.distinct()
    }

    private fun synonymTerms(rawQuery: String): SynonymTerms {
        val normalized = normalizeCompact(rawQuery)
        val chinese = mutableListOf<String>()
        val english = mutableListOf<String>()

        PART_SYNONYM_GROUPS.forEach { group ->
            if (group.matches(rawQuery, normalized)) {
                chinese.addAll(group.chinese)
                english.addAll(group.english)
            }
        }

        return SynonymTerms(chinese.distinct(), english.distinct())
    }

    private fun looksLikeQuestionOnly(value: String): Boolean {
        return value in listOf("是什么", "有没有", "多少钱", "有哪些", "这个编码", "什么")
    }

    private data class SynonymTerms(
        val chinese: List<String>,
        val english: List<String>
    )

    private data class SynonymGroup(
        val chinese: List<String>,
        val english: List<String>,
        val triggers: List<String> = chinese + english
    )

    private fun SynonymGroup.matches(rawQuery: String, normalizedQuery: String): Boolean {
        return triggers.any { trigger ->
            if (trigger.any { it in '\u4E00'..'\u9FFF' }) {
                trigger in rawQuery
            } else {
                normalizeCompact(trigger) in normalizedQuery
            }
        }
    }

    private data class IndexedPart(
        val part: PartItem,
        val codeCompact: String = normalizeCompact(part.code),
        val searchSpaced: String = normalizeSpaced(part.searchText),
        val searchCompact: String = normalizeCompact(part.searchText),
        val modelsSpaced: String = normalizeSpaced(part.models),
        val modelsCompact: String = normalizeCompact(part.models),
        val brandSpaced: String = normalizeSpaced(part.brand),
        val brandCompact: String = normalizeCompact(part.brand),
        val specSpaced: String = normalizeSpaced(part.spec),
        val specCompact: String = normalizeCompact(part.spec),
        val remarkSpaced: String = normalizeSpaced(part.remark),
        val remarkCompact: String = normalizeCompact(part.remark),
        val nameCnSpaced: String = normalizeSpaced(part.nameCn),
        val nameCnCompact: String = normalizeCompact(part.nameCn),
        val nameEnSpaced: String = normalizeSpaced(part.nameEn),
        val nameEnCompact: String = normalizeCompact(part.nameEn)
    )

    private const val TAG = "HighTacAI"
    private const val MIN_EFFECTIVE_SCORE = 30

    private val SEPARATOR_REGEX = Regex("[\\s\\-_/\\\\.,;:，。；：、|()（）\\[\\]{}]+")
    private val ALNUM_TOKEN_REGEX = Regex("[A-Za-z0-9]+")
    private val CJK_TERM_REGEX = Regex("[\\u4E00-\\u9FFF]{2,}")
    private val CODE_WITH_SEPARATOR_REGEX = Regex("[A-Za-z0-9]{2,}(?:[\\s\\-_]+[A-Za-z0-9]{2,}){1,4}")
    private val PRICE_HINT_REGEX = Regex("(多少钱|价格|售价|报价|单价|price|cost)", RegexOption.IGNORE_CASE)
    private val PARTS_HINT_REGEX = Regex(
        "(编码|配件|产品|车型|有没有|起动|启动|马达|刹车|制动|皮带|离合器|普利|空气滤|空滤|机油滤|油滤|火花塞|灯泡|大灯|前灯|尾灯|转向灯|方向灯|仪表|后视镜|反光镜|电门锁|主锁|锁套|线束|继电器|整流器|稳压器|外壳|塑料件|挡泥板|坐垫|座垫|拉杆|刹车泵|减震器|轮胎|电池|蓄电池|brake|disc|disk|pad|starter|filter|motor|belt|clutch|light|signal|mirror|lock|harness|relay|regulator|rectifier|cover|fender|seat|shock|tire|battery)",
        RegexOption.IGNORE_CASE
    )
    private val KNOWN_SHORT_MODELS = setOf("VMS")
    private val KNOWN_BRANDS = setOf("SYM", "YAMAHA", "VMS", "HIGHTAC")
    private val PART_SYNONYM_GROUPS = listOf(
        SynonymGroup(
            chinese = listOf("起动马达", "启动马达", "启动电机", "起动机", "马达"),
            english = listOf("starter motor"),
            triggers = listOf("起动", "启动", "马达", "starter", "starter motor")
        ),
        SynonymGroup(
            chinese = listOf("刹车盘", "制动盘", "碟刹盘"),
            english = listOf("brake disc", "brake disk")
        ),
        SynonymGroup(
            chinese = listOf("刹车片", "制动片"),
            english = listOf("brake pad")
        ),
        SynonymGroup(
            chinese = listOf("皮带", "传动皮带"),
            english = listOf("belt")
        ),
        SynonymGroup(
            chinese = listOf("离合器"),
            english = listOf("clutch")
        ),
        SynonymGroup(
            chinese = listOf("普利盘", "普利珠"),
            english = emptyList(),
            triggers = listOf("普利盘", "普利珠", "普利")
        ),
        SynonymGroup(
            chinese = listOf("空气滤芯", "空气滤清器", "空滤"),
            english = listOf("air filter"),
            triggers = listOf("空气滤", "空气滤芯", "空气滤清器", "空滤", "air filter")
        ),
        SynonymGroup(
            chinese = listOf("机油滤芯", "油滤"),
            english = listOf("oil filter"),
            triggers = listOf("机油滤", "机油滤芯", "油滤", "oil filter")
        ),
        SynonymGroup(
            chinese = listOf("火花塞"),
            english = listOf("spark plug")
        ),
        SynonymGroup(
            chinese = listOf("灯泡"),
            english = emptyList()
        ),
        SynonymGroup(
            chinese = listOf("大灯", "前灯"),
            english = listOf("head light", "headlight")
        ),
        SynonymGroup(
            chinese = listOf("尾灯"),
            english = listOf("tail light", "taillight")
        ),
        SynonymGroup(
            chinese = listOf("转向灯", "方向灯"),
            english = listOf("turn signal")
        ),
        SynonymGroup(
            chinese = listOf("仪表"),
            english = emptyList()
        ),
        SynonymGroup(
            chinese = listOf("后视镜", "反光镜"),
            english = listOf("mirror")
        ),
        SynonymGroup(
            chinese = listOf("电门锁", "主锁", "锁套"),
            english = listOf("main lock", "main key"),
            triggers = listOf("电门锁", "主锁", "锁套", "main lock", "main key")
        ),
        SynonymGroup(
            chinese = listOf("线束"),
            english = listOf("wire harness")
        ),
        SynonymGroup(
            chinese = listOf("继电器", "启动继电器"),
            english = listOf("relay", "starter relay"),
            triggers = listOf("继电器", "启动继电器", "relay", "starter relay")
        ),
        SynonymGroup(
            chinese = listOf("整流器", "稳压器"),
            english = listOf("rectifier", "regulator")
        ),
        SynonymGroup(
            chinese = listOf("外壳", "塑料件"),
            english = listOf("cover", "body cover")
        ),
        SynonymGroup(
            chinese = listOf("挡泥板"),
            english = listOf("fender")
        ),
        SynonymGroup(
            chinese = listOf("坐垫", "座垫"),
            english = listOf("seat")
        ),
        SynonymGroup(
            chinese = listOf("刹车手柄", "拉杆"),
            english = listOf("brake lever")
        ),
        SynonymGroup(
            chinese = listOf("刹车泵"),
            english = listOf("brake pump")
        ),
        SynonymGroup(
            chinese = listOf("减震器"),
            english = listOf("shock absorber")
        ),
        SynonymGroup(
            chinese = listOf("轮胎"),
            english = listOf("tire")
        ),
        SynonymGroup(
            chinese = listOf("电池", "蓄电池"),
            english = listOf("battery")
        )
    )
    private val ENGLISH_STOP_WORDS = setOf(
        "is",
        "are",
        "a",
        "an",
        "the",
        "what",
        "how",
        "much",
        "do",
        "does",
        "have",
        "with",
        "for",
        "of",
        "and",
        "or"
    )
}
