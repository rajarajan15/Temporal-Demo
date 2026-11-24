package com.occlet.issuance.mock;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
    private final CallbackSender callbacks;

    MockAdminController(MockGate gate, ProcessorStore store, CallbackSender callbacks) {
        this.gate = gate;
        this.store = store;
        this.callbacks = callbacks;
    }

    @PutMapping("/failures/{tenant}/{mock}/{operation}")
    ResponseEntity<Void> setFailure(@PathVariable String tenant, @PathVariable String mock,
            @PathVariable String operation, @RequestBody FailureRule rule) {
        gate.setRule(tenant, mock, operation, rule);
        return ResponseEntity.noContent().build();
    }

    /** How the processor delivers confirmation callbacks for a tenant: NORMAL, DELAY, DUPLICATE or NEVER. */
    @PutMapping("/callbacks/{tenant}")
    ResponseEntity<Void> setCallbacks(@PathVariable String tenant, @RequestBody CallbackSender.CallbackRule rule) {
        callbacks.setRule(tenant, rule);
        return ResponseEntity.noContent().build();
    }

    /** Creates an active card at the processor that the platform never issued. */
    @PostMapping("/cards/{tenant}/orphan")
    ResponseEntity<ProcessorStore.Created> orphan(@PathVariable String tenant) {
        return ResponseEntity.status(HttpStatus.CREATED).body(store.createOrphan(tenant));
    }

    @PostMapping("/reset")
    ResponseEntity<Void> reset() {
        gate.reset();
        store.reset();
        callbacks.reset();
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
