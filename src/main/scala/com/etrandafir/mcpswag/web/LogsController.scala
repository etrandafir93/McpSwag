package com.etrandafir.mcpswag.web

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.{GetMapping, RestController}
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class LogsController:

  @GetMapping(value = Array("/api/logs"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
  def streamLogs(): SseEmitter =
    val emitter = new SseEmitter(0L) // no timeout — client reconnects on drop
    try
      LogBuffer.recent.foreach: entry =>
        emitter.send(SseEmitter.event().data(entry, MediaType.APPLICATION_JSON))
      LogBuffer.register(emitter)
    catch
      case e: Exception => emitter.completeWithError(e)
    emitter
