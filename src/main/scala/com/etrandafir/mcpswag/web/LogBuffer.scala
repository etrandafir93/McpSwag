package com.etrandafir.mcpswag.web

import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.{ConcurrentLinkedDeque, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*

case class LogEntry(ts: Long, level: String, logger: String, message: String)

object LogBuffer:
  private val MaxSize = 500
  private val entries = new ConcurrentLinkedDeque[LogEntry]()
  private val emitters = new CopyOnWriteArrayList[SseEmitter]()

  def append(entry: LogEntry): Unit =
    while entries.size >= MaxSize do entries.pollFirst()
    entries.addLast(entry)
    emitters.asScala.foreach: emitter =>
      try emitter.send(SseEmitter.event().data(entry, MediaType.APPLICATION_JSON))
      catch case _ => emitters.remove(emitter)

  def recent: List[LogEntry] = entries.asScala.toList

  def register(emitter: SseEmitter): Unit =
    emitters.add(emitter)
    emitter.onCompletion(() => emitters.remove(emitter))
    emitter.onTimeout(() => emitters.remove(emitter))
    emitter.onError(_ => emitters.remove(emitter))
