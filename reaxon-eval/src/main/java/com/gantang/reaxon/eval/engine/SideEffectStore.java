package com.gantang.reaxon.eval.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Records every fake-tool invocation during a scenario run.
 *
 * <p>KC-Bench principle: assertions should target the <b>environmental end
 * state</b> (which tool was called with what arguments, in what order) rather
 * than reply wording. This store is that environment's ledger.
 */
public class SideEffectStore {

    public record Record(
            int order,
            String name,
            Map<String, Object> params,
            boolean success,
            String content,
            String error
    ) {}

    private final List<Record> records = Collections.synchronizedList(new ArrayList<>());

    void record(String name, Map<String, Object> params, boolean success, String content, String error) {
        records.add(new Record(records.size(), name,
                params == null ? Map.of() : Map.copyOf(params), success, content, error));
    }

    public List<Record> all() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public List<Record> byName(String name) {
        return all().stream().filter(r -> r.name().equals(name)).toList();
    }

    public int count(String name) {
        return (int) all().stream().filter(r -> r.name().equals(name)).count();
    }
}
