package com.example.EthQR.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/parser")
    public String parser() {
        return "parser";
    }

    @GetMapping("/certification")
    public String certification() {
        return "certification";
    }

    @GetMapping("/mobile-simulator")
    public String mobileSimulator() {
        return "mobile-simulator";
    }
}
