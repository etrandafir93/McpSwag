package com.etrandafir.mcpswag.spec

enum SpecSource:
  case Url(name: String, url: String)
  case File(name: String, path: String)

object SpecSource:
  extension (s: SpecSource)
    def name: String = s match
      case Url(n, _)  => n
      case File(n, _) => n
