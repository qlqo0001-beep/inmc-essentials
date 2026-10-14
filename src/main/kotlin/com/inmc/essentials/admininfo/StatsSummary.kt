package com.inmc.essentials.admininfo

import com.google.gson.JsonObject
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/**
 * `/통계` 의 한 화면(CMI `command.stats` 와 같은 묶음). 값은 접속자면 Bukkit 통계, 아니면 통계 파일(JSON)에서 — 둘 다 [Source] 하나로.
 *
 * Bukkit `Statistic` 의 열쇠는 enum 이름의 소문자라 바닐라 이름(`minecraft:play_time`)과 다르다 — 그래서 아래 [CUSTOM] 표가 둘을 잇는다.
 * 이 서버의 옛 API 에 없는 이름(새 탈것 등)은 건너뛴다(지뢰 25 — 이름으로 찾고 없으면 0).
 */
internal object StatsSummary {

    /** 하나짜리 통계 — (보이는 이름, 바닐라 열쇠, Bukkit 이름, 단위). */
    data class Custom(val label: String, val json: String, val bukkit: String, val unit: Kind)

    enum class Kind { COUNT, TICKS, CM, DAMAGE }

    val CUSTOM: List<Custom> = listOf(
        Custom("플레이 시간", "play_time", "PLAY_ONE_MINUTE", Kind.TICKS),
        Custom("마지막 죽음 뒤", "time_since_death", "TIME_SINCE_DEATH", Kind.TICKS),
        Custom("마지막 잠 뒤", "time_since_rest", "TIME_SINCE_REST", Kind.TICKS),
        Custom("웅크린 시간", "sneak_time", "SNEAK_TIME", Kind.TICKS),
        Custom("나간 횟수", "leave_game", "LEAVE_GAME", Kind.COUNT),
        Custom("죽음", "deaths", "DEATHS", Kind.COUNT),
        Custom("점프", "jump", "JUMP", Kind.COUNT),
        Custom("몹 처치", "mob_kills", "MOB_KILLS", Kind.COUNT),
        Custom("플레이어 처치", "player_kills", "PLAYER_KILLS", Kind.COUNT),
        Custom("준 피해", "damage_dealt", "DAMAGE_DEALT", Kind.DAMAGE),
        Custom("받은 피해", "damage_taken", "DAMAGE_TAKEN", Kind.DAMAGE),
        Custom("방패로 막은 피해", "damage_blocked_by_shield", "DAMAGE_BLOCKED_BY_SHIELD", Kind.DAMAGE),
        Custom("걷기", "walk_one_cm", "WALK_ONE_CM", Kind.CM),
        Custom("달리기", "sprint_one_cm", "SPRINT_ONE_CM", Kind.CM),
        Custom("웅크려 걷기", "crouch_one_cm", "CROUCH_ONE_CM", Kind.CM),
        Custom("수영", "swim_one_cm", "SWIM_ONE_CM", Kind.CM),
        Custom("물 위 걷기", "walk_on_water_one_cm", "WALK_ON_WATER_ONE_CM", Kind.CM),
        Custom("물속 걷기", "walk_under_water_one_cm", "WALK_UNDER_WATER_ONE_CM", Kind.CM),
        Custom("낙하", "fall_one_cm", "FALL_ONE_CM", Kind.CM),
        Custom("오르기", "climb_one_cm", "CLIMB_ONE_CM", Kind.CM),
        Custom("날기", "fly_one_cm", "FLY_ONE_CM", Kind.CM),
        Custom("겉날개", "aviate_one_cm", "AVIATE_ONE_CM", Kind.CM),
        Custom("광산 수레", "minecart_one_cm", "MINECART_ONE_CM", Kind.CM),
        Custom("보트", "boat_one_cm", "BOAT_ONE_CM", Kind.CM),
        Custom("돼지", "pig_one_cm", "PIG_ONE_CM", Kind.CM),
        Custom("말", "horse_one_cm", "HORSE_ONE_CM", Kind.CM),
        Custom("스트라이더", "strider_one_cm", "STRIDER_ONE_CM", Kind.CM),
        Custom("행복한 가스트", "happy_ghast_one_cm", "HAPPY_GHAST_ONE_CM", Kind.CM),
        Custom("앵무조개", "nautilus_one_cm", "NAUTILUS_ONE_CM", Kind.CM),
        Custom("상자 열기", "open_chest", "CHEST_OPENED", Kind.COUNT),
        Custom("엔더 상자 열기", "open_enderchest", "ENDERCHEST_OPENED", Kind.COUNT),
        Custom("셜커 상자 열기", "open_shulker_box", "SHULKER_BOX_OPENED", Kind.COUNT),
        Custom("통 열기", "open_barrel", "OPEN_BARREL", Kind.COUNT),
        Custom("작업대", "interact_with_crafting_table", "CRAFTING_TABLE_INTERACTION", Kind.COUNT),
        Custom("화로", "interact_with_furnace", "FURNACE_INTERACTION", Kind.COUNT),
        Custom("양조기", "interact_with_brewingstand", "BREWINGSTAND_INTERACTION", Kind.COUNT),
        Custom("신호기", "interact_with_beacon", "BEACON_INTERACTION", Kind.COUNT),
        Custom("발사기 살피기", "inspect_dispenser", "DISPENSER_INSPECTED", Kind.COUNT),
        Custom("공급기 살피기", "inspect_dropper", "DROPPER_INSPECTED", Kind.COUNT),
        Custom("깔때기 살피기", "inspect_hopper", "HOPPER_INSPECTED", Kind.COUNT),
        Custom("잠자기", "sleep_in_bed", "SLEEP_IN_BED", Kind.COUNT),
        Custom("마법 부여", "enchant_item", "ITEM_ENCHANTED", Kind.COUNT),
        Custom("낚은 물고기", "fish_caught", "FISH_CAUGHT", Kind.COUNT),
        Custom("주민 거래", "traded_with_villager", "TRADED_WITH_VILLAGER", Kind.COUNT),
        Custom("동물 번식", "animals_bred", "ANIMALS_BRED", Kind.COUNT),
    )

    /** 이동 거리로 더할 것(합계 줄). */
    private val TRAVEL = CUSTOM.filter { it.unit == Kind.CM }

    /** 묶음 통계(아이템·블록·몹마다) — 바닐라 분류 이름. */
    enum class Category(val json: String, val label: String) {
        MINED("mined", "캔 블록"), USED_BLOCKS("used", "놓은 블록"), USED_ITEMS("used", "쓴 아이템"), CRAFTED("crafted", "만든 아이템"),
        BROKEN("broken", "부서진 도구"), PICKED_UP("picked_up", "주운 아이템"), DROPPED("dropped", "버린 아이템"), KILLED("killed", "처치한 몹"),
        KILLED_BY("killed_by", "죽인 몹");
    }

    /** 값을 내주는 곳. */
    interface Source {
        fun custom(c: Custom): Long
        fun category(cat: Category): Long
    }

    /** 접속자 — Bukkit 통계(메인). 블록·아이템 합계는 재료를 다 훑는다(명령 한 번 — 수천 번의 맵 조회). */
    class Online(private val player: Player) : Source {
        override fun custom(c: Custom): Long {
            val stat = runCatching { Statistic.valueOf(c.bukkit) }.getOrNull() ?: return 0
            return runCatching { player.getStatistic(stat).toLong() }.getOrDefault(0)
        }

        override fun category(cat: Category): Long {
            fun sumMaterials(stat: Statistic, pick: (Material) -> Boolean): Long =
                materials.filter(pick).sumOf { m -> runCatching { player.getStatistic(stat, m).toLong() }.getOrDefault(0) }
            fun sumEntities(stat: Statistic): Long =
                entities.sumOf { e -> runCatching { player.getStatistic(stat, e).toLong() }.getOrDefault(0) }
            return when (cat) {
                Category.MINED -> sumMaterials(Statistic.MINE_BLOCK) { it.isBlock }
                Category.USED_BLOCKS -> sumMaterials(Statistic.USE_ITEM) { it.isItem && it.isBlock }
                Category.USED_ITEMS -> sumMaterials(Statistic.USE_ITEM) { it.isItem && !it.isBlock }
                Category.CRAFTED -> sumMaterials(Statistic.CRAFT_ITEM) { it.isItem }
                Category.BROKEN -> sumMaterials(Statistic.BREAK_ITEM) { it.isItem }
                Category.PICKED_UP -> sumMaterials(Statistic.PICKUP) { it.isItem }
                Category.DROPPED -> sumMaterials(Statistic.DROP) { it.isItem }
                Category.KILLED -> sumEntities(Statistic.KILL_ENTITY)
                Category.KILLED_BY -> sumEntities(Statistic.ENTITY_KILLED_BY)
            }
        }

        private companion object {
            @Suppress("DEPRECATION")
            val materials: List<Material> by lazy { Material.entries.filter { !it.isLegacy } }
            val entities: List<EntityType> by lazy { EntityType.entries.filter { it != EntityType.UNKNOWN && it.isAlive } }
        }
    }

    /** 통계 파일(`stats` 객체) — 아무 스레드. 블록/아이템 구분은 [isBlock](재료 이름 → 블록인가)으로. */
    class FromJson(private val stats: JsonObject, private val isBlock: (String) -> Boolean) : Source {
        private fun group(name: String): Map<String, Long> =
            stats.getAsJsonObject("minecraft:$name")?.entrySet()?.associate { it.key to runCatching { it.value.asLong }.getOrDefault(0) }.orEmpty()

        override fun custom(c: Custom): Long = group("custom")["minecraft:${c.json}"] ?: 0

        override fun category(cat: Category): Long {
            val g = group(cat.json)
            return when (cat) {
                Category.USED_BLOCKS -> g.filterKeys { isBlock(it) }.values.sum()
                Category.USED_ITEMS -> g.filterKeys { !isBlock(it) }.values.sum()
                else -> g.values.sum()
            }
        }
    }

    /** 한 화면의 줄들 — (이름, 보이는 값). 0 인 이동 거리·상호작용은 뺀다(긴 목록이 0 으로 차지 않게). */
    fun lines(source: Source, formatTicks: (Long) -> String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (c in CUSTOM) {
            val v = source.custom(c)
            if (v == 0L && c.unit == Kind.CM) continue
            if (v == 0L && c.unit == Kind.COUNT && c !in ALWAYS) continue
            out += c.label to format(v, c.unit, formatTicks)
        }
        val travel = TRAVEL.sumOf { source.custom(it) }
        out.add(minOf(out.size, 4), "이동 거리 합계" to distance(travel))
        for (cat in Category.entries) out += cat.label to "%,d".format(source.category(cat))
        return out
    }

    private val ALWAYS: Set<Custom> = CUSTOM.filter { it.json in setOf("deaths", "jump", "mob_kills", "player_kills", "leave_game") }.toSet()

    fun format(v: Long, unit: Kind, formatTicks: (Long) -> String): String = when (unit) {
        Kind.COUNT -> "%,d".format(v)
        Kind.TICKS -> formatTicks(v)
        Kind.CM -> distance(v)
        Kind.DAMAGE -> "%,.1f".format(v / 10.0) // 바닐라는 피해 ×10 으로 센다(하트 반 칸 = 1)
    }

    /** cm → m·km. */
    fun distance(cm: Long): String = when {
        cm >= 100_000 -> "%,.2fkm".format(cm / 100_000.0)
        cm >= 100 -> "%,dm".format(cm / 100)
        else -> "${cm}cm"
    }
}
