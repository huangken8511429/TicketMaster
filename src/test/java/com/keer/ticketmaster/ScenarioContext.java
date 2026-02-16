package com.keer.ticketmaster;

import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨模組共用的 Scenario context，儲存 BDD 步驟之間的測試狀態
 */
@Component
public class ScenarioContext {

    private final Map<String, Object> context = new HashMap<>();
    private MvcResult lastResponse;

    public void setLastResponse(MvcResult result) {
        this.lastResponse = result;
    }

    public MvcResult getLastResponse() {
        return lastResponse;
    }

    public void set(String key, Object value) {
        context.put(key, value);
    }

    public Object get(String key) {
        return context.get(key);
    }

    public void clear() {
        context.clear();
        lastResponse = null;
    }
}
