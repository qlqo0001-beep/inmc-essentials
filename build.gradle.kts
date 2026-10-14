plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.essentials"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-essentials"
}

dependencies {
    // 컴파일 의존은 PlaceholderAPI 하나뿐. WorldEdit·LuckPerms·Votifier·CoreProtect·TitleForge·Multiverse 는
    // 전부 리플렉션으로 닿는다 — 없는 서버에서도 모든 클래스가 읽혀야 한다(ReflectionPurityTest 가 지킨다).
    compileOnly(libs.placeholderapi) { isTransitive = false }
}
