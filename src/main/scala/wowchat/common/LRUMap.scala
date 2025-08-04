package wowchat.common

import scala.collection.mutable

object LRUMap {
  // Optimized default sizes based on typical usage patterns
  def empty[K, V](): mutable.Map[K, V] = empty(512) // Reduced from 10000 for better memory usage
  def empty[K, V](maxSize: Int): mutable.Map[K, V] = new LRUMap[K, V](maxSize)
}

/**
 * Optimized LRU Map implementation with better performance characteristics
 * Uses LinkedHashMap for O(1) access and maintains LRU order efficiently
 */
class LRUMap[K, V](maxSize: Int) extends mutable.LinkedHashMap[K, V] {

  override def get(key: K): Option[V] = {
    // Move to end (most recently used) on access
    super.remove(key) match {
      case Some(value) =>
        super.put(key, value)
        Some(value)
      case None => None
    }
  }

  override def put(key: K, value: V): Option[V] = {
    // Remove existing entry if present
    val oldValue = super.remove(key)
    
    // Evict least recently used entries if needed
    while (size >= maxSize) {
      firstEntry match {
        case null => // Empty map
        case entry => super.remove(entry.key)
      }
    }
    
    super.put(key, value)
    oldValue
  }

  override def += (kv: (K, V)): this.type = {
    put(kv._1, kv._2)
    this
  }

  // Optimized bulk operations
  override def ++= (xs: IterableOnce[(K, V)]): this.type = {
    xs.iterator.foreach { case (k, v) => put(k, v) }
    this
  }

  // Memory-efficient size checking
  def isFull: Boolean = size >= maxSize
  
  // Get current capacity utilization (for monitoring)
  def utilizationRatio: Double = size.toDouble / maxSize
}
