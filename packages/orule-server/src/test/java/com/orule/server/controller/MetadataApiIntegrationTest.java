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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RFC-0015 — Metadata CRUD API integration tests.
 *
 * <p>Covers: DomainType, ObjectType, AttributeType, EnumType (+EnumValue),
 * FunctionLib. Each endpoint exercised with realistic sample data.
 *
 * <p>Test isolation: {@code @Transactional} on the test class rolls back
 * everything between methods; tests are ordered to exercise FK chains.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
class MetadataApiIntegrationTest {

    @Autowired
    private WebApplicationContext ctx;

    @Autowired
    private ObjectMapper om;

    private MockMvc mvc;

    @PostConstruct
    void init() {
        this.mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    // ===== DomainType =====

    @Test
    @Order(1)
    @DisplayName("DomainType: GET list returns seed data from V5")
    void domainTypeListHasSeed() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/domain-types"))
            .andExpect(status().isOk())
            .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("ORDER") && body.contains("CUSTOMER"),
            "Expected seed codes ORDER and CUSTOMER, got: " + body);
    }

    @Test
    @Order(2)
    @DisplayName("DomainType: POST creates a new domain, GET-by-code returns it")
    void domainTypeCreateAndLookup() throws Exception {
        String body = """
            {"code":"MALL","name":"MALL Domain","description":"Mall domain",
             "ownerCode":"team-mall"}
            """;
        MvcResult created = mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.code").value("MALL"))
            .andExpect(jsonPath("$.data.name").value("MALL Domain"))
            .andExpect(jsonPath("$.data.ownerCode").value("team-mall"))
            .andReturn();
        JsonNode data = om.readTree(created.getResponse().getContentAsString()).get("data");
        String id = data.get("id").asText();
        assertNotNull(id);
        assertTrue(id.length() > 8);

        mvc.perform(get("/api/v1/domain-types/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("MALL"));

        mvc.perform(get("/api/v1/domain-types/by-code/MALL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("MALL Domain"));
    }

    @Test
    @Order(3)
    @DisplayName("DomainType: POST with duplicate code returns 409 Conflict")
    void domainTypeConflict() throws Exception {
        // Seed first domain to ensure the duplicate exists.
        String seed = """
            {"code":"CONFLICT_D1","name":"seed","description":"s"}
            """;
        mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(seed))
            .andExpect(status().isOk());

        // Same code again → 409
        String body = """
            {"code":"CONFLICT_D1","name":"dup","description":"dup"}
            """;
        mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @Order(4)
    @DisplayName("DomainType: POST with missing required field returns 400")
    void domainTypeValidationFails() throws Exception {
        String body = """
            {"description":"missing name and code"}
            """;
        mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    @DisplayName("DomainType: PUT updates name and description")
    void domainTypeUpdate() throws Exception {
        // create first
        String create = """
            {"code":"TEMP_D1","name":"temp","description":"d"}
            """;
        MvcResult c = mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isOk()).andReturn();
        String id = om.readTree(c.getResponse().getContentAsString()).get("data").get("id").asText();

        String update = """
            {"name":"temp updated","description":"new desc","ownerCode":"team-x"}
            """;
        mvc.perform(put("/api/v1/domain-types/" + id)
                .contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("temp updated"))
            .andExpect(jsonPath("$.data.description").value("new desc"))
            .andExpect(jsonPath("$.data.ownerCode").value("team-x"));
    }

    @Test
    @Order(6)
    @DisplayName("DomainType: GET unknown id returns 404")
    void domainTypeNotFound() throws Exception {
        mvc.perform(get("/api/v1/domain-types/nonexistent-id"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(7)
    @DisplayName("DomainType: DELETE removes entity")
    void domainTypeDelete() throws Exception {
        String create = """
            {"code":"DEL_D1","name":"del","description":"d"}
            """;
        MvcResult c = mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isOk()).andReturn();
        String id = om.readTree(c.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(delete("/api/v1/domain-types/" + id))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/domain-types/" + id))
            .andExpect(status().isNotFound());
    }

    // ===== ObjectType =====

    @Test
    @Order(10)
    @DisplayName("ObjectType: POST creates object under seed SMART_HOME domain")
    void objectTypeCreate() throws Exception {
        String body = """
            {"domainId":"%s","code":"CUSTOMER","name":"Customer",
             "description":"customer entity"}
            """.formatted(seedDomainId("SMART_HOME"));
        mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("CUSTOMER"));
    }

    @Test
    @Order(11)
    @DisplayName("ObjectType: GET ?domainId= filters list")
    void objectTypeFilterByDomain() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        mvc.perform(get("/api/v1/object-types?domainId=" + domainId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(12)
    @DisplayName("ObjectType: GET {id}/with-attributes returns attribute list")
    void objectTypeWithAttributes() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        // create object
        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","code":"ROOM","name":"Room","description":"r"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        // add attribute
        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"objectId":"%s","code":"AREA","name":"Area","dataType":"decimal",
                     "required":true,"defaultValue":"0.0","description":"room area"}
                    """.formatted(objId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("AREA"));

        mvc.perform(get("/api/v1/object-types/" + objId + "/with-attributes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.attributes").isArray())
            .andExpect(jsonPath("$.data.attributes[0].code").value("AREA"));
    }

    // ===== AttributeType =====

    @Test
    @Order(13)
    @DisplayName("AttributeType: GET ?objectId= lists attributes for an object")
    void attributeTypeList() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","code":"DEVICE","name":"Device","description":"d"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"objectId":"%s","code":"MODEL","name":"Model","dataType":"string",
                     "required":false,"defaultValue":"","description":"device model"}
                    """.formatted(objId)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/attribute-types?objectId=" + objId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].code").value("MODEL"));
    }

    // ===== EnumType + EnumValue =====

    @Test
    @Order(20)
    @DisplayName("EnumType: POST creates enum and POST enum-values adds values")
    void enumTypeCreateWithValues() throws Exception {
        String body = """
            {"code":"DEVICE_STATUS","name":"Device Status",
             "description":"on/off/unknown"}
            """;
        MvcResult enumRes = mvc.perform(post("/api/v1/enum-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("DEVICE_STATUS"))
            .andReturn();
        String enumId = om.readTree(enumRes.getResponse().getContentAsString())
            .get("data").get("id").asText();

        // enum-values endpoint: POST /api/v1/enum-types/{id}/values
        mvc.perform(post("/api/v1/enum-types/" + enumId + "/values")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"code":"ON","name":"On","sortOrder":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.values[?(@.code=='ON')]").exists());

        mvc.perform(post("/api/v1/enum-types/" + enumId + "/values")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"code":"OFF","name":"Off","sortOrder":2}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.values[?(@.code=='OFF')]").exists());

        mvc.perform(get("/api/v1/enum-types/" + enumId + "/values"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.values").isArray())
            .andExpect(jsonPath("$.data.values[?(@.code=='ON')]").exists())
            .andExpect(jsonPath("$.data.values[?(@.code=='OFF')]").exists())
            .andExpect(jsonPath("$.data.values.length()").value(2));
    }

    // ===== FunctionLib =====

    @Test
    @Order(30)
    @DisplayName("FunctionLib: POST creates a function library entry")
    void functionLibCreate() throws Exception {
        String body = """
            {"code":"SUM","name":"Sum","signature":"sum(List<Number>) -> Number",
             "description":"sum numbers","category":"math","builtin":true}
            """;
        mvc.perform(post("/api/v1/function-libs")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("SUM"))
            .andExpect(jsonPath("$.data.builtin").value(true));
    }

    @Test
    @Order(31)
    @DisplayName("FunctionLib: GET ?category=math filters list")
    void functionLibFilter() throws Exception {
        mvc.perform(post("/api/v1/function-libs")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"code":"AVG","name":"Avg","signature":"avg(List<Number>) -> Number",
                     "description":"avg","category":"math","builtin":true}
                    """)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/function-libs?category=math"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[?(@.code=='AVG')]").exists());
    }

    @Test
    @Order(32)
    @DisplayName("FunctionLib: PUT updates name and signature")
    void functionLibUpdate() throws Exception {
        MvcResult c = mvc.perform(post("/api/v1/function-libs")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"code":"UPD_ME","name":"upd","signature":"sig()",
                     "description":"d","category":"util","builtin":false}
                    """)).andExpect(status().isOk()).andReturn();
        String id = om.readTree(c.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(put("/api/v1/function-libs/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"upd v2","signature":"sig(Integer)",
                     "description":"new","category":"util","builtin":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("upd v2"))
            .andExpect(jsonPath("$.data.signature").value("sig(Integer)"));
    }

    // ===== Helper =====

    private String seedDomainId(String code) throws Exception {
        try {
            MvcResult res = mvc.perform(get("/api/v1/domain-types/by-code/" + code))
                .andExpect(status().isOk()).andReturn();
            return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
        } catch (AssertionError notFound) {
            // Code not seeded: create it on demand for this test.
            String body = """
                {"code":"%s","name":"%s Domain","description":"auto-seeded for test","ownerCode":"test"}
                """.formatted(code, code);
            MvcResult res = mvc.perform(post("/api/v1/domain-types")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
            return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
        }
    }
}
