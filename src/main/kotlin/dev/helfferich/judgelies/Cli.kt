package dev.helfferich.judgelies

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

object Cli {
    fun run(args: Array<String>, env: Map<String, String> = System.getenv(), clock: () -> Instant = Instant::now): Int {
        if (args.isEmpty() || args[0] == "help" || args[0] == "--help") {
            println(USAGE)
            return 0
        }
        return try {
            val (command, flags) = parse(args.toList())
            when (command) {
                "prepare" -> prepare(flags)
                "estimate" -> estimate(flags, env)
                "run" -> execute(flags, env, clock)
                "analyze" -> analyze(flags, clock)
                else -> {
                    System.err.println("Unknown command '$command'.\n$USAGE")
                    1
                }
            }
        } catch (ex: BudgetRefused) {
            System.err.println(ex.message)
            System.err.println(CostModel.format(ex.estimate))
            2
        } catch (ex: IllegalArgumentException) {
            System.err.println(ex.message)
            1
        } catch (ex: IllegalStateException) {
            System.err.println(ex.message)
            1
        }
    }

    private fun prepare(flags: Map<String, String>): Int {
        val squadPath = path(flags["squad"] ?: error("prepare requires --squad <dev-v1.1.json>"))
        val out = path(flags["out"] ?: "data/items.json")
        val dump = Jsons.pretty.decodeFromString(SquadDump.serializer(), Files.readString(squadPath))
        val dataset = VariantRules.buildDataset(dump)
        Files.createDirectories(out.parent)
        Files.writeString(out, Jsons.pretty.encodeToString(DatasetFile.serializer(), dataset) + "\n")
        val kinds = dataset.items.groupingBy { it.subtleKind }.eachCount()
        println("Wrote ${dataset.items.size} items to $out")
        kinds.toSortedMap().forEach { (kind, n) -> println("  $kind: $n") }
        return 0
    }

    private fun estimate(flags: Map<String, String>, env: Map<String, String>): Int {
        val (config, _) = loadReadyConfig(flags, refreshDefault = false)
        val dataset = loadDataset(path(config.dataset))
        val estimate = CostModel.estimate(config, Planner.plan(config, dataset.items).map { it.call })
        println(CostModel.format(estimate))
        println()
        println("Pinned prices as of ${config.pricingAsOf} from ${config.pricingSource}.")
        println("Re-check with: ./gradlew -q run --args=\"estimate --refresh\"")
        println("No API calls were made. OPENROUTER_API_KEY is ${if (env["OPENROUTER_API_KEY"].isNullOrBlank()) "not set" else "set"}.")
        return if (estimate.allowed) 0 else 2
    }

    private fun execute(flags: Map<String, String>, env: Map<String, String>, clock: () -> Instant): Int {
        val dryRun = flags["dry-run"] == "true"
        val (config, refreshed) = loadReadyConfig(flags, refreshDefault = !dryRun && flags["allow-stale-prices"] != "true")
        val dataset = loadDataset(path(config.dataset))
        val out = path(
            flags["out"] ?: if (dryRun) "results/dry-run" else "results/runs/${stamp(clock())}",
        )
        val judge = if (dryRun) {
            SimulatedJudge
        } else {
            val key = env["OPENROUTER_API_KEY"]?.trim().orEmpty()
            if (key.isEmpty()) {
                throw IllegalStateException("Set OPENROUTER_API_KEY, or pass --dry-run. No request was sent.")
            }
            OpenRouterJudge(key)
        }
        if (!dryRun && !refreshed && flags["allow-stale-prices"] != "true") {
            throw IllegalStateException("Refusing a paid run without a fresh OpenRouter price check. Pass --refresh or --allow-stale-prices.")
        }
        println(if (dryRun) "Dry run. No network calls." else "Paid run against OpenRouter. Prices were refreshed.")
        val result = ExperimentRunner(clock).run(
            config = config,
            dataset = dataset,
            outputDir = out,
            judge = judge,
            mode = if (dryRun) "dry-run" else "openrouter",
            fresh = flags["fresh"] == "true",
            gitCommit = gitCommit(),
        )
        println(CostModel.format(result.manifest.estimate))
        println()
        println("Completed ${result.manifest.completedTrials} / ${result.manifest.plannedTrials} -> $out")
        if (result.manifest.cacheHits > 0) println("Cache hits: ${result.manifest.cacheHits}")
        if (result.manifest.stoppedEarly) {
            System.err.println("Stopped early: ${result.manifest.stopReason}")
            return 3
        }
        println("Next: ./gradlew -q run --args=\"analyze --run $out\"")
        return 0
    }

    private fun analyze(flags: Map<String, String>, clock: () -> Instant): Int {
        val runDir = flags["run"]?.let { path(it) } ?: newestRun(path("results"))
        val docs = if (flags["no-docs"] == "true") null else path(flags["docs"] ?: "docs/data")
        val manifest = Jsons.pretty.decodeFromString(RunManifest.serializer(), Files.readString(runDir.resolve("manifest.json")))
        val dataset = loadDataset(path(manifest.datasetPath))
        val cache = ResponseCache(runDir.resolve("responses.jsonl"))
        cache.load()
        val disputedPath = path(flags["disputed"] ?: "data/disputed.json")
        val disputed = if (disputedPath.exists()) {
            Jsons.pretty.decodeFromString(DisputedFile.serializer(), Files.readString(disputedPath)).items
        } else {
            emptyList()
        }
        val analysis = Analyzer.analyze(dataset.items, manifest, cache.rows(), clock().toString(), disputed)
        Report.write(runDir, docs, analysis)
        val h = analysis.metrics.headlines
        println("Analyzed $runDir")
        println("Parsed ${analysis.metrics.meta.nParsed} / ${analysis.metrics.meta.nTrialsCompleted} responses")
        println("Mean self-disagreement (faithfulness): ${pct(h.meanFaithfulnessSelfDisagreement)}")
        println("Mean pairwise kappa (faithfulness): ${num(h.meanPairwiseFaithfulnessKappa)}")
        println("Mean subtle-error miss rate: ${pct(h.meanSubtleMissRate)} (correctness >= ${manifest.missThreshold}), ${pct(h.meanSubtleMissRateAt5)} (== 5)")
        if (disputed.isNotEmpty()) {
            println("  without ${disputed.size} disputed item(s): ${pct(h.meanSubtleMissRateExcludingDisputed)} (>= ${manifest.missThreshold}), ${pct(h.meanSubtleMissRateAt5ExcludingDisputed)} (== 5)")
        }
        println("Missed planted cases (all prompts and repeats): ${analysis.misses.rows.size}")
        println("Mean unsupported miss rate: ${pct(h.meanUnsupportedMissRate)}")
        if (docs != null) println("Wrote charts data to $docs")
        return 0
    }

    private fun loadReadyConfig(flags: Map<String, String>, refreshDefault: Boolean): Pair<ExperimentConfig, Boolean> {
        val configPath = path(flags["config"] ?: "config/experiment.json")
        var config = Jsons.pretty.decodeFromString(ExperimentConfig.serializer(), Files.readString(configPath))
        config.validate()
        val refresh = flags["refresh"] == "true" || (refreshDefault && flags["refresh"] != "false")
        if (!refresh) return config to false
        println("Fetching ${config.pricingSource}")
        val body = fetch(config.pricingSource)
        val judges = reconcilePrices(config.judges, body)
        config = config.copy(judges = judges, pricingAsOf = Instant.now().toString().take(10))
        config.validate()
        val moved = judges.zip(Jsons.pretty.decodeFromString(ExperimentConfig.serializer(), Files.readString(configPath)).judges)
            .filter { (live, pinned) ->
                live.promptUsdPerMillion != pinned.promptUsdPerMillion ||
                    live.completionUsdPerMillion != pinned.completionUsdPerMillion
            }
        if (moved.isEmpty()) println("Live prices are at or below the pinned prices. Using the pinned prices.")
        else moved.forEach { (live, _) ->
            println("Using the higher live price for ${live.id}: ${live.promptUsdPerMillion} / ${live.completionUsdPerMillion} USD per 1M")
        }
        return config to true
    }

    private fun fetch(url: String): String {
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("GET $url failed with HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private fun loadDataset(file: Path): DatasetFile {
        if (!file.exists()) throw IllegalStateException("Dataset not found at $file. Run prepare, or use the checked-in data/items.json.")
        return Jsons.pretty.decodeFromString(DatasetFile.serializer(), Files.readString(file))
    }

    private fun newestRun(root: Path): Path {
        if (!root.exists()) throw IllegalStateException("No results directory at $root")
        val found = mutableListOf<Path>()
        Files.walk(root, 3).use { stream ->
            stream.filter { it.fileName?.toString() == "responses.jsonl" }.forEach { path ->
                path.parent?.let { found.add(it) }
            }
        }
        return found.maxByOrNull { Files.getLastModifiedTime(it.resolve("responses.jsonl")) }
            ?: throw IllegalStateException("No responses.jsonl under $root")
    }

    private fun gitCommit(): String? = try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor() == 0) process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim().ifBlank { null } else null
    } catch (_: Exception) {
        null
    }

    private fun path(value: String): Path = Path.of(value)
    private fun stamp(now: Instant): String = now.toString().replace(":", "").replace("-", "").take(15)
    private fun pct(value: Double?): String = if (value == null) "n/a" else "%.1f%%".format(value * 100)
    private fun num(value: Double?): String = if (value == null) "n/a" else "%.3f".format(value)

    private fun parse(args: List<String>): Pair<String, Map<String, String>> {
        val command = args.firstOrNull() ?: error("missing command")
        val flags = mutableMapOf<String, String>()
        var index = 1
        while (index < args.size) {
            val token = args[index]
            if (!token.startsWith("--")) throw IllegalArgumentException("Unexpected argument '$token'")
            val key = token.removePrefix("--")
            val next = args.getOrNull(index + 1)
            if (next == null || next.startsWith("--")) {
                flags[key] = "true"
                index += 1
            } else {
                flags[key] = next
                index += 2
            }
        }
        return command to flags
    }

    private val USAGE = """
        Usage:
          ./gradlew -q run --args="prepare --squad dev-v1.1.json --out data/items.json"
          ./gradlew -q run --args="estimate"
          ./gradlew -q run --args="estimate --refresh"
          ./gradlew -q run --args="run --dry-run --fresh"
          ./gradlew -q run --args="run --refresh"
          ./gradlew -q run --args="analyze --run results/dry-run"

        estimate and run refuse when expected cost × safety margin exceeds capEur.
        A paid run stops between calls when the next max_tokens ceiling would exceed the cap.
        Cached responses are not billed again.
    """.trimIndent()
}
