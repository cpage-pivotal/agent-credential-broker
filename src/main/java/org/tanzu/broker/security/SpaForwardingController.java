package org.tanzu.broker.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {

    @GetMapping(value = {"/grants", "/delegations", "/systems/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
