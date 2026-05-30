package com.etrandafir.mcpswag.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class UiController:
  @GetMapping(Array("/"))
  def index(): String = "index"
