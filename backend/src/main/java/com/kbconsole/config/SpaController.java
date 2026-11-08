package com.kbconsole.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/chat", "/series", "/series/create", "/series/{id:[0-9]+}",
            "/admin/users", "/admin/departments", "/admin/roles"})
    public String index() {
        return "forward:/index.html";
    }
}