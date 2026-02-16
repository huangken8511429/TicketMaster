package com.keer.ticketmaster;

import io.cucumber.java.zh_tw.那麼;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨模組共用的 Then 步驟 — HTTP 狀態碼驗證
 */
public class CommonThenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @那麼("系統應該回傳 201 Created")
    public void 系統應該回傳201Created() {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");
        assertEquals(201, result.getResponse().getStatus(), "HTTP狀態碼應為 201 Created");
    }

    @那麼("系統應該回傳 200 OK")
    public void 系統應該回傳200OK() {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");
        assertEquals(200, result.getResponse().getStatus(), "HTTP狀態碼應為 200 OK");
    }

    @那麼("系統應該回傳 202 Accepted")
    public void 系統應該回傳202Accepted() {
        MvcResult result = scenarioContext.getLastResponse();
        assertNotNull(result, "應該有前一個HTTP回應");
        assertEquals(202, result.getResponse().getStatus(), "HTTP狀態碼應為 202 Accepted");
    }
}
