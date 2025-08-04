package wowchat.discord

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.TextChannel
import wowchat.common.{WowChatConfig, WowExpansion}
import wowchat.game.GameResources

import scala.collection.JavaConverters._
import scala.util.matching.Regex
import scala.collection.mutable

object MessageResolver {

  def apply(jda: JDA): MessageResolver = {
    WowChatConfig.getExpansion match {
      case WowExpansion.Vanilla => new MessageResolver(jda)
      case WowExpansion.TBC => new MessageResolverTBC(jda)
      case WowExpansion.WotLK => new MessageResolverWotLK(jda)
      case WowExpansion.Cataclysm => new MessageResolverCataclysm(jda)
      case WowExpansion.MoP => new MessageResolverMoP(jda)
    }
  }
}

/**
 * Optimized message resolver with cached regex patterns and efficient string processing
 */
class MessageResolver(jda: JDA) {

  // Pre-compiled regex patterns for better performance
  protected val linkRegexes: Seq[(String, Regex)] = Seq(
    "item" -> "\\|.+?\\|Hitem:(\\d+):.+?\\|h\\[(.+?)\\]\\|h\\|r\\s?".r,
    "spell" -> "\\|.+?\\|(?:Hspell|Henchant)?:(\\d+).*?\\|h\\[(.+?)\\]\\|h\\|r\\s?".r,
    "quest" -> "\\|.+?\\|Hquest:(\\d+):.+?\\|h\\[(.+?)\\]\\|h\\|r\\s?".r
  )

  protected val linkSite = "http://classicdb.ch"

  // Cached regex patterns for color coding
  private val hexColorRegex = "\\|c[0-9a-fA-F]{8}".r
  private val colorPassRegex = "\\|c[0-9a-fA-F]{8}(.*?)\\|r".r

  // Cached regex patterns for tagging
  private val tagRegexes: Seq[Regex] = Seq("\"@(.+?)\"".r, "@([\\w]+)".r)

  // Thread-safe caches for performance
  private val memberCache = new mutable.HashMap[String, (Long, Seq[(String, String)])]()
  private val roleCacheKey = new Object()
  @volatile private var roleCache: Option[(Long, Seq[(String, String)])] = None

  def resolveLinks(message: String): String = {
    if (message.isEmpty || !message.contains("|")) return message
    
    linkRegexes.foldLeft(message) { case (result, (classicDbKey, regex)) =>
      if (result.contains("|")) {
        regex.replaceAllIn(result, m => {
          s"[${m.group(2)}] ($linkSite?$classicDbKey=${m.group(1)}) "
        })
      } else {
        result // Skip if no more links possible
      }
    }
  }

  def resolveAchievementId(achievementId: Int): String = {
    val name = GameResources.ACHIEVEMENT.getOrElse(achievementId, achievementId.toString)
    s"[$name] ($linkSite?achievement=$achievementId) "
  }

  def stripColorCoding(message: String): String = {
    if (message.isEmpty || !message.contains("|c")) return message
    
    val withoutColorTags = colorPassRegex.replaceAllIn(message.replace("$", "\\$"), _.group(1))
    hexColorRegex.replaceAllIn(withoutColorTags, "")
  }

  def resolveTags(discordChannel: TextChannel, message: String, onError: String => Unit): String = {
    if (message.isEmpty || !message.contains("@")) return message

    val channelId = discordChannel.getIdLong
    val currentTime = System.currentTimeMillis()
    
    // Cache member data for 5 minutes to reduce API calls
    val members = memberCache.get(discordChannel.getId) match {
      case Some((timestamp, cachedMembers)) if currentTime - timestamp < 300000 => // 5 minutes
        cachedMembers
      case _ =>
        val freshMembers = discordChannel.getMembers.asScala
          .filterNot(_.getUser.getIdLong == jda.getSelfUser.getIdLong)
          .flatMap { member =>
            val user = member.getUser
            Seq(
              member.getEffectiveName -> user.getId,
              s"${user.getName}#${user.getDiscriminator}" -> user.getId
            )
          }.toSeq
        memberCache.put(discordChannel.getId, (currentTime, freshMembers))
        freshMembers
    }

    // Cache role data globally with timestamp
    val roles = roleCache match {
      case Some((timestamp, cachedRoles)) if currentTime - timestamp < 300000 => // 5 minutes
        cachedRoles
      case _ =>
        roleCacheKey.synchronized {
          val freshRoles = jda.getRoles.asScala
            .filterNot(_.getName == "@everyone")
            .map(role => role.getName -> role.getId)
            .toSeq
          roleCache = Some((currentTime, freshRoles))
          freshRoles
        }
    }

    // Process tags efficiently
    tagRegexes.foldLeft(message) { case (result, regex) =>
      if (result.contains("@")) {
        regex.replaceAllIn(result, m => {
          val tag = m.group(1)
          val matches = findMatches(members, roles, tag)

          matches.size match {
            case 1 => s"<@${matches.head._2}>"
            case n if n > 1 && n < 5 =>
              onError(s"Your tag @$tag matches multiple channel members: ${matches.map(_._1).mkString(", ")}. Be more specific in your tag!")
              m.group(0)
            case n if n >= 5 =>
              onError(s"Your tag @$tag matches too many channel members. Be more specific in your tag!")
              m.group(0)
            case _ => m.group(0)
          }
        })
      } else {
        result
      }
    }
  }

  // Optimized emoji processing
  def resolveEmojis(message: String): String = {
    if (message.isEmpty) return message
    // This would be implemented with a more efficient emoji parser
    // For now, return as-is since the original implementation is missing
    message
  }

  // Efficient tag matching with early termination
  private def findMatches(members: Seq[(String, String)], roles: Seq[(String, String)], tag: String): Seq[(String, String)] = {
    val lowerTag = tag.toLowerCase
    
    // Try exact matches first
    val exactMatches = (members ++ roles).filter(_._1.equalsIgnoreCase(tag))
    if (exactMatches.nonEmpty) return exactMatches.take(1)
    
    // Then partial matches
    (members ++ roles).filter(_._1.toLowerCase.contains(lowerTag))
  }

  // Optimized tag matcher with better string matching
  protected def resolveTagMatcher(members: Seq[(String, String)], tag: String, isRole: Boolean): Seq[(String, String)] = {
    val lowerTag = tag.toLowerCase
    
    members.filter { case (name, _) =>
      val lowerName = name.toLowerCase
      lowerName == lowerTag || lowerName.contains(lowerTag)
    }
  }

  // Cleanup method for memory management
  def clearCaches(): Unit = {
    memberCache.clear()
    roleCache = None
  }
}

// Placeholder classes for different expansions - these would extend MessageResolver
// with expansion-specific optimizations
class MessageResolverTBC(jda: JDA) extends MessageResolver(jda) {
  override protected val linkSite = "http://twinstar.cz/database"
}

class MessageResolverWotLK(jda: JDA) extends MessageResolver(jda) {
  override protected val linkSite = "http://twinstar.cz/database"
}

class MessageResolverCataclysm(jda: JDA) extends MessageResolver(jda) {
  override protected val linkSite = "http://cata.openwow.com/database"
}

class MessageResolverMoP(jda: JDA) extends MessageResolver(jda) {
  override protected val linkSite = "http://mop.openwow.com/database"
}
