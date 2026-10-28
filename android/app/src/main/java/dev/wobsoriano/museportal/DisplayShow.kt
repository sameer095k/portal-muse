package dev.wobsoriano.museportal

import dev.wobsoriano.muse.Command
import dev.wobsoriano.muse.CommandFailed
import dev.wobsoriano.muse.Param
import dev.wobsoriano.muse.ParamType
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The one command this app registers: display.show. Muse sends the card
 * down the gadget link; the handler hands it to the dashboard server
 * (DashServer) over loopback — DashServer accepts /api/show from
 * on-device apps without the upload token — and the dashboard's Show
 * page renders it. Kept as a singleton so the [Command] (and its
 * handler) is the same instance in every config the controller sends,
 * which is what lets the core's config dedup keep working.
 */
object DisplayShow {

  val command: Command =
      Command(
          name = "display.show",
          description =
              "Show visual content on this device's screen: a recipe (ingredients and " +
                  "steps), a how-to, a list, instructions, or a web/video link. Use this " +
                  "whenever the answer is better read than heard — the content appears on " +
                  "the device's dashboard for the user to read. Keep your spoken reply to " +
                  "a brief confirmation (e.g. \"Pancakes are on screen\") and do not read " +
                  "the content aloud.",
          required =
              listOf(
                  Param(
                      "title",
                      ParamType.String,
                      "The card's title, e.g. the name of the recipe or page.")),
          optional =
              listOf(
                  Param(
                      "subtitle",
                      ParamType.String,
                      "One short line under the title, e.g. a time or a source."),
                  Param(
                      "url",
                      ParamType.String,
                      "A web or video link; the card's Open button launches it."),
                  Param("image", ParamType.String, "A banner image URL for the card."),
                  Param(
                      "sections",
                      ParamType.String,
                      "The content itself, as a JSON array written out as a string: " +
                          "[{\"heading\":\"Ingredients\",\"lines\":[\"1 cup flour\",\"2 eggs\"]}," +
                          "{\"heading\":\"Steps\",\"lines\":[\"Mix.\",\"Bake.\"]}]. " +
                          "At most 6 sections of at most 40 lines each. Put the full " +
                          "recipe, how-to or list here — the user reads it on screen."),
              ),
          timeout = 15.seconds,
          handler = { args, _ -> show(args) },
      )

  private suspend fun show(args: String): JsonElement {
    val root =
        try {
          Json.parseToJsonElement(args).jsonObject
        } catch (e: Exception) {
          throw CommandFailed("the show request was not valid JSON")
        }
    fun str(name: String) = (root[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    val title = str("title").take(200)
    if (title.isEmpty()) throw CommandFailed("a title is needed to show a card")
    val url = str("url").takeIf { isHttp(it) && it.length <= 1000 }.orEmpty()
    val image = str("image").takeIf { isHttp(it) && it.length <= 1000 }.orEmpty()
    val sections = sanitizeSections(root["sections"])
    if (url.isEmpty() && sections == null) {
      throw CommandFailed("nothing to show: the card needs a url or sections")
    }
    val card = buildJsonObject {
      put("title", title)
      if (url.isNotEmpty()) put("url", url)
      str("subtitle").takeIf { it.isNotEmpty() }?.let { put("subtitle", it) }
      if (image.isNotEmpty()) put("image", image)
      if (sections != null) put("sections", sections)
    }
    return post(card.toString(), title)
  }

  private fun isHttp(s: String) = s.startsWith("http://") || s.startsWith("https://")

  /**
   * The sections parameter arrives as a JSON array inside a string (the
   * command spec has no array type). Lenient copy of DashServer's caps —
   * at most 6 sections, 40 lines per section — so its strict validation
   * never has a reason to refuse the card.
   */
  private fun sanitizeSections(el: JsonElement?): JsonArray? {
    val text = (el as? JsonPrimitive)?.contentOrNull ?: return null
    val arr =
        try {
          Json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
          null
        } ?: return null
    val out = buildJsonArray {
      for (s in arr.take(6)) {
        val obj = s as? JsonObject ?: continue
        val linesEl = obj["lines"] as? JsonArray ?: continue
        val lines =
            linesEl
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(300) }
                .filter { it.isNotEmpty() }
                .take(40)
        if (lines.isEmpty()) continue
        val heading = (obj["heading"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(120).orEmpty()
        add(
            buildJsonObject {
              put("heading", heading)
              put("lines", buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
            })
      }
    }
    return out.takeIf { it.isNotEmpty() }
  }

  /** POSTs the card to DashServer on loopback and reports what Muse should hear back. */
  private suspend fun post(body: String, title: String): JsonElement =
      withContext(Dispatchers.IO) {
        val conn =
            URL("http://127.0.0.1:8321/api/show").openConnection() as HttpURLConnection
        try {
          conn.requestMethod = "POST"
          conn.connectTimeout = 5_000
          conn.readTimeout = 5_000
          conn.doOutput = true
          conn.setRequestProperty("Content-Type", "application/json")
          val bytes = body.toByteArray(Charsets.UTF_8)
          conn.setFixedLengthStreamingMode(bytes.size)
          conn.outputStream.use { it.write(bytes) }
          val code = conn.responseCode
          if (code != 200) {
            throw CommandFailed("the dashboard did not take the card (HTTP $code)")
          }
          buildJsonObject {
            put("ok", true)
            put("shown", title)
          }
        } catch (e: CommandFailed) {
          throw e
        } catch (e: Exception) {
          throw CommandFailed("the dashboard is not reachable: ${e.message}")
        } finally {
          conn.disconnect()
        }
      }
}

// The app's command set, registered with every config the controller sends.
val portalCommands: List<Command> = listOf(DisplayShow.command)
