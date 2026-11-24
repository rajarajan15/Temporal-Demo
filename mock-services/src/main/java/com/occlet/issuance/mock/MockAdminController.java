package com.occlet.issuance.mock;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controls the mocks during a demo. */
@RestController
@RequestMapping("/mock-admin")
class MockAdminController {
    private final MockGate gate;
    private final ProcessorStore store;

    MockAdminController(MockGate gate, ProcessorStore store) {
        this.gate = gate;
        this.store = store;
    }

    @PutMapping("/failures/{tenant}/{mock}/{operation}")
    ResponseEntity<Void> setFailure(@PathVariable String tenant, @PathVariable String mock,
            @PathVariable String operation, @RequestBody FailureRule rule) {
        gate.setRule(tenant, mock, operation, rule);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset")
    ResponseEntity<Void> reset() {
        gate.reset();
        store.reset();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cards/{tenant}")
    List<ProcessorStore.CardView> cards(@PathVariable String tenant) {
        return store.list(tenant);
    }

    @GetMapping("/counters/{tenant}")
    Map<String, Object> counters(@PathVariable String tenant) {
        return gate.counters(tenant);
    }
}
