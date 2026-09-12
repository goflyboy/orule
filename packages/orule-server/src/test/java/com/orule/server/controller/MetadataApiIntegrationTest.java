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

import java.util.List;

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
 * RFC-0032 — Metadata CRUD API integration tests.
 *
 * <p>关键变更（vs RFC-0031 集成测试）：
 * <ul>
 *   <li>所有 code 字段改为 programCode</li>
 *   <li>ObjectType 支持 kind=ENUM + enumValues（enum 升格为 ObjectType 特殊形态）</li>
 *   <li>AttributeType 用 3 列结构（dataType + subDataTypeProgramCode + sub2），
 *       移除 type JSON 字段；服务端自动组装 Type 树</li>
 *   <li>by-code 路径改为 by-program-code</li>
 * </ul>
 *
 * <p>Test isolation: {@code @Transactional} rolls back everything between methods.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
public class MetadataApiIntegrationTest {

    @Autowired private WebApplicationContext ctx;
    @Autowired private ObjectMapper om;

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
    @DisplayName("DomainType: POST creates, GET-by-program-code returns it")
    void domainTypeCreateAndLookup() throws Exception {
        String body = """
            {"programCode":"MALL","name":"MALL Domain","description":"Mall domain",
             "ownerCode":"team-mall"}
            """;
        mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.programCode").value("MALL"))
            .andExpect(jsonPath("$.data.name").value("MALL Domain"))
            .andExpect(jsonPath("$.data.ownerCode").value("team-mall"));

        mvc.perform(get("/api/v1/domain-types/by-program-code/MALL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("MALL Domain"));
    }

    @Test
    @Order(3)
    @DisplayName("DomainType: POST with duplicate programCode returns 409 Conflict")
    void domainTypeConflict() throws Exception {
        String seed = """
            {"programCode":"CONFLICT_D1","name":"seed","description":"s"}
            """;
        mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(seed))
            .andExpect(status().isOk());

        String body = """
            {"programCode":"CONFLICT_D1","name":"dup","description":"dup"}
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
            {"description":"missing name and programCode"}
            """;
        mvc.perform(post("/api/v1/domain-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    @DisplayName("DomainType: PUT updates name and description")
    void domainTypeUpdate() throws Exception {
        String create = """
            {"programCode":"TEMP_D1","name":"temp","description":"d"}
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
            {"programCode":"DEL_D1","name":"del","description":"d"}
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
            {"domainId":"%s","programCode":"CUSTOMER","name":"Customer",
             "kind":"CLASS","description":"customer entity"}
            """.formatted(seedDomainId("SMART_HOME"));
        mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.programCode").value("CUSTOMER"))
            .andExpect(jsonPath("$.data.kind").value("CLASS"));
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
        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","programCode":"ROOM","name":"Room",
                     "kind":"CLASS","description":"r"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        // RFC-0032: attribute 用 3 列结构，无 type JSON 字段
        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"objectId":"%s","programCode":"AREA","name":"Area",
                     "dataType":"primitive","subDataTypeProgramCode":"number",
                     "required":true,"defaultValue":"0.0","description":"room area"}
                    """.formatted(objId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.programCode").value("AREA"))
            .andExpect(jsonPath("$.data.dataType").value("primitive"))
            // 服务端组装的 Type 树
            .andExpect(jsonPath("$.data.type.kind").value("primitive"))
            .andExpect(jsonPath("$.data.type.name").value("number"));

        mvc.perform(get("/api/v1/object-types/" + objId + "/with-attributes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.attributes").isArray())
            .andExpect(jsonPath("$.data.attributes[0].programCode").value("AREA"));
    }

    // ===== AttributeType =====

    @Test
    @Order(13)
    @DisplayName("AttributeType: GET ?objectId= lists attributes for an object")
    void attributeTypeList() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","programCode":"DEVICE","name":"Device",
                     "kind":"CLASS","description":"d"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"objectId":"%s","programCode":"MODEL","name":"Model",
                     "dataType":"primitive","subDataTypeProgramCode":"string",
                     "required":false,"defaultValue":"","description":"device model"}
                    """.formatted(objId)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/attribute-types?objectId=" + objId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].programCode").value("MODEL"));
    }

    @Test
    @Order(14)
    @DisplayName("AttributeType: object→CustomerTier(ENUM) 跨 attribute 共享")
    void attributeTypeEnumViaObjectRef() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        // 创建 ObjectType(kind=ENUM) 作为 SensorStatus
        MvcResult enumObj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","programCode":"SensorStatus","name":"Sensor Status",
                     "kind":"ENUM",
                     "enumValues":[
                       {"code":"ON","label":"On","sortOrder":1},
                       {"code":"OFF","label":"Off","sortOrder":2}
                     ]}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String enumObjId = om.readTree(enumObj.getResponse().getContentAsString())
            .get("data").get("id").asText();

        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","programCode":"SENSOR","name":"Sensor",
                     "kind":"CLASS","description":"s"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        // attribute 引用 ObjectType(kind=ENUM)
        String body = """
            {"objectId":"%s","programCode":"STATUS","name":"Status",
             "dataType":"object","subDataTypeProgramCode":"SensorStatus",
             "required":true}
            """.formatted(objId);
        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.programCode").value("STATUS"))
            .andExpect(jsonPath("$.data.dataType").value("object"))
            // 服务端组装的 Type 树：ObjectRef
            .andExpect(jsonPath("$.data.type.kind").value("object"))
            .andExpect(jsonPath("$.data.type.programCode").value("SensorStatus"));

        // 验证 enum 定义独立存储，不在 attribute 中
        mvc.perform(get("/api/v1/object-types/" + enumObjId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kind").value("ENUM"))
            .andExpect(jsonPath("$.data.enumValues[?(@.code=='ON')]").exists())
            .andExpect(jsonPath("$.data.enumValues[?(@.code=='OFF')]").exists());
    }

    @Test
    @Order(15)
    @DisplayName("AttributeType: list 类型 attribute（3 列 sub=element type）")
    void attributeTypeListNested() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","programCode":"THERMOSTAT","name":"Thermostat",
                     "kind":"CLASS","description":"t"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"objectId":"%s","programCode":"READINGS","name":"Readings",
                     "dataType":"list","subDataTypeProgramCode":"number",
                     "required":false}
                    """.formatted(objId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.programCode").value("READINGS"))
            // 服务端组装的 Type 树
            .andExpect(jsonPath("$.data.type.kind").value("list"))
            .andExpect(jsonPath("$.data.type.elementType.kind").value("primitive"))
            .andExpect(jsonPath("$.data.type.elementType.name").value("number"));
    }

    @Test
    @Order(16)
    @DisplayName("AttributeType: map 类型 attribute（3 列 sub=key, sub2=value）")
    void attributeTypeMapNested() throws Exception {
        String domainId = seedDomainId("SMART_HOME");
        MvcResult obj = mvc.perform(post("/api/v1/object-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"domainId":"%s","programCode":"WIDGET","name":"Widget",
                     "kind":"CLASS","description":"w"}
                    """.formatted(domainId)))
            .andExpect(status().isOk()).andReturn();
        String objId = om.readTree(obj.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(post("/api/v1/attribute-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"objectId":"%s","programCode":"META","name":"Meta",
                     "dataType":"map","subDataTypeProgramCode":"string",
                     "subDataTypeProgramCode2":"number","required":false}
                    """.formatted(objId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type.kind").value("map"))
            .andExpect(jsonPath("$.data.type.keyType.kind").value("primitive"))
            .andExpect(jsonPath("$.data.type.valueType.kind").value("primitive"));
    }

    // ===== FuntionType =====

    @Test
    @Order(30)
    @DisplayName("FuntionType: POST creates a function type entry (signature 为 JSON 树)")
    void funtionTypeCreate() throws Exception {
        String body = """
            {"programCode":"SUM","name":"Sum",
             "signature":{
               "params":[{"kind":"list","elementType":{"kind":"primitive","name":"number"}}],
               "return":{"kind":"primitive","name":"number"}
             },
             "description":"sum numbers","category":"math","builtin":true}
            """;
        mvc.perform(post("/api/v1/funtion-types")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.programCode").value("SUM"))
            .andExpect(jsonPath("$.data.builtin").value(true))
            .andExpect(jsonPath("$.data.signature.return.kind").value("primitive"))
            .andExpect(jsonPath("$.data.signature.params.length()").value(1));
    }

    @Test
    @Order(31)
    @DisplayName("FuntionType: GET ?category=math filters list")
    void funtionTypeFilter() throws Exception {
        mvc.perform(post("/api/v1/funtion-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"programCode":"AVG","name":"Avg",
                     "signature":{
                       "params":[{"kind":"list","elementType":{"kind":"primitive","name":"number"}}],
                       "return":{"kind":"primitive","name":"number"}
                     },
                     "description":"avg","category":"math","builtin":true}
                    """)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/funtion-types?category=math"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[?(@.programCode=='AVG')]").exists());
    }

    @Test
    @Order(32)
    @DisplayName("FuntionType: PUT updates name and signature")
    void funtionTypeUpdate() throws Exception {
        MvcResult c = mvc.perform(post("/api/v1/funtion-types")
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"programCode":"UPD_ME","name":"upd",
                     "signature":{
                       "params":[],
                       "return":{"kind":"primitive","name":"string"}
                     },
                     "description":"d","category":"util","builtin":false}
                    """)).andExpect(status().isOk()).andReturn();
        String id = om.readTree(c.getResponse().getContentAsString()).get("data").get("id").asText();

        mvc.perform(put("/api/v1/funtion-types/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"upd v2",
                     "signature":{
                       "params":[{"kind":"primitive","name":"number"}],
                       "return":{"kind":"primitive","name":"number"}
                     },
                     "description":"new","category":"util","builtin":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("upd v2"))
            .andExpect(jsonPath("$.data.signature.params.length()").value(1));
    }

    // ===== Helper =====

    private String seedDomainId(String programCode) throws Exception {
        try {
            MvcResult res = mvc.perform(get("/api/v1/domain-types/by-program-code/" + programCode))
                .andExpect(status().isOk()).andReturn();
            return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
        } catch (AssertionError notFound) {
            String body = """
                {"programCode":"%s","name":"%s Domain","description":"auto-seeded for test","ownerCode":"test"}
                """.formatted(programCode, programCode);
            MvcResult res = mvc.perform(post("/api/v1/domain-types")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
            return om.readTree(res.getResponse().getContentAsString()).get("data").get("id").asText();
        }
    }
}
