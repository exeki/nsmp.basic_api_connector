package nsmp_basic_api_connector;

import org.junit.jupiter.api.Assumptions;
import ru.kazantsev.nsmp.basic_api_connector.Connector;
import ru.kazantsev.nsmp.basic_api_connector.ConnectorParams;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static nsmp_basic_api_connector.TestConstants.SERVICE_CALL_METACLASS;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class TestUtils {

    private static final String DEFAULT_INSTALLATION_ID = "EXEKI1";

    public static Connector getApi() {
        Path configPath = Path.of(ConnectorParams.getDefaultParamsFilePath());
        Assumptions.assumeTrue(
                Files.exists(configPath),
                "NSMP integration config not found at " + configPath
        );
        try {
            ConnectorParams params = ConnectorParams.byConfigFile(DEFAULT_INSTALLATION_ID);
            return new Connector(params);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    public static File getMetainfoFile() {
        File file = new File(System.getProperty("user.home"), "metainfo.xml");
        try {
            if (!file.exists()) {
                Files.createFile(file.toPath());
            }
            return file;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static File resourceFile(String name) {
        var url = TestUtils.class.getClassLoader().getResource(name);
        assertNotNull(url, "Missing test resource: " + name);
        try {
            return new File(url.toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String resourceText(String name) {
        try {
            return Files.readString(resourceFile(name).toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> createServiceCallPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("metaClass", SERVICE_CALL_METACLASS);
        payload.put("description", "java-test");
        payload.put("clientEmployee", "employee$10501");
        payload.put("clientOU", "ou$10001");
        payload.put("agreement", "agreement$9101");
        payload.put("service", "slmService$9301");
        return payload;
    }
}
