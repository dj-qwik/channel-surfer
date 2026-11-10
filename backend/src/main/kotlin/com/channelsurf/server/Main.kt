package com.channelsurf.server

import com.channelsurf.core.security.ManifestSignature
import java.io.File
import kotlin.system.exitProcess

/**
 * Usage:
 *   generate-key <dir>                         writes manifest-signing.pk8 and prints the public key for the app
 *   serve --manifest <file> --key <pk8> [--port 8080]
 *   check --manifest <file>                    validate a manifest and print a 24 h schedule summary
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: usage()
    val opts = args.drop(1).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    when (cmd) {
        "generate-key" -> {
            val dir = File(args.getOrNull(1) ?: usage()).apply { mkdirs() }
            val kp = ManifestSignature.generateKeyPair()
            File(dir, "manifest-signing.pk8").writeBytes(kp.private.encoded)
            val pub = ManifestSignature.publicKeyToBase64(kp.public)
            File(dir, "manifest-signing.pub.txt").writeText(pub)
            println("Private key: ${File(dir, "manifest-signing.pk8").absolutePath} (keep secret)")
            println("Public key (paste into the app: Setup > Schedule server > Public key):")
            println(pub)
        }
        "serve" -> {
            val manifest = File(opts["--manifest"] ?: usage())
            val key = ManifestSignature.privateKeyFromPkcs8(File(opts["--key"] ?: usage()).readBytes())
            val server = ScheduleServer(manifest, key)
            val pub = server.loadManifest()
            val port = server.start(opts["--port"]?.toIntOrNull() ?: 8080)
            println("Channel Surf schedule server on :$port, revision ${pub.lineup.revision}, ${pub.lineup.channels.size} channels")
        }
        "check" -> {
            val text = File(opts["--manifest"] ?: usage()).readText()
            when (val r = com.channelsurf.core.manifest.ManifestCodec.decode(text)) {
                is com.channelsurf.core.manifest.ManifestResult.Failure -> {
                    r.errors.forEach { System.err.println("ERROR: $it") }
                    exitProcess(1)
                }
                is com.channelsurf.core.manifest.ManifestResult.Success -> {
                    r.warnings.forEach { println("WARN: $it") }
                    val engine = com.channelsurf.core.schedule.ScheduleEngine(r.lineup)
                    val now = System.currentTimeMillis()
                    r.lineup.enabledChannels.forEach { c ->
                        val blocks = engine.blocksBetween(c.id, now, now + ScheduleServer.DAY)
                        val problems = com.channelsurf.core.schedule.ScheduleValidator.problems(blocks, now, now + ScheduleServer.DAY)
                        println("${c.displayNumber} ${c.displayName.padEnd(22)} ${blocks.size} blocks/24h  " +
                            (if (engine.hasProgramming(c.id)) "on air" else "no approved programming") +
                            (if (problems.isEmpty()) "" else "  PROBLEMS: $problems"))
                    }
                    println("OK: revision ${r.lineup.revision}")
                }
            }
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        """
        Channel Surf schedule server
          generate-key <dir>
          serve --manifest <file> --key <manifest-signing.pk8> [--port 8080]
          check --manifest <file>
        """.trimIndent()
    )
    exitProcess(2)
}
