package wowchat

import java.util.concurrent.{Executors, TimeUnit, CompletableFuture}

import wowchat.common.{CommonConnectionCallback, Global, ReconnectDelay, WowChatConfig}
import wowchat.discord.Discord
import wowchat.game.GameConnector
import wowchat.realm.{RealmConnectionCallback, RealmConnector}
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.nio.NioEventLoopGroup

import scala.io.Source
import scala.util.{Try, Using}

object WoWChat extends StrictLogging {

  private val RELEASE = "v1.3.3"

  def main(args: Array[String]): Unit = {
    logger.info(s"Starting WoWChat - $RELEASE")
    
    val confFile = if (args.nonEmpty) {
      args(0)
    } else {
      logger.info("No configuration file supplied. Using default wowchat.conf.")
      "wowchat.conf"
    }
    
    try {
      Global.config = WowChatConfig(confFile)
      logger.info("Configuration loaded successfully")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load configuration from $confFile", e)
        sys.exit(1)
    }

    // Async version check to avoid blocking startup
    checkForNewVersionAsync()

    val gameConnectionController: CommonConnectionCallback = new CommonConnectionCallback {

      private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
        val thread = new Thread(r, "wowchat-reconnect")
        thread.setDaemon(true)
        thread
      })
      private val reconnectDelay = new ReconnectDelay

      override def connect: Unit = {
        try {
          // Use optimized event loop group settings
          Global.group = new NioEventLoopGroup(
            0, // Use default thread count (2 * cores)
            (r: Runnable) => {
              val thread = new Thread(r, "wowchat-nio")
              thread.setDaemon(true)
              thread
            }
          )

          val realmConnector = new RealmConnector(new RealmConnectionCallback {
            override def success(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
              gameConnect(host, port, realmName, realmId, sessionKey)
            }

            override def disconnected: Unit = doReconnect

            override def error: Unit = {
              logger.error("Realm connection failed")
              sys.exit(1)
            }
          })

          realmConnector.connect
        } catch {
          case e: Exception =>
            logger.error("Failed to initialize network connections", e)
            sys.exit(1)
        }
      }

      private def gameConnect(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
        try {
          new GameConnector(host, port, realmName, realmId, sessionKey, this).connect
        } catch {
          case e: Exception =>
            logger.error("Failed to connect to game server", e)
            doReconnect
        }
      }

      override def connected: Unit = {
        reconnectDelay.reset
        logger.info("Successfully connected to game server")
      }

      override def disconnected: Unit = doReconnect

      def doReconnect: Unit = {
        Option(Global.group).foreach(_.shutdownGracefully())
        Global.discord.changeRealmStatus("Connecting...")
        val delay = reconnectDelay.getNext
        logger.info(s"Disconnected from server! Reconnecting in $delay seconds...")

        reconnectExecutor.schedule(new Runnable {
          override def run(): Unit = connect
        }, delay, TimeUnit.SECONDS)
      }
    }

    logger.info("Connecting to Discord...")
    try {
      Global.discord = new Discord(new CommonConnectionCallback {
        override def connected: Unit = {
          logger.info("Discord connection established, starting game connection...")
          gameConnectionController.connect
        }

        override def error: Unit = {
          logger.error("Discord connection failed")
          sys.exit(1)
        }
      })
    } catch {
      case e: Exception =>
        logger.error("Failed to initialize Discord connection", e)
        sys.exit(1)
    }

    // Add shutdown hook for graceful cleanup
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("Shutting down WoWChat...")
      try {
        Option(Global.group).foreach(_.shutdownGracefully(0, 5, TimeUnit.SECONDS))
        // Clear caches for memory cleanup
        wowchat.game.GameResources.clearCaches()
      } catch {
        case e: Exception =>
          logger.warn("Error during shutdown", e)
      }
    }))
  }

  // Non-blocking version check
  private def checkForNewVersionAsync(): Unit = {
    CompletableFuture.supplyAsync(() => {
      try {
        Using(Source.fromURL("https://api.github.com/repos/fjaros/wowchat/releases/latest")) { source =>
          val data = source.mkString
          val regex = "\"tag_name\":\"(.+?)\",".r
          regex.findFirstMatchIn(data)
            .map(_.group(1))
            .getOrElse("NOT FOUND")
        }.getOrElse("NOT FOUND")
      } catch {
        case _: Exception => "NOT FOUND" // Fail silently for version check
      }
    }).thenAccept(repoTagName => {
      if (repoTagName != RELEASE && repoTagName != "NOT FOUND") {
        logger.warn("~~~ !!!                YOUR WoWChat VERSION IS OUT OF DATE                !!! ~~~")
        logger.warn(s"~~~ !!!                     Current Version:  $RELEASE                      !!! ~~~")
        logger.warn(s"~~~ !!!                     Latest  Version:  $repoTagName                      !!! ~~~")
        logger.warn("~~~ !!! RUN git pull OR GO TO https://github.com/fjaros/wowchat TO UPDATE !!! ~~~")
      }
    }).exceptionally(e => {
      logger.debug("Version check failed (this is not critical)", e)
      null
    })
  }
}
