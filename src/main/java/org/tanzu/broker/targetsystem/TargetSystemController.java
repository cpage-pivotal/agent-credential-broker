package org.tanzu.broker.targetsystem;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/target-systems")
public class TargetSystemController {

    private final TargetSystemRegistry registry;

    public TargetSystemController(TargetSystemRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<TargetSystem> listTargetSystems() {
        return registry.getAll();
    }
}
