package nsmp_basic_api_connector;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.kazantsev.nsmp.basic_api_connector.Connector;
import ru.kazantsev.nsmp.basic_api_connector.dto.nsmp.FileDto;
import ru.kazantsev.nsmp.basic_api_connector.dto.nsmp.ScriptChecksums;
import ru.kazantsev.nsmp.basic_api_connector.dto.nsmp.ServiceTimeExclusionDto;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static nsmp_basic_api_connector.TestUtils.*;
import static nsmp_basic_api_connector.TestConstants.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorMethodTests {

    private static String testServiceTimeUuid;
    private static String testServiceCallUuid;

    public static Logger logger = LoggerFactory.getLogger(ConnectorMethodTests.class);

    private static Connector api() {
        return TestUtils.getApi();
    }

    @BeforeEach
    void logTestStart(TestInfo testInfo) {
        logger.info("Running test: {}", testInfo.getDisplayName());
    }

    @BeforeAll
    static void initServiceCall() {
        logger.info("Инициализация ServiceCall");
        Connector api = api();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(TEST_OBJECT_DATE_TIME_PATTERN);
        var title = "test_" + LocalDateTime.now().format(dtf);
        Map<String, Object> payload = createServiceCallPayload();
        payload.put("title", title);
        HashMap<String, Object> result = api.createM2M(SERVICE_CALL_METACLASS, payload);
        testServiceCallUuid = result.get("UUID").toString();
        logger.info("Создан тестовый serviceCall, title: {}, uuid: {}", title, testServiceCallUuid);

    }

    @BeforeAll
    static void initServiceTime() {
        logger.info("Инициализация ServiceTime");
        Connector api = api();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(TEST_OBJECT_DATE_TIME_PATTERN);
        var code = "test_" + LocalDateTime.now().format(dtf);
        Map<String, Object> payload = Map.of("title", code, "code", code, "status", "active");
        HashMap<String, Object> result = api.createM2M(SERVICE_TIME_METACLASS, payload);
        testServiceTimeUuid = result.get("UUID").toString();
        logger.info("Создан тестовый serviceTime, code: {}, uuid: {}", code, testServiceTimeUuid);
    }

    @AfterAll
    static void deleteTestServiceCall() {
        if (DELETE_TEST_OBJECTS_AFTER_TESTS) {
            api().delete(testServiceCallUuid);
            logger.info("Тестовый ServiceCall удален");
        }
    }

    @AfterAll
    static void deleteTestServiceTime() {
        if (DELETE_TEST_OBJECTS_AFTER_TESTS) {
            api().delete(testServiceTimeUuid);
            logger.info("Тестовый ServiceTime удален");
        }
    }

    @Test
    void create() {
        assertDoesNotThrow(() -> api().create(SERVICE_CALL_METACLASS, createServiceCallPayload()));
    }

    @Test
    void addFile() {
        assertDoesNotThrow(() -> api().addFile(testServiceCallUuid, resourceFile("test/testFile.txt")));
    }

    @Test
    void addFileFromBytes() {
        byte[] bytes = resourceText("test/testFile.txt").getBytes(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> api().addFile(testServiceCallUuid, bytes, "get.txt"));
    }

    @Test
    void addFileFromFileList() {
        List<File> files = List.of(resourceFile("test/testFile.txt"));
        assertDoesNotThrow(() -> api().addFile(testServiceCallUuid, files));
    }

    @Test
    void createExcl() throws Exception {
        ServiceTimeExclusionDto excl = api().createExcl(
                testServiceTimeUuid,
                new SimpleDateFormat(DATE_PATTERN).parse("2022-01-15")
        );
        assertNotNull(excl);
        assertNotNull(excl.uuid);
    }

    @Test
    void createM2M() {
        Connector api = api();
        Map<String, Object> payload = createServiceCallPayload();
        HashMap<String, Object> result = api.createM2M(SERVICE_CALL_METACLASS, payload);
        assertNotNull(result);
        assertNotNull(result.get("UUID"));
        api.delete(String.valueOf(result.get("UUID")));
    }

    @Test
    void createM2MMultiple() {
        Connector api = api();
        List<Map<String, Object>> objects = new ArrayList<>();
        objects.add(createServiceCallPayload());
        objects.add(createServiceCallPayload());
        objects.add(createServiceCallPayload());

        List<HashMap<String, Object>> result = api.createM2MMultiple(objects);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        for (HashMap<String, Object> item : result) {
            Object uuid = item.get("UUID");
            if (uuid != null) {
                api.delete(String.valueOf(uuid));
            }
        }
    }

    @Test
    void delete() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        assertDoesNotThrow(() -> api.delete(uuid));
    }

    @Test
    void edit() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        assertDoesNotThrow(() -> api.edit(uuid, Map.of("title", "TESETESTESTES")));
        api.delete(uuid);
    }

    @Test
    void editExcl() throws Exception {
        Connector api = api();
        ServiceTimeExclusionDto excl = api.createExcl(
                testServiceTimeUuid,
                new SimpleDateFormat(DATE_PATTERN).parse("2023-01-17")
        );
        ServiceTimeExclusionDto edited = api.editExcl(excl.uuid, 28_800_000L, 53_000_000L);
        assertNotNull(edited);
        assertNotNull(edited.uuid);
    }

    @Test
    void editM2M() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        HashMap<String, Object> edited = api.editM2M(uuid, Map.of("title", "drthwrthwrh"), List.of("title", "UUID"));
        assertNotNull(edited);
        api.delete(uuid);
    }


    @Test
    void find() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        List<HashMap<String, Object>> result = api.find(SERVICE_CALL_METACLASS, Map.of("UUID", uuid), null, 0L, 1L);
        assertNotNull(result);
    }

    @Test
    void findWithoutPaging() {
        List<HashMap<String, Object>> result = api().find(SERVICE_CALL_METACLASS, Map.of());
        assertNotNull(result);
    }

    @Test
    void findWithReturnAttrs() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        List<HashMap<String, Object>> result = api.find(SERVICE_CALL_METACLASS, Map.of("UUID", uuid), List.of("title", "UUID"));
        assertNotNull(result);
    }

    @Test
    void get() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        HashMap<String, Object> result = api.get(uuid, List.of("title", "UUID"));
        assertNotNull(result);
    }

    @Test
    void getWithoutReturnAttrs() {
        Connector api = api();
        HashMap<String, Object> created = api.createM2M(SERVICE_CALL_METACLASS, createServiceCallPayload());
        String uuid = String.valueOf(created.get("UUID"));
        HashMap<String, Object> result = api.get(uuid);
        assertNotNull(result);
    }

    @Test
    void getAccessKey() {
        String key = api().getAccessKey("system", "manager", 1);
        assertNotNull(key);
        assertFalse(key.isBlank());
    }

    @Test
    void getFile() {
        var api = api();
        api.addFile(testServiceCallUuid, resourceFile("test/testFile.txt"));
        var files = api.find("file", Map.of("source", testServiceCallUuid));
        assertFalse(files.isEmpty());
        String fileUuid = files.getLast().get("UUID").toString();
        FileDto file = api().getFile(fileUuid);
        assertNotNull(file);
        assertNotNull(file.bytes);
        assertTrue(file.bytes.length > 0);
        assertNotNull(file.title);
    }

    @Test
    void getScripts() {
        String archive = api().getScripts();
        assertNotNull(archive);
        assertFalse(archive.isBlank());
    }

    @Test
    void getScriptsStatus() {
        ScriptChecksums checksums = api().getScriptsStatus();
        assertNotNull(checksums);
    }

    @Test
    void groovyVersion() {
        String value = api().groovyVersion();
        assertNotNull(value);
        assertFalse(value.isBlank());
    }

    @Test
    void jpdaInfo() {
        String value = api().jpdaInfo();
        assertNotNull(value);
    }

    @Test
    void metainfoWithTimeout() {
        String value = api().metainfo(15_000L);
        assertNotNull(value);
        assertFalse(value.isBlank());
    }

    @Test
    void metainfoDefaultTimeout() {
        String value = api().metainfo();
        assertNotNull(value);
    }

    @Test
    void pushScripts() {
        Connector api = api();
        ScriptChecksums checksums = api.pushScripts(api.getScripts().getBytes(StandardCharsets.UTF_8));
        assertNotNull(checksums);
    }

    @Test
    void version() {
        String value = api().version();
        assertNotNull(value);
        assertFalse(value.isBlank());
    }
}
