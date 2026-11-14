package kr.gachitan.data

/** 기록 저장소. 앱에서는 [SecureStore](암호화), 테스트에서는 [InMemoryStore]를 씁니다. */
interface KeyValueStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    fun wipeAll()
}

class InMemoryStore : KeyValueStore {
    private val files = linkedMapOf<String, String>()
    @Synchronized override fun read(name: String): String? = files[name]
    @Synchronized override fun write(name: String, text: String) { files[name] = text }
    @Synchronized override fun delete(name: String) { files.remove(name) }
    @Synchronized override fun wipeAll() { files.clear() }
}
