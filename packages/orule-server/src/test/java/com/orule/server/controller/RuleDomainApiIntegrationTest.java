package com.orule.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jakarta.annotation.PostConstruct;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RFC-0016 — Rule domain + state machine API integration tests.
 *
 * <p>Covers: RuleSet, Rule, RuleVersion CRUD plus the full state machine
 * (MAINTENANCE → PUBLISHED → RETIRED), clone, auto-version increment,
 * auto-retire of previous PUBLISHED on republish.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
class RuleDomainApiIntegrationTest {

    @Autowired private WebApplicationContext ctx;
    @Autowired private ObjectMapper om;
    private MockMvc mvc;

    @PostConstruct
    void init() {
        this.mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    // ===== Helpers =====

    private String createDomain(String code) throws Exception {
        String body = """
            {"code":"%s","name":"%s Domain","description":"d","ownerCode":"test"}
            """.formatted(code, code);
        MvcResult res = mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String createRuleSet(String code, String domainId) throws Exception {
        String body = """
            {"code":"%s","name":"%s","description":"d",
             "domainId":"%s","ownerCode":"test"}
            """.formatted(code, code, domainId);
        MvcResult res = mvc.perform(post("/api/v1/rule-sets")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String createRule(String ruleSetId, String code) throws Exception {
        String body = """
            {"ruleSetId":"%s","code":"%s","name":"%s",
             "description":"d","sortOrder":1,"ownerCode":"test"}
            """.formatted(ruleSetId, code, code);
        MvcResult res = mvc.perform(post("/api/v1/rules")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String createVersion(String ruleId, String simpleTs) throws Exception {
        String body = """
            {"ruleId":"%s","description":"v1","simpleTs":"%s","changelog":"init"}
            """.formatted(ruleId, simpleTs == null ? "" : simpleTs);
        MvcResult res = mvc.perform(post("/api/v1/rule-versions")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .param("createdBy", "alice"))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private JsonNode getVersion(String id) throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/rule-versions/" + id))
            .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("data");
    }

    // ===== RuleSet =====

    @Test
    @Order(1)
    @DisplayName("RuleSet: POST creates a rule set under a domain")
    void ruleSetCreate() throws Exception {
        String domainId = createDomain("BIZ_RS1");
        String body = """
            {"code":"RS_BIZ","name":"Biz Ruleset","description":"d",
             "domainId":"%s","ownerCode":"biz-team"}
            """.formatted(domainId);
        mvc.perform(post("/api/v1/rule-sets")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("RS_BIZ"))
            .andExpect(jsonPath("$.data.status").value("MAINTENANCE"));
    }

    @Test
    @Order(2)
    @DisplayName("RuleSet: GET {id}/with-rules includes child rules")
    void ruleSetWithRules() throws Exception {
        String domainId = createDomain("BIZ_RS2");
        String rsId = createRuleSet("RS_WITH", domainId);
        createRule(rsId, "R1");
        createRule(rsId, "R2");

        mvc.perform(get("/api/v1/rule-sets/" + rsId + "/with-rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rules").isArray())
            .andExpect(jsonPath("$.data.rules.length()").value(2));
    }

    @Test
    @Order(3)
    @DisplayName("RuleSet: PUT updates name and description")
    void ruleSetUpdate() throws Exception {
        String domainId = createDomain("BIZ_RS3");
        String rsId = createRuleSet("RS_UPD", domainId);
        mvc.perform(put("/api/v1/rule-sets/" + rsId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"RS updated","description":"new desc","ownerCode":"o"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("RS updated"));
    }

    @Test
    @Order(4)
    @DisplayName("RuleSet: DELETE removes rule set")
    void ruleSetDelete() throws Exception {
        String domainId = createDomain("BIZ_RS4");
        String rsId = createRuleSet("RS_DEL", domainId);
        mvc.perform(delete("/api/v1/rule-sets/" + rsId))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/rule-sets/" + rsId))
            .andExpect(status().isNotFound());
    }

    // ===== Rule =====

    @Test
    @Order(10)
    @DisplayName("Rule: POST creates a rule under a rule set")
    void ruleCreate() throws Exception {
        String domainId = createDomain("BIZ_R5");
        String rsId = createRuleSet("RS_R5", domainId);
        mvc.perform(post("/api/v1/rules")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"ruleSetId":"%s","code":"CHECK_AGE","name":"Check Age",
                     "description":"age > 18","sortOrder":1,"ownerCode":"o"}
                    """.formatted(rsId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("CHECK_AGE"));
    }

    @Test
    @Order(11)
    @DisplayName("Rule: GET ?ruleSetId= filters rules")
    void ruleFilterByRuleSet() throws Exception {
        String domainId = createDomain("BIZ_R6");
        String rsId = createRuleSet("RS_R6", domainId);
        createRule(rsId, "R1");
        mvc.perform(get("/api/v1/rules?ruleSetId=" + rsId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].code").value("R1"));
    }

    @Test
    @Order(12)
    @DisplayName("Rule: GET {id}/with-versions includes all versions")
    void ruleWithVersions() throws Exception {
        String domainId = createDomain("BIZ_R7");
        String rsId = createRuleSet("RS_R7", domainId);
        String ruleId = createRule(rsId, "R1");
        createVersion(ruleId, "age > 18");
        createVersion(ruleId, "age > 21");

        mvc.perform(get("/api/v1/rules/" + ruleId + "/with-versions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.versions").isArray())
            .andExpect(jsonPath("$.data.versions.length()").value(2));
    }

    // ===== RuleVersion state machine =====

    @Test
    @Order(20)
    @DisplayName("RuleVersion: POST creates MAINTENANCE version, version auto-increments")
    void versionCreate() throws Exception {
        String domainId = createDomain("BIZ_V1");
        String rsId = createRuleSet("RS_V1", domainId);
        String ruleId = createRule(rsId, "R1");

        String v1 = createVersion(ruleId, "age > 18");
        JsonNode data1 = getVersion(v1);
        org.junit.jupiter.api.Assertions.assertEquals(1, data1.get("version").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("MAINTENANCE", data1.get("status").asText());

        String v2 = createVersion(ruleId, "age > 21");
        JsonNode data2 = getVersion(v2);
        org.junit.jupiter.api.Assertions.assertEquals(2, data2.get("version").asInt());
    }

    @Test
    @Order(21)
    @DisplayName("RuleVersion: PUT edits MAINTENANCE version (simpleTs updated)")
    void versionUpdate() throws Exception {
        String domainId = createDomain("BIZ_V2");
        String rsId = createRuleSet("RS_V2", domainId);
        String ruleId = createRule(rsId, "R1");
        String versionId = createVersion(ruleId, "old");

        mvc.perform(put("/api/v1/rule-versions/" + versionId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"description":"updated","simpleTs":"new","changelog":"fix"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.simpleTs").value("new"))
            .andExpect(jsonPath("$.data.description").value("updated"));
    }

    @Test
    @Order(22)
    @DisplayName("RuleVersion: PUT on PUBLISHED returns 409 (only MAINTENANCE editable)")
    void versionUpdateOnlyMaintenance() throws Exception {
        String domainId = createDomain("BIZ_V3");
        String rsId = createRuleSet("RS_V3", domainId);
        String ruleId = createRule(rsId, "R1");
        String versionId = createVersion(ruleId, "ok");
        mvc.perform(post("/api/v1/rule-versions/" + versionId + "/publish"))
            .andExpect(status().isOk());

        mvc.perform(put("/api/v1/rule-versions/" + versionId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"description":"x","simpleTs":"y","changelog":"z"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @Order(23)
    @DisplayName("RuleVersion: publish MAINTENANCE → PUBLISHED, then retire → RETIRED")
    void versionPublishRetire() throws Exception {
        String domainId = createDomain("BIZ_V4");
        String rsId = createRuleSet("RS_V4", domainId);
        String ruleId = createRule(rsId, "R1");
        String versionId = createVersion(ruleId, "age > 18");

        // publish
        mvc.perform(post("/api/v1/rule-versions/" + versionId + "/publish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // retire
        mvc.perform(post("/api/v1/rule-versions/" + versionId + "/retire"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RETIRED"));
    }

    @Test
    @Order(24)
    @DisplayName("RuleVersion: publish without simpleTs returns 409")
    void versionPublishWithoutSimpleTs() throws Exception {
        String domainId = createDomain("BIZ_V5");
        String rsId = createRuleSet("RS_V5", domainId);
        String ruleId = createRule(rsId, "R1");
        String versionId = createVersion(ruleId, null); // simpleTs empty

        mvc.perform(post("/api/v1/rule-versions/" + versionId + "/publish"))
            .andExpect(status().isConflict());
    }

    @Test
    @Order(25)
    @DisplayName("RuleVersion: publish twice — previous PUBLISHED auto-RETIRED, new one PUBLISHED")
    void versionPublishTwiceAutoRetire() throws Exception {
        String domainId = createDomain("BIZ_V6");
        String rsId = createRuleSet("RS_V6", domainId);
        String ruleId = createRule(rsId, "R1");

        String v1 = createVersion(ruleId, "age > 18");
        String v2 = createVersion(ruleId, "age > 21");

        mvc.perform(post("/api/v1/rule-versions/" + v1 + "/publish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        mvc.perform(post("/api/v1/rule-versions/" + v2 + "/publish"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // v1 should now be RETIRED
        JsonNode v1After = getVersion(v1);
        org.junit.jupiter.api.Assertions.assertEquals("RETIRED", v1After.get("status").asText());
    }

    @Test
    @Order(26)
    @DisplayName("RuleVersion: retire non-PUBLISHED returns 409")
    void versionRetireOnlyPublished() throws Exception {
        String domainId = createDomain("BIZ_V7");
        String rsId = createRuleSet("RS_V7", domainId);
        String ruleId = createRule(rsId, "R1");
        String versionId = createVersion(ruleId, "ok");

        mvc.perform(post("/api/v1/rule-versions/" + versionId + "/retire"))
            .andExpect(status().isConflict());
    }

    @Test
    @Order(27)
    @DisplayName("RuleVersion: clone from PUBLISHED creates new MAINTENANCE version with bumped number")
    void versionClone() throws Exception {
        String domainId = createDomain("BIZ_V8");
        String rsId = createRuleSet("RS_V8", domainId);
        String ruleId = createRule(rsId, "R1");
        String v1 = createVersion(ruleId, "age > 18");
        mvc.perform(post("/api/v1/rule-versions/" + v1 + "/publish"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/rule-versions/" + v1 + "/clone")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"changelog":"cloned","createdBy":"bob"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.status").value("MAINTENANCE"))
            .andExpect(jsonPath("$.data.simpleTs").value("age > 18"))
            .andExpect(jsonPath("$.data.createdBy").value("bob"));
    }

    @Test
    @Order(28)
    @DisplayName("RuleVersion: GET /rules/{ruleId}/versions lists all versions of a rule")
    void versionListByRule() throws Exception {
        String domainId = createDomain("BIZ_V9");
        String rsId = createRuleSet("RS_V9", domainId);
        String ruleId = createRule(rsId, "R1");
        createVersion(ruleId, "a");
        createVersion(ruleId, "b");
        createVersion(ruleId, "c");

        mvc.perform(get("/api/v1/rules/" + ruleId + "/versions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @Order(29)
    @DisplayName("RuleVersion: DELETE removes version")
    void versionDelete() throws Exception {
        String domainId = createDomain("BIZ_V10");
        String rsId = createRuleSet("RS_V10", domainId);
        String ruleId = createRule(rsId, "R1");
        String versionId = createVersion(ruleId, "x");

        mvc.perform(delete("/api/v1/rule-versions/" + versionId))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/rule-versions/" + versionId))
            .andExpect(status().isNotFound());
    }
}
