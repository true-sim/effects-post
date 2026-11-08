// The root project publishes nothing; a group of its own keeps it from clashing with the module of the same name
// (com.truesim.effects:effects-post) when this build is included in another's composite.
group = "com.truesim.effects.build"

// Test shards (CI): -Ptruesim.shard=i/n -Ptruesim.shardModules=effects-post runs shard i of n of those
// modules' tests, the shards balanced by ci/test-timings.txt (seconds per test class, per test method for the long
// classes, as CI measured them): the longest first, each to a shard already running its package while that stays
// under an even share (they share shaders lavapipe compiles once per shard), else to the shard with the least so far. A class the file does not
// know goes to shard hash(name) % n, as do a split class's tests the file does not know. Without the property every
// test runs, as always.
val shard = providers.gradleProperty("truesim.shard").orNull
val shardModules = providers.gradleProperty("truesim.shardModules").orNull?.split(",")?.map(String::trim).orEmpty()

/** What one shard runs of a module: the classes and methods it includes, and the methods it leaves to others. */
class ShardPart {
    val includes = mutableListOf<String>()
    val excludes = mutableListOf<String>()
}

fun shardParts(index: Int, count: Int): Map<String, ShardPart> {
    val timings = rootProject.file("ci/test-timings.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split("\t") }
        .filter { it.size == 3 && it[1] in shardModules }
        .map { Triple(it[0].toDouble(), it[1], it[2]) }
    val load = DoubleArray(count) // seconds per shard, as timed
    val shardOf = mutableMapOf<String, Int>() // unit (class or class#method) -> shard
    // Tests of one package share shaders, which lavapipe compiles once per shard (its disk cache): a unit goes where
    // its package already is while that shard stays under its even share, else to the least loaded shard
    val share = timings.sumOf { it.first } / count * 1.05
    val packagesIn = Array(count) { mutableSetOf<String>() }
    for ((seconds, _, unit) in timings.sortedWith(compareBy({ -it.first }, { it.third }))) {
        val pkg = unit.substringBefore('#').substringBeforeLast('.')
        val together = load.indices.filter { pkg in packagesIn[it] && load[it] + seconds <= share }.minByOrNull { load[it] }
        val shard = together ?: load.indices.minBy { load[it] }
        load[shard] += seconds
        packagesIn[shard] += pkg
        shardOf[unit] = shard
    }
    fun hashed(name: String) = Math.floorMod(name.hashCode(), count)
    val parts = mutableMapOf<String, ShardPart>()
    for (module in shardModules) {
        val part = ShardPart()
        parts[module] = part
        val sources = project(":$module").file("src/test/java")
        val classes = sources.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }
            .map { it.relativeTo(sources).path.removeSuffix(".java").replace(File.separatorChar, '.') }.sorted().toList()
        val known = timings.filter { it.second == module }.map { it.third }
        for (name in classes) {
            val methods = known.filter { it.startsWith("$name#") }
            if (methods.isEmpty()) { // a class as a whole
                if ((shardOf[name] ?: hashed(name)) == index) {
                    part.includes += name
                }
                continue
            }
            // A split class: its known tests where the timings put them, the rest in the shard of its name
            val rest = hashed(name) == index
            if (rest) {
                part.includes += name
            }
            for (method in methods) {
                val pattern = method.replace('#', '.')
                when {
                    shardOf[method] == index && !rest -> part.includes += pattern
                    shardOf[method] != index && rest -> part.excludes += pattern
                }
            }
        }
    }
    return parts
}

val parts = shard?.split("/")?.let { (i, n) -> shardParts(i.toInt() - 1, n.toInt()) }.orEmpty()

// ./gradlew testShards -Ptruesim.shard=1/8 -Ptruesim.shardModules=...: what that shard runs, and its timed seconds
val shardReport = parts.map { (module, part) ->
    "$module: includes ${part.includes.size} (${part.includes.joinToString()}), excludes ${part.excludes.size}" +
        " (${part.excludes.joinToString()})"
}
tasks.register("testShards") {
    description = "Prints what the test shard given by truesim.shard runs."
    val report = shardReport
    doLast { report.forEach { println(it) } }
}

subprojects {
    // LWJGL and mapped GPU memory use native access.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    val part = parts[name]
    if (part != null) {
        tasks.withType<Test>().matching { it.name == "test" }.configureEach {
            filter {
                isFailOnNoMatchingTests = false
                if (part.includes.isEmpty()) {
                    includeTestsMatching("none.NoTestsInThisShard")
                }
                part.includes.forEach { includeTestsMatching(it) }
                part.excludes.forEach { excludeTestsMatching(it) }
            }
        }
    }
}

// The shards' coverage together (CI's last job): -Ptruesim.mergedCoverage=true checks and reports a module's coverage
// from every .exec file in its build/jacoco (the shards' test.exec, downloaded) instead of its own test run's.
if (providers.gradleProperty("truesim.mergedCoverage").orNull == "true") {
    subprojects {
        tasks.withType<JacocoReportBase>().configureEach {
            executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
        }
    }
}
