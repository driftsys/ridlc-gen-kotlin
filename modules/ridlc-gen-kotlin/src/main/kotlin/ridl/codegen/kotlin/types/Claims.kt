package ridl.codegen.kotlin.types

import ridl.codegen.kotlin.PLUGIN

/**
 * One package-level Kotlin name a generated file takes, and what it is
 * generated for, in words: `declaration `Level``, `the descriptor of member
 * `temperature` of interface `Cabin``.
 */
class Claim(val name: String, val source: String)

/**
 * The refusal of every package-level name two generated types claim
 * (driftsys/ridlc-gen-kotlin#18, step 2.2 of driftsys/ridl's generated-name
 * collision design): both are spelled from ridl names, so no rename is the
 * plugin's to make, and the package is refused with one message naming every
 * source. The names of the package's three files share one Kotlin namespace,
 * so the claims of all three are checked together. An interface whose face
 * is skipped claims nothing, because it emits nothing.
 */
internal fun collisions(pkg: String, claims: List<Claim>): List<String> =
    claims.groupBy { it.name }.filterValues { it.size > 1 }.map { (name, sources) ->
        val all = sources.map { it.source }
        val times = if (all.size == 2) "twice" else "${all.size} times"
        "$PLUGIN: `$name` is generated $times in package `$pkg`: for ${all.dropLast(1).joinToString(", ")}, " +
            "and for ${all.last()}; rename one of them"
    }
