package kami.geology.util

object Hash {
    private const val GAMMA = -7046029254386353131L

    fun mix(value: Long): Long {
        var z = value + GAMMA
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    fun of(seed: Long, a: Long, b: Long = 0, c: Long = 0): Long = mix(mix(mix(seed xor a) xor b) xor c)

    fun at(seed: Long, x: Int, y: Int, z: Int): Long = of(seed, x.toLong(), y.toLong(), z.toLong())

    fun unit(hash: Long): Double = (hash ushr 11) * (1.0 / (1L shl 53))
}

class Rng(private var state: Long) {
    fun long(): Long {
        state += -7046029254386353131L
        return Hash.mix(state)
    }

    fun double(): Double = Hash.unit(long())

    fun int(bound: Int): Int = (double() * bound).toInt()

    fun chance(p: Double): Boolean = double() < p

    fun between(min: Double, max: Double): Double = min + double() * (max - min)

    fun pick(range: List<Int>): Int = if (range[1] <= range[0]) range[0] else range[0] + int(range[1] - range[0] + 1)

    fun spread(range: List<Double>): Double = between(range[0], range[1])
}
