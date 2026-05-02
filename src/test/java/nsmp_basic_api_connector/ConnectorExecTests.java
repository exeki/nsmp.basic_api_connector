package nsmp_basic_api_connector;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.kazantsev.nsmp.basic_api_connector.Connector;

import static nsmp_basic_api_connector.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorExecTests {

    public static Logger logger = LoggerFactory.getLogger(ConnectorExecTests.class);

    private static Connector api() {
        return TestUtils.getApi();
    }

    @BeforeEach
    void logTestStart(TestInfo testInfo) {
        logger.info("Running test: {}", testInfo.getDisplayName());
    }

    @Test
    void exec() {
        assertDoesNotThrow(() -> api().exec(resourceText("test/testScript.groovy")));
    }


    @Test
    @Tag("manual")
    void execLong() {
        assertDoesNotThrow(() -> api().exec(
                resourceText("test/testScriptLong.groovy"),
                150_000L
        ));
    }
}
