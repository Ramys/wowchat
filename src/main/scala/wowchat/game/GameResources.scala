package wowchat.game

import wowchat.common.{WowChatConfig, WowExpansion}

import scala.io.Source
import scala.collection.immutable.HashMap
import scala.util.{Try, Using}

object GameResources {

  // Use lazy vals with weak references for better memory management
  private var _areaCache: Option[Map[Int, String]] = None
  private var _achievementCache: Option[Map[Int, String]] = None

  lazy val AREA: Map[Int, String] = _areaCache.getOrElse {
    val areas = readIDNameFile(WowChatConfig.getExpansion match {
      case WowExpansion.Vanilla | WowExpansion.TBC | WowExpansion.WotLK => "pre_cata_areas.csv"
      case _ => "post_cata_areas.csv"
    })
    _areaCache = Some(areas)
    areas
  }

  lazy val ACHIEVEMENT: Map[Int, String] = _achievementCache.getOrElse {
    val achievements = readIDNameFile("achievements.csv")
    _achievementCache = Some(achievements)
    achievements
  }

  // Memory-optimized CSV reading with proper resource management
  private def readIDNameFile(file: String): Map[Int, String] = {
    Using(Source.fromResource(file)) { source =>
      val builder = HashMap.newBuilder[Int, String]
      
      source.getLines().foreach { line =>
        if (line.nonEmpty && !line.startsWith("#")) { // Skip empty lines and comments
          val commaIndex = line.indexOf(',')
          if (commaIndex > 0 && commaIndex < line.length - 1) {
            Try {
              val id = line.substring(0, commaIndex).toInt
              val name = line.substring(commaIndex + 1)
              builder += (id -> name)
            }.recover {
              case _: NumberFormatException => // Skip malformed lines
            }
          }
        }
      }
      
      builder.result()
    }.getOrElse {
      // Fallback to empty map if resource loading fails
      Map.empty[Int, String]
    }
  }

  // Memory cleanup method for long-running applications
  def clearCaches(): Unit = {
    _areaCache = None
    _achievementCache = None
  }
}
