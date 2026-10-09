package kami.libs.xaero.impl

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

object Refl {
    fun cls(name: String): Class<*> = Class.forName(name)

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        target ?: return null
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size && fits(it, args) }
            ?: throw NoSuchMethodException("${target.javaClass.name}.$name/${args.size}")
        return open(m, target.javaClass).invoke(target, *args)
    }

    private fun open(m: Method, type: Class<*>): Method {
        if (Modifier.isPublic(m.declaringClass.modifiers)) return m
        val queue = ArrayDeque<Class<*>>().also { it += type }
        val seen = HashSet<Class<*>>()
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (Modifier.isPublic(c.modifiers) && c != type) runCatching { c.getMethod(m.name, *m.parameterTypes) }.getOrNull()?.let { return it }
            c.superclass?.let(queue::add)
            queue.addAll(c.interfaces)
        }
        m.trySetAccessible()
        return m
    }

    private fun fits(m: Method, args: Array<out Any?>) = m.parameterTypes.indices.all { i ->
        val p = m.parameterTypes[i]
        val a = args[i]
        if (a == null) !p.isPrimitive else box(p).isInstance(a)
    }

    private fun box(c: Class<*>): Class<*> = when (c) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> c
    }

    fun staticField(owner: String, field: String): Any? = cls(owner).getField(field).get(null)

    fun staticCall(owner: String, name: String, vararg args: Any?): Any? = staticCallOn(cls(owner), name, *args)

    fun staticCallOn(c: Class<*>, name: String, vararg args: Any?): Any? {
        val m = c.methods.firstOrNull { Modifier.isStatic(it.modifiers) && it.name == name && it.parameterCount == args.size && fits(it, args) }
            ?: throw NoSuchMethodException("${c.name}.$name/${args.size}")
        return open(m, c).invoke(null, *args)
    }

    fun proxy(iface: Class<*>, handler: (String, Array<Any?>) -> Any?): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), InvocationHandler { self, method, args ->
            val a = args ?: emptyArray()
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === a[0]
                    else -> "KamiXaero:${iface.simpleName}"
                }
                else -> handler(method.name, a)
            }
        })
}
