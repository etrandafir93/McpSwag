package com.etrandafir.mcpswag.web

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

class LogAppender extends AppenderBase[ILoggingEvent]:
  override def append(event: ILoggingEvent): Unit =
    val simpleName = event.getLoggerName.split("\\.").lastOption.getOrElse(event.getLoggerName)
    LogBuffer.append(LogEntry(
      ts      = event.getTimeStamp,
      level   = event.getLevel.toString,
      logger  = simpleName,
      message = event.getFormattedMessage
    ))
