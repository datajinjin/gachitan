package kr.gachitan.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.gachitan.core.EnergyKind
import kr.gachitan.vehicle.OccupancyMapping
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository(private val store: KeyValueStore) : SettingsSource {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    override val current: AppSettings get() = _settings.value

    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        store.write(FILE, toJson(next).toString())
    }

    override fun rememberNames(names: Collection<String>) = update { s ->
        val merged = (names.filter { it.isNotBlank() } + s.recentNames).distinct().take(MAX_RECENT)
        s.copy(recentNames = merged)
    }

    private fun load(): AppSettings = store.read(FILE)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        ?: AppSettings()

    private fun toJson(s: AppSettings) = JSONObject().apply {
        put("driverName", s.driverName)
        put("energyKind", s.energyKind.name)
        put("unitPriceWon", s.unitPriceWon)
        s.capacityUnits?.let { put("capacityUnits", it) }
        put("fallbackKmPerUnit", s.fallbackKmPerUnit)
        put("driverPaysShare", s.driverPaysShare)
        put("roundingUnitWon", s.roundingUnitWon)
        put("tossId", s.tossId)
        put("account", s.account)
        put("viewerBaseUrl", s.viewerBaseUrl)
        put("occupancyMapping", s.occupancyMapping.name)
        put("demoMode", s.demoMode)
        put("recentNames", JSONArray(s.recentNames))
        put("themeMode", s.themeMode.name)
        put("wearWonPerKm", s.wearWonPerKm)
    }

    private fun fromJson(o: JSONObject): AppSettings {
        val d = AppSettings()
        val names = o.optJSONArray("recentNames")
        return AppSettings(
            driverName = o.optString("driverName", d.driverName),
            energyKind = runCatching { EnergyKind.valueOf(o.getString("energyKind")) }.getOrDefault(d.energyKind),
            unitPriceWon = o.optDouble("unitPriceWon", d.unitPriceWon),
            capacityUnits = if (o.has("capacityUnits")) o.getDouble("capacityUnits") else null,
            fallbackKmPerUnit = o.optDouble("fallbackKmPerUnit", d.fallbackKmPerUnit).takeIf { it > 0 } ?: d.fallbackKmPerUnit,
            driverPaysShare = o.optBoolean("driverPaysShare", d.driverPaysShare),
            roundingUnitWon = o.optLong("roundingUnitWon", d.roundingUnitWon).coerceAtLeast(1),
            tossId = o.optString("tossId", ""),
            account = o.optString("account", ""),
            viewerBaseUrl = o.optString("viewerBaseUrl", ""),
            occupancyMapping = runCatching { OccupancyMapping.valueOf(o.getString("occupancyMapping")) }
                .getOrDefault(d.occupancyMapping),
            demoMode = o.optBoolean("demoMode", false),
            recentNames = if (names == null) emptyList() else (0 until names.length()).map { names.getString(it) },
            themeMode = runCatching { ThemeMode.valueOf(o.getString("themeMode")) }.getOrDefault(d.themeMode),
            wearWonPerKm = o.optDouble("wearWonPerKm", d.wearWonPerKm).takeIf { it.isFinite() && it >= 0 } ?: d.wearWonPerKm
        )
    }

    private companion object {
        const val FILE = "settings.enc"
        const val MAX_RECENT = 8
    }
}
