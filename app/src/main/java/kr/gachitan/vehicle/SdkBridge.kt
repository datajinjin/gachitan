package kr.gachitan.vehicle

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Vehicle SDK 도메인 객체의 메서드를 이름으로 찾아 호출합니다.
 *
 * 왜 리플렉션인가
 * - Pleos 공식 Vehicle SDK Sample App(VehicleApiExplorer)이 19개 도메인을 같은 방식으로 호출합니다.
 * - 도메인마다 콜백 형태(값 1개, 영역+값 등)가 조금씩 달라서, 이름·영역·콜백 규칙만 맞으면
 *   SDK 버전이 바뀌어도 이 코드는 다시 컴파일할 필요 없이 동작합니다.
 * - Door처럼 시그니처가 확정된 API는 [PleosVehicleSignals]에서 타입 안전하게 직접 호출합니다.
 */
internal class SdkBridge(
    private val onError: (method: String, error: Throwable) -> Unit
) {
    class Registration(
        val target: Any,
        val unregisterName: String,
        val area: Any?,
        val listener: Any
    )

    /** getXxx 같은 1회성 조회. 결과는 [onValue]로 비동기 전달됩니다. */
    fun get(target: Any, name: String, area: Any? = null, onValue: (Any?) -> Unit): Boolean =
        invoke(target, name, area, onValue, listener = null)

    /** registerXxx 구독. 해제에 필요한 정보를 돌려줍니다. */
    fun register(target: Any, name: String, area: Any? = null, onValue: (Any?) -> Unit): Registration? {
        val fn = candidates(target, name, area).firstOrNull() ?: run {
            onError(name, NoSuchMethodException(name)); return null
        }
        val listenerClass = fn.parameters.firstNotNullOfOrNull { p ->
            (p.type.classifier as? KClass<*>)?.takeIf { it.isListener() }
        } ?: run { onError(name, IllegalStateException("listener parameter not found")); return null }

        val listener = proxyListener(listenerClass, name, onValue)
        return if (invoke(target, name, area, onValue, listener)) {
            Registration(target, "un$name", area, listener)
        } else null
    }

    fun unregister(registration: Registration) {
        invoke(registration.target, registration.unregisterName, registration.area, {}, registration.listener)
    }

    // ------------------------------------------------------------------ 내부

    private fun invoke(
        target: Any,
        name: String,
        area: Any?,
        onValue: (Any?) -> Unit,
        listener: Any?
    ): Boolean {
        val functions = candidates(target, name, area)
        if (functions.isEmpty()) {
            onError(name, NoSuchMethodException("${target.javaClass.simpleName}.$name"))
            return false
        }
        for (fn in functions) {
            val args = arguments(fn, target, area, name, onValue, listener) ?: continue
            return try {
                fn.callBy(args)
                true
            } catch (e: Exception) {
                onError(name, (e as? InvocationTargetException)?.targetException ?: e)
                false
            }
        }
        onError(name, IllegalArgumentException("no compatible overload for $name"))
        return false
    }

    /** 영역 인자 유무가 일치하는 오버로드를 먼저, 그리고 인자가 적은 순으로 시도합니다. */
    private fun candidates(target: Any, name: String, area: Any?): List<KFunction<*>> =
        apiClasses(target)
            .flatMap { cls -> runCatching { cls.memberFunctions.toList() }.getOrDefault(emptyList()) }
            .filter { it.name == name }
            .distinctBy { fn -> fn.parameters.drop(1).joinToString { it.type.toString() } }
            .onEach { fn -> runCatching { fn.isAccessible = true } }
            .sortedWith(compareBy<KFunction<*>>(
                { fn -> if (hasAreaParameter(fn) == (area != null)) 0 else 1 },
                { fn -> fn.parameters.size }
            ))

    /**
     * 공개 API 타입(ai.pleos.playground.vehicle.api.*)을 먼저 찾습니다. 구현 클래스가 비공개여도
     * 공개 타입의 메서드로 호출할 수 있습니다(공식 샘플도 api 클래스 기준으로 호출).
     */
    private fun apiClasses(target: Any): List<KClass<*>> {
        val publicApi = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { sequenceOf(it) + it.interfaces.asSequence() }
            .filter { it.name.startsWith("ai.pleos.playground.vehicle.api.") }
            .map { it.kotlin }
            .toList()
        return (publicApi + target::class).distinct()
    }

    private fun hasAreaParameter(fn: KFunction<*>): Boolean =
        fn.parameters.any { (it.type.classifier as? KClass<*>)?.simpleName?.endsWith("Area") == true }

    private fun arguments(
        fn: KFunction<*>,
        target: Any,
        area: Any?,
        name: String,
        onValue: (Any?) -> Unit,
        listener: Any?
    ): Map<KParameter, Any?>? {
        val args = mutableMapOf<KParameter, Any?>()
        for (p in fn.parameters) {
            val cls = p.type.classifier as? KClass<*>
            val paramName = p.name.orEmpty()
            when {
                p.kind == KParameter.Kind.INSTANCE -> args[p] = target
                cls == null -> return null
                cls.isListener() -> args[p] = listener ?: return null
                area != null && cls.isInstance(area) -> args[p] = area
                cls == Function1::class && paramName.contains("fail", ignoreCase = true) ->
                    args[p] = { e: Any? -> onError(name, e as? Throwable ?: RuntimeException(e.toString())) }
                cls == Function1::class -> args[p] = { v: Any? -> onValue(v) }
                // onComplete(area, value) 형태: 마지막 값을 씁니다.
                cls == Function2::class -> args[p] = { a: Any?, b: Any? -> onValue(b ?: a) }
                cls == Function0::class -> args[p] = { onValue(Unit) }
                p.isOptional -> Unit
                else -> return null
            }
        }
        return args
    }

    private fun proxyListener(listenerClass: KClass<*>, name: String, onValue: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(listenerClass.java.classLoader, arrayOf(listenerClass.java)) { proxy, method, a ->
            when (method.name) {
                "equals" -> proxy === a?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "GachitanListener($name)"
                else -> {
                    if (method.name.contains("fail", ignoreCase = true)) {
                        val e = a?.firstOrNull()
                        onError(name, e as? Throwable ?: RuntimeException(e.toString()))
                    } else {
                        onValue(a?.lastOrNull())
                    }
                    Unit
                }
            }
        }

    private fun KClass<*>.isListener(): Boolean =
        qualifiedName?.startsWith("ai.pleos.playground.vehicle.listener.") == true
}
