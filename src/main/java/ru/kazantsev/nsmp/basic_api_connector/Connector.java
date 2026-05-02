package ru.kazantsev.nsmp.basic_api_connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import ru.kazantsev.nsmp.basic_api_connector.dto.nsmp.FileDto;
import ru.kazantsev.nsmp.basic_api_connector.dto.nsmp.ScriptChecksums;
import ru.kazantsev.nsmp.basic_api_connector.dto.nsmp.ServiceTimeExclusionDto;
import ru.kazantsev.nsmp.basic_api_connector.exception.BadResponseException;
import ru.kazantsev.nsmp.basic_api_connector.exception.RequestProcessException;
import ru.kazantsev.nsmp.basic_api_connector.exception.ResponseReadException;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

/**
 * Коннектор, имплементирующий методы базового API NSMP
 */
public class Connector {

    protected static final String ACCESS_KEY_PARAM_NAME = "accessKey";
    protected static final String BASE_REST_PATH = "/sd/services/rest";
    protected static final String BASE_SMPSYNC_PATH = "/sd/services/smpsync";

    protected static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";
    protected static final Logger logger = LoggerFactory.getLogger(Connector.class);

    protected final String scheme;
    protected final String host;
    protected String accessKey;
    protected boolean ignoringSSL;

    public Connector(ConnectorParams params) throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        this.host = params.getHost();
        this.accessKey = params.getAccessKey();
        this.scheme = params.getScheme();
        this.ignoringSSL = params.isIgnoringSSL();
        HttpClientBuilder clientBuilder = HttpClients.custom();
        if (params.isIgnoringSSL()) clientBuilder.setConnectionManager(getNoSslConnectionManager());
        this.client = clientBuilder.build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setDateFormat(new SimpleDateFormat(DATE_PATTERN));
    }

    /**
     * Клиент
     */
    protected final CloseableHttpClient client;

    /**
     * Используемый при общении маппер
     */
    protected ObjectMapper objectMapper;

    /**
     * Возвращает connection manager с отключенной проверкой сертификата и hostname verification
     *
     * @return connection manager
     */
    protected PoolingHttpClientConnectionManager getNoSslConnectionManager() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                        .setSslContext(sslContext)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .buildClassic())
                .build();
    }

    /**
     * Возвращает базовый конструктор URI
     *
     * @return базовый конструктор URI
     */
    protected URIBuilder getBasicUriBuilder() {
        return new URIBuilder().setScheme(scheme).setHost(host).addParameter(ACCESS_KEY_PARAM_NAME, accessKey);
    }

    protected URI getUri(String path) {
        return getUri(path, null);
    }

    protected URI getUri(String path, Map<String, String> params) {
        try {
            var builder = getBasicUriBuilder().setPath(path);
            if (params != null) params.forEach(builder::setParameter);
            return builder.build();
        } catch (URISyntaxException e) {
            throw new RequestProcessException(e);
        }
    }

    /**
     * Установить object mapper для чтения json
     *
     * @param mapper object mapper
     */
    @SuppressWarnings("unused")
    public void setObjectMapper(ObjectMapper mapper) {
        this.objectMapper = mapper;
    }

    /**
     * Получить хост
     *
     * @return хост
     */
    public String getHost() {
        return host;
    }

    /**
     * Собирает string entity подавляя потенциальное исключение
     *
     * @param value что будет в string entity
     * @return string entity
     */
    protected StringEntity newStringEntity(Object value) {
        try {
            return new StringEntity(objectMapper.writeValueAsString(value), ContentType.APPLICATION_JSON);
        } catch (JsonProcessingException e) {
            throw new RequestProcessException(e);
        }
    }

    /**
     * Выполнить POST
     *
     * @param request        HttpPost
     * @param method         название метода для лога
     * @param responseMapper маппер для преобразования ответа
     * @param <T>            тип возвращаемых данных
     * @return ответ, преобразованных responseMapper
     */
    protected <T> T executePost(
            HttpPost request,
            String method,
            Function<ClassicHttpResponse, T> responseMapper
    ) {
        return executePost(request, method, responseMapper, null);
    }

    /**
     * Выполнить POST
     *
     * @param request        HttpPost
     * @param method         название метода для лога
     * @param responseMapper маппер для преобразования ответа
     * @param readTimeout    read timeout
     * @param <T>            тип возвращаемых данных
     * @return ответ, преобразованных responseMapper
     */
    protected <T> T executePost(
            HttpPost request,
            String method,
            Function<ClassicHttpResponse, T> responseMapper,
            Long readTimeout
    ) {
        try {
            if (readTimeout != null) {
                RequestConfig requestConfig = RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                        .build();
                request.setConfig(requestConfig);
            }
            logger.debug("POST request \"{}\" uri: \"{}\"", method, request);
            HttpClientResponseHandler<T> handler = response -> handleResponse(method, response, responseMapper);
            return client.execute(request, handler);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Выполнить GET
     *
     * @param request        HttpGet
     * @param method         название метода для лога
     * @param responseMapper маппер для преобразования ответа
     * @param <T>            тип возвращаемых данных
     * @return ответ, преобразованных responseMapper
     */
    protected <T> T executeGet(
            HttpGet request,
            String method,
            Function<ClassicHttpResponse, T> responseMapper
    ) {
        return executeGet(request, method, responseMapper, null);
    }

    /**
     * Выполнить GET
     *
     * @param request        HttpGet
     * @param method         название метода для лога
     * @param responseMapper маппер для преобразования ответа
     * @param readTimeout    read timeout
     * @param <T>            тип возвращаемых данных
     * @return ответ, преобразованных responseMapper
     */
    protected <T> T executeGet(
            HttpGet request,
            String method,
            Function<ClassicHttpResponse, T> responseMapper,
            Long readTimeout
    ) {
        try {
            if (readTimeout != null) {
                RequestConfig requestConfig = RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout))
                        .build();
                request.setConfig(requestConfig);
            }
            logger.debug("GET request \"{}\" uri: \"{}\"", method, request);
            HttpClientResponseHandler<T> handler = response -> handleResponse(method, response, responseMapper);
            return client.execute(request, handler);
        } catch (IOException e) {
            throw new RequestProcessException(e);
        }
    }

    /**
     * Проверить ответ на код и преобразовать текст ответа
     *
     * @param method         название метода для лога
     * @param response       ответ для проверки и преобразования
     * @param responseMapper маппер для преобразования ответа
     * @param <T>            тип возвращаемых данных
     * @return ответ, преобразованных responseMapper
     */
    protected <T> T handleResponse(
            String method,
            ClassicHttpResponse response,
            Function<ClassicHttpResponse, T> responseMapper
    ) {
        var status = response.getCode();
        logger.debug("{} response status: {}", method, status);
        BadResponseException.throwIfNotOk(this, response);
        return responseMapper.apply(response);
    }

    /**
     * Прочитать ответ как строку
     *
     * @param response ответ
     * @return ответ как строка
     */
    protected String readBodyAsString(ClassicHttpResponse response) {
        try {
            return EntityUtils.toString(response.getEntity());
        } catch (IOException | ParseException e) {
            throw new ResponseReadException(e);
        }
    }

    /**
     * Прочитать ответ как массив байтов
     *
     * @param response ответ
     * @return ответ как массив байтов
     */
    protected byte[] readBodyAsBytes(ClassicHttpResponse response) {
        try {
            return EntityUtils.toByteArray(response.getEntity());
        } catch (IOException e) {
            throw new ResponseReadException(e);
        }
    }

    /**
     * Прочитать ответ как JSON
     *
     * @param response ответ
     * @param <T>      требуемый тип
     * @return ответ, десерилизованный в требуемый тип
     */
    protected <T> T readBodyAsJson(ClassicHttpResponse response) {
        try {
            return objectMapper.readValue(readBodyAsString(response), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new ResponseReadException(e);
        }
    }

    /**
     * Прочитать ответ как JSON
     *
     * @param response ответ
     * @param clazz    требуемый тип, задается явно
     * @param <T>      требуемый тип
     * @return ответ, десерилизованный в требуемый тип
     */
    protected <T> T readBodyAsJson(ClassicHttpResponse response, Class<T> clazz) {
        try {
            return objectMapper.readValue(readBodyAsString(response), clazz);
        } catch (JsonProcessingException e) {
            throw new ResponseReadException(e);
        }
    }

    /**
     * Прочитать ответ как JSON
     *
     * @param response      ответ
     * @param typeReference требуемый тип, задается как TypeReference
     * @param <T>           требуемый тип
     * @return ответ, десерилизованный в требуемый тип
     */
    @SuppressWarnings("unused")
    protected <T> T readBodyAsJson(ClassicHttpResponse response, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(readBodyAsString(response), typeReference);
        } catch (JsonProcessingException e) {
            throw new ResponseReadException(e);
        }
    }

    /**
     * Делает из Map JSON строку, которую потом можно затолкать в url
     *
     * @param map из этого будет создан JSON
     * @return JSON
     */
    protected String createJsonForUrl(HashMap<String, String> map) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("{");
        Set<Map.Entry<String, String>> entrySet = map.entrySet();
        int size = entrySet.size();
        int index = 0;
        for (Map.Entry<String, String> entry : entrySet) {
            index++;
            stringBuilder.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            if (index != size) stringBuilder.append(",");
        }
        stringBuilder.append("}");
        return stringBuilder.toString();
    }

    /**
     * Создание объекта (метод rest api 'create')
     *
     * @param metaClassCode fqn создаваемого объекта, например, serviceCall.
     * @param attributes    атрибуты создаваемого объекта.
     */
    public void create(String metaClassCode, Map<String, Object> attributes) {
        String PATH_SEGMENT = "create";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + metaClassCode;
        HttpPost httpPost = new HttpPost(getUri(path));
        httpPost.setEntity(newStringEntity(attributes));
        executePost(httpPost, PATH_SEGMENT, response -> null);
    }

    /**
     * Добавление файла к объекту (метод rest api 'add-file')
     *
     * @param targetObjectUuid идентификатор объекта, к которому будет приложен файл, например, serviceCall$1992;
     * @param file             отправляемый файл
     */
    public void addFile(String targetObjectUuid, File file) {
        addFile(targetObjectUuid, Collections.singletonList(file), null);
    }

    /**
     * Добавление файла к объекту (метод rest api 'add-file')
     *
     * @param targetObjectUuid идентификатор объекта, к которому будет приложен файл, например, serviceCall$1992;
     * @param file             отправляемый файл
     * @param attrCode         код атрибута типа "Файл". Если параметр указан, то файл добавляется в указанный атрибут, иначе файл добавляется к объекту.
     */
    @SuppressWarnings("unused")
    public void addFile(String targetObjectUuid, File file, String attrCode) {
        addFile(targetObjectUuid, Collections.singletonList(file), attrCode);
    }

    /**
     * Добавление файлов к объекту (метод rest api 'add-file')
     *
     * @param targetObjectUuid идентификатор объекта, к которому будет приложен файл, например, serviceCall$1992;
     * @param files            отправляемые файлы
     */
    public void addFile(String targetObjectUuid, List<File> files) {
        addFile(targetObjectUuid, files, null);
    }

    /**
     * Добавление файлов к объекту (метод rest api 'add-file')
     *
     * @param targetObjectUuid идентификатор объекта, к которому будет приложен файл, например, serviceCall$1992;
     * @param files            отправляемые файлы
     * @param attrCode         код атрибута типа "Файл". Если параметр указан, то файл добавляется в указанный атрибут, иначе файл добавляется к объекту.
     */
    public void addFile(String targetObjectUuid, List<File> files, String attrCode) {
        String PATH_SEGMENT = "add-file";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + targetObjectUuid;
        URI uri;
        if (attrCode == null) uri = getUri(path);
        else uri = getUri(path, Map.of("attrsCode", attrCode));
        HttpPost httpPost = new HttpPost(uri);
        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        for (int i = 0; i < files.size(); i++) {
            entityBuilder.addBinaryBody(String.valueOf(i), files.get(i));
        }
        httpPost.setEntity(entityBuilder.build());
        executePost(httpPost, PATH_SEGMENT, (ClassicHttpResponse response) -> null);
    }

    /**
     * Добавление файлов к объекту (метод rest api 'add-file')
     * contentType будет указан как plain text
     *
     * @param targetObjectUuid идентификатор объекта, к которому будет приложен файл, например, serviceCall$1992;
     * @param fileBytes        байты добавляемого файла
     * @param fileName         имя добавляемого файла
     */
    public void addFile(String targetObjectUuid, byte[] fileBytes, String fileName) {
        addFile(targetObjectUuid, fileBytes, fileName, null);
    }

    /**
     * Добавление файлов к объекту (метод rest api 'add-file')
     *
     * @param targetObjectUuid идентификатор объекта, к которому будет приложен файл, например, serviceCall$1992;
     * @param fileBytes        байты добавляемого файла
     * @param fileName         имя добавляемого файла
     * @param attrCode         код атрибута типа "Файл". Если параметр указан, то файл добавляется в указанный атрибут, иначе файл добавляется к объекту.
     */
    public void addFile(String targetObjectUuid, byte[] fileBytes, String fileName, String attrCode) {
        String PATH_SEGMENT = "add-file";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + targetObjectUuid;
        URI uri;
        if (attrCode == null) uri = getUri(path);
        else uri = getUri(path, Map.of("attrsCode", attrCode));
        HttpPost httpPost = new HttpPost(uri);
        HttpEntity entity = MultipartEntityBuilder.create()
                .addBinaryBody("file", fileBytes, ContentType.TEXT_PLAIN, fileName)
                .build();
        httpPost.setEntity(entity);
        executePost(httpPost, PATH_SEGMENT, response -> null);
    }

    /**
     * Создание исключения в указанном классе обслуживания
     *
     * @param serviceTimeUuid uuid класса обслуживания, в который нужно добавить исключение, например, servicetime$2204
     * @param exclusionDate   дата исключения
     * @return Созданный объект (исключение). Черновик редактируемого класса обслуживания, в котором создается исключение, будет автоматически подтвержден.
     */
    public ServiceTimeExclusionDto createExcl(String serviceTimeUuid, Date exclusionDate) {
        return createExcl(serviceTimeUuid, exclusionDate, null, null);
    }

    /**
     * Создание исключения в указанном классе обслуживания
     *
     * @param serviceTimeUuid uuid класса обслуживания, в который нужно добавить исключение, например, servicetime$2204
     * @param exclusionDate   дата исключения
     * @param startTime       время начала исключения (необязательно)
     * @param endTime         время окончания исключения (необязательно)
     * @return Созданный объект (исключение). Черновик редактируемого класса обслуживания, в котором создается исключение, будет автоматически подтвержден.
     */
    public ServiceTimeExclusionDto createExcl(String serviceTimeUuid, Date exclusionDate, Long startTime, Long endTime) {
        String PATH_SEGMENT = "create-excl";
        HashMap<String, String> lastSegmentMap = new HashMap<>();
        lastSegmentMap.put("exclusionDate", new SimpleDateFormat(DATE_PATTERN).format(exclusionDate));
        if (startTime != null) lastSegmentMap.put("startTime", startTime.toString());
        if (endTime != null) lastSegmentMap.put("endTime", endTime.toString());
        String lastSegmentString = createJsonForUrl(lastSegmentMap);
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + serviceTimeUuid + "/" + lastSegmentString;
        var uri = getUri(path);
        return executeGet(
                new HttpGet(uri),
                PATH_SEGMENT,
                (ClassicHttpResponse response) -> readBodyAsJson(response, ServiceTimeExclusionDto.class)
        );
    }

    /**
     * Создание объекта для машинного взаимодействия
     *
     * @param metaClassCode fqn создаваемого объекта, например, serviceCall
     * @param attributes    атрибуты создаваемого объекта
     * @return Созданный объект или только указанные атрибуты созданного объекта, если установлен returnAttrs;
     */
    public HashMap<String, Object> createM2M(String metaClassCode, Map<String, Object> attributes) {
        return createM2M(metaClassCode, attributes, null);
    }

    /**
     * Создание объекта для машинного взаимодействия
     *
     * @param metaClassCode fqn создаваемого объекта, например, serviceCall
     * @param attributes    атрибуты создаваемого объекта
     * @param returnAttrs   коды атрибутов (через запятую, без пробелов), которые необходимо вернуть в ответе. Если параметр будет пустой, то вернется весь объект.
     * @return Созданный объект или только указанные атрибуты созданного объекта, если установлен returnAttrs;
     */
    public HashMap<String, Object> createM2M(String metaClassCode, Map<String, Object> attributes, List<String> returnAttrs) {
        String PATH_SEGMENT = "create-m2m";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + metaClassCode;
        HashMap<String, String> params = new HashMap<>();
        if (returnAttrs != null) params.put("attrs", String.join(",", returnAttrs));
        HttpPost httpPost = new HttpPost(getUri(path, params));
        httpPost.setEntity(newStringEntity(attributes));
        return executePost(httpPost, PATH_SEGMENT, this::readBodyAsJson);
    }

    /**
     * Создание множества объектов для машинного взаимодействия
     *
     * @param objects лист с атрибутами создаваемых объектов
     * @return Массив объектов с UUID для созданных и переданную информацию для создания объекта с сообщением об ошибке в поле error для не созданных.
     */
    public List<HashMap<String, Object>> createM2MMultiple(List<Map<String, Object>> objects) {
        String PATH_SEGMENT = "create-m2m-multiple";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT;
        HttpPost httpPost = new HttpPost(getUri(path));
        httpPost.setEntity(newStringEntity(objects));
        return executePost(httpPost, PATH_SEGMENT, this::readBodyAsJson);
    }

    /**
     * Удаление объекта
     *
     * @param objectUuid uuid удаляемого объекта, например, serviceCall$501.
     */
    public void delete(String objectUuid) {
        String PATH_SEGMENT = "delete";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + objectUuid;
        executeGet(new HttpGet(getUri(path)), PATH_SEGMENT, response -> null);
    }

    /**
     * Редактирование объекта
     *
     * @param objectUuid uuid изменяемого объекта, например, serviceCall$501.
     * @param attributes изменяемые атрибуты.
     */
    public void edit(String objectUuid, Map<String, Object> attributes) {
        String PATH_SEGMENT = "edit";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + objectUuid;
        HttpPost httpPost = new HttpPost(getUri(path));
        httpPost.setEntity(newStringEntity(attributes));
        executePost(httpPost, PATH_SEGMENT, response -> null);
    }

    /**
     * Редактирование периода исключения для заданного исключения класса обслуживания
     *
     * @param serviceTimeExclusion uuid изменяемого объекта, например, srvTimeExcl$10502;
     * @param startTime            время начала исключения
     * @param endTime              время окончания исключения
     * @return измененный объект. Черновик редактируемого класса обслуживания, в котором создается исключение, будет автоматически подтвержден.
     */
    public ServiceTimeExclusionDto editExcl(String serviceTimeExclusion, Long startTime, Long endTime) {
        String PATH_SEGMENT = "edit-excl";
        HashMap<String, String> lastSegmentMap = new HashMap<>();
        lastSegmentMap.put("exclusionDate", serviceTimeExclusion);
        if (startTime != null) lastSegmentMap.put("startTime", startTime.toString());
        if (endTime != null) lastSegmentMap.put("endTime", endTime.toString());
        String lastSegmentString = createJsonForUrl(lastSegmentMap);
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + serviceTimeExclusion + "/" + lastSegmentString;
        return executeGet(
                new HttpGet(getUri(path)),
                PATH_SEGMENT,
                (ClassicHttpResponse response) -> readBodyAsJson(response, ServiceTimeExclusionDto.class)
        );
    }

    /**
     * Редактирование (для машинного взаимодействия)
     *
     * @param objectUuid uuid изменяемого объекта, например, srvTimeExcl$10502;
     * @param attributes изменяемые атрибуты
     * @return измененный объект или только указанные атрибуты, если установлен returnAttrs.
     */
    @SuppressWarnings("unused")
    public HashMap<String, Object> editM2M(String objectUuid, Map<String, Object> attributes) {
        return editM2M(objectUuid, attributes, null);
    }

    /**
     * Редактирование (для машинного взаимодействия)
     *
     * @param objectUuid  uuid изменяемого объекта, например, srvTimeExcl$10502;
     * @param attributes  изменяемые атрибуты
     * @param returnAttrs коды атрибутов (через запятую, без пробелов), которые необходимо вернуть в ответе. Если параметр будет пустой, то вернется весь объект
     * @return измененный объект или только указанные атрибуты, если установлен returnAttrs.
     */
    public HashMap<String, Object> editM2M(String objectUuid, Map<String, Object> attributes, List<String> returnAttrs) {
        String PATH_SEGMENT = "edit-m2m";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + objectUuid;
        Map<String, String> params = new HashMap<>();
        if (returnAttrs != null) params.put("attrs", String.join(",", returnAttrs));
        HttpPost httpPost = new HttpPost(getUri(path, params));
        httpPost.setEntity(newStringEntity(attributes));
        return executePost(httpPost, PATH_SEGMENT, this::readBodyAsJson);
    }

    /**
     * Выполнение скрипта
     *
     * @param scriptText  текст скрипта
     * @param readTimeout время ожидания ответа в мс
     * @return Результат выполнения скрипта в виде строки (без какого либо формата)
     */
    public String exec(String scriptText, Long readTimeout) {
        byte[] byteArray = scriptText.getBytes(StandardCharsets.UTF_8);
        String PATH_SEGMENT = "exec";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT;
        HttpPost httpPost = new HttpPost(getUri(path));
        HttpEntity entity = MultipartEntityBuilder.create()
                .addBinaryBody("script", byteArray, ContentType.TEXT_PLAIN, "script.groovy")
                .build();
        httpPost.setEntity(entity);
        return executePost(httpPost, PATH_SEGMENT, this::readBodyAsString, readTimeout);
    }

    /**
     * Выполнение скрипта
     *
     * @param scriptText текст скрипта
     * @return Результат выполнения скрипта в виде строки (без какого либо формата)
     */
    public String exec(String scriptText) {
        return exec(scriptText, null);
    }

    /**
     * Получение информации об объекте
     *
     * @param objectUuid uuid интересующего объекта
     * @return объект или только указанные атрибуты, если установлен returnAttrs.
     */
    public HashMap<String, Object> get(String objectUuid) {
        return get(objectUuid, null);
    }

    /**
     * Получение информации об объекте
     *
     * @param objectUuid  uuid интересующего объекта
     * @param returnAttrs коды атрибутов (через запятую, без пробелов), которые необходимо вернуть в ответе. Если параметр будет пустой, то вернется весь объект.
     * @return объект или только указанные атрибуты, если установлен returnAttrs.
     */
    public HashMap<String, Object> get(String objectUuid, List<String> returnAttrs) {
        String PATH_SEGMENT = "get";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + objectUuid;
        HashMap<String, String> params = new HashMap<>();
        if (returnAttrs != null) params.put("attrs", String.join(",", returnAttrs));
        return executeGet(
                new HttpGet(getUri(path, params)),
                PATH_SEGMENT,
                this::readBodyAsJson
        );
    }

    /**
     * Получение контента файла по его UUID
     *
     * @param fileUuid uuid файла
     * @return DTO содержащий информацию о файле
     */
    public FileDto getFile(String fileUuid) {
        String PATH_SEGMENT = "get-file";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + fileUuid;
        return executeGet(new HttpGet(getUri(path)), PATH_SEGMENT, (ClassicHttpResponse response) ->
                new FileDto(
                        readBodyAsBytes(response),
                        Optional.ofNullable(response.getFirstHeader("Content-Disposition"))
                                .map(NameValuePair::getValue)
                                .map(cd -> cd.substring(cd.indexOf('=') + 2, cd.length() - 1))
                                .orElse(null),
                        Optional.ofNullable(response.getFirstHeader("Content-Type")).map(NameValuePair::getValue).orElse(null)
                )
        );
    }

    /**
     * Поиск бизнес объектов в системе
     *
     * @param metaClassCode fqn типа (класса) объекта
     * @param searchAttrs   атрибуты и их значения, по которым осуществляется поиск
     * @return список найденных объектов
     */
    public List<HashMap<String, Object>> find(
            String metaClassCode,
            Map<String, Object> searchAttrs
    ) {
        return find(metaClassCode, searchAttrs, null, null, null);
    }

    /**
     * Поиск бизнес объектов в системе
     *
     * @param metaClassCode fqn типа (класса) объекта
     * @param searchAttrs   атрибуты и их значения, по которым осуществляется поиск
     * @param returnAttrs   коды атрибутов, которые необходимо вернуть в ответе (через запятую, без пробелов). Если параметр будет пустой, то вернется весь объект
     * @return список найденных объектов
     */
    public List<HashMap<String, Object>> find(
            String metaClassCode,
            Map<String, Object> searchAttrs,
            List<String> returnAttrs
    ) {
        return find(metaClassCode, searchAttrs, returnAttrs, null, null);
    }

    /**
     * Поиск бизнес объектов в системе
     *
     * @param metaClassCode fqn типа (класса) объекта
     * @param searchAttrs   атрибуты и их значения, по которым осуществляется поиск
     * @param returnAttrs   коды атрибутов, которые необходимо вернуть в ответе (через запятую, без пробелов). Если параметр будет пустой, то вернется весь объект
     * @param offset        количество строк (число), которые будут пропускаться перед выводом результатов запроса
     * @param limit         максимальное количество элементов для поиска (число)
     * @return список найденных объектов
     */
    public List<HashMap<String, Object>> find(
            String metaClassCode,
            Map<String, Object> searchAttrs,
            List<String> returnAttrs,
            Long offset,
            Long limit
    ) {
        String PATH_SEGMENT = "find";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT + "/" + metaClassCode;
        HashMap<String, String> params = new HashMap<>();
        if (returnAttrs != null) params.put("attrs", String.join(",", returnAttrs));
        if (offset != null) params.put("offset", offset.toString());
        if (limit != null) params.put("limit", limit.toString());
        HttpPost httpPost = new HttpPost(getUri(path, params));
        httpPost.setEntity(newStringEntity(searchAttrs));
        return executePost(httpPost, PATH_SEGMENT, this::readBodyAsJson);
    }

    /**
     * Выполнение функции модуля через POST запрос
     *
     * @param httpEntity     http сущность, содержащая body запроса
     * @param methodName     название модуля и функции, вызываемой из модуля (func=modules.moduleCode.methodName)
     * @param params         параметры функции, указанной в параметре func.
     * @param responseMapper маппер, который должен преобразовать ответ в требуемые данные
     * @return результат обработки responseMapper
     */
    @SuppressWarnings("unused")
    public <T> T execPost(
            HttpEntity httpEntity,
            String methodName,
            String params,
            Function<ClassicHttpResponse, T> responseMapper
    ) {
        return execPost(httpEntity, methodName, params, responseMapper, null);
    }

    /**
     * Выполнение функции модуля через POST запрос
     *
     * @param httpEntity          http сущность, содержащая body запроса
     * @param methodName          название модуля и функции, вызываемой из модуля (func=modules.moduleCode.methodName)
     * @param params              параметры функции, указанной в параметре func.
     * @param responseMapper      маппер, который должен преобразовать ответ в требуемые данные
     * @param additionalUrlParams дополнительные параметры url
     * @return результат обработки responseMapper
     */
    public <T> T execPost(
            HttpEntity httpEntity,
            String methodName,
            String params,
            Function<ClassicHttpResponse, T> responseMapper,
            Map<String, String> additionalUrlParams
    ) {
        String PATH_SEGMENT = "exec-post";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT;
        HashMap<String, String> urlParams = new HashMap<>();
        if (additionalUrlParams != null) urlParams.putAll(additionalUrlParams);
        urlParams.put("func", methodName);
        urlParams.put("params", params);
        urlParams.put("raw", "true");
        HttpPost httpPost = new HttpPost(getUri(path, urlParams));
        httpPost.setEntity(httpEntity);
        return executePost(httpPost, PATH_SEGMENT, responseMapper);
    }

    /**
     * Выполнение функции модуля через GET запрос
     *
     * @param methodName     название модуля и функции, вызываемой из модуля (func=modules.moduleCode.methodName)
     * @param params         параметры функции, указанной в параметре func
     * @param responseMapper маппер, который должен преобразовать ответ в требуемые данные
     * @return результат обработки responseMapper
     */
    @SuppressWarnings("unused")
    public <T> T execGet(
            String methodName,
            String params,
            Function<ClassicHttpResponse, T> responseMapper
    ) {
        return execGet(methodName, params, responseMapper, null);
    }

    /**
     * Выполнение функции модуля через GET запрос
     *
     * @param methodName          название модуля и функции, вызываемой из модуля (func=modules.moduleCode.methodName)
     * @param params              параметры функции, указанной в параметре func
     * @param responseMapper      маппер, который должен преобразовать ответ в требуемые данные
     * @param additionalUrlParams дополнительные параметры url
     * @return результат обработки responseMapper
     */
    @SuppressWarnings("unused")
    public <T> T execGet(
            String methodName,
            String params,
            Function<ClassicHttpResponse, T> responseMapper,
            Map<String, String> additionalUrlParams
    ) {
        String PATH_SEGMENT = "exec";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT;
        HashMap<String, String> urlParams = new HashMap<>();
        if (additionalUrlParams != null) urlParams.putAll(additionalUrlParams);
        urlParams.put("func", methodName);
        urlParams.put("params", params);
        urlParams.put("raw", "true");
        HttpGet httpGet = new HttpGet(getUri(path, urlParams));
        return executeGet(httpGet, PATH_SEGMENT, responseMapper);
    }

    /**
     * Получить версию приложения инсталляции
     *
     * @return строка с версией
     */
    public String version() {
        String PATH_SEGMENT = "version";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        return executeGet(new HttpGet(getUri(path)), PATH_SEGMENT, this::readBodyAsString);
    }

    /**
     * Получить версию groovy инсталляции
     *
     * @return строка с версией
     */
    public String groovyVersion() {
        String PATH_SEGMENT = "groovy_version";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        return executeGet(new HttpGet(getUri(path)), PATH_SEGMENT, this::readBodyAsString);
    }

    /**
     * Получить ip инсталляции (наверное)
     *
     * @return строка с ip
     */
    public String jpdaInfo() {
        String PATH_SEGMENT = "jpda_info";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        return executeGet(new HttpGet(getUri(path)), PATH_SEGMENT, this::readBodyAsString);
    }

    /**
     * Получить метаинформацию с инсталляции
     *
     * @param readTimeout ожидание ответа в мс
     * @return строка с xml-ником метаинформации
     */
    public String metainfo(Long readTimeout) {
        String PATH_SEGMENT = "metainfo";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        HttpGet httpGet = new HttpGet(getUri(path));
        return executeGet(httpGet, PATH_SEGMENT, this::readBodyAsString, readTimeout);
    }

    /**
     * Получить метаинформацию с инсталляции
     *
     * @return строка с xml-ником метаинформации
     */
    public String metainfo() {
        return metainfo(null);
    }

    /**
     * Загрузить метаинформацию
     *
     * @param xmlFileContent строка xml файла конфигурации
     * @param readTimeout    read timeout
     */
    @SuppressWarnings("unused")
    public void uploadMetainfo(String xmlFileContent, Long readTimeout) {
        String PATH_SEGMENT = "upload-metainfo";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        HttpPost httpPost = new HttpPost(getUri(path));
        HttpEntity entity = MultipartEntityBuilder.create()
                .addBinaryBody("metainfo", xmlFileContent.getBytes(), ContentType.APPLICATION_XML, "metainfo.xml")
                .build();
        httpPost.setEntity(entity);
        executePost(httpPost, PATH_SEGMENT, response -> null, readTimeout);
    }

    /**
     * Получение ключа для по логину и паролю.
     * Если у коннектора нет ключа - установит пришедший.
     * Если у вас есть nginx, то он по умолчанию обрезает используемые в запросе хедеры, вам нужно будет настроить параметр underscores_in_headers
     *
     * @param login    логин
     * @param password пароль
     * @param livetime срок жизни В МИНУТАХ
     * @return новый ключ
     */
    public String getAccessKey(String login, String password, Integer livetime) {
        String PATH_SEGMENT = "get-access-key";
        String path = BASE_REST_PATH + "/" + PATH_SEGMENT;
        var httpGet = new HttpGet(getUri(path, Map.of("livetime", livetime.toString())));
        httpGet.setHeader("HTTP_AUTH_LOGIN", login);
        httpGet.setHeader("HTTP_AUTH_PASSWD", password);
        var key = executeGet(httpGet, PATH_SEGMENT, this::readBodyAsString);
        if (this.accessKey == null || !this.accessKey.isEmpty()) this.accessKey = key;
        return key;
    }

    /**
     * Получить скрипты из инсталляции
     *
     * @param readTimeout время ожидания ответа от сервера
     * @return архив со скриптами
     */
    public String getScripts(Long readTimeout) {
        String PATH_SEGMENT = "scripts";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        var httpGet = new HttpGet(getUri(path));
        return executeGet(httpGet, PATH_SEGMENT, this::readBodyAsString, readTimeout);
    }

    /**
     * Получить скрипты из инсталляции
     *
     * @return архив со скриптами
     */
    public String getScripts() {
        return getScripts(null);
    }

    /**
     * Отправить скрипты на загрузку в инсталляцию
     *
     * @param archive архив со скриптами (состав - информация секретная)
     * @return ДТО с чексуммами загруженного файла
     */
    public ScriptChecksums pushScripts(byte[] archive) {
        return pushScripts(archive, null);
    }

    /**
     * Отправить скрипты на загрузку в инсталляцию
     *
     * @param archive     архив со скриптами (состав - информация секретная)
     * @param readTimeout время ожидания ответа от сервера
     * @return ДТО с чексуммами загруженного файла
     */
    public ScriptChecksums pushScripts(byte[] archive, Long readTimeout) {
        String PATH_SEGMENT = "scripts";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        HttpPost httpPost = new HttpPost(getUri(path));
        HttpEntity entity = MultipartEntityBuilder.create()
                .addBinaryBody("file", archive, ContentType.create("application/zip"), "archive.zip")
                .build();
        httpPost.setEntity(entity);
        return executePost(
                httpPost,
                PATH_SEGMENT,
                (ClassicHttpResponse response) -> readBodyAsJson(response, ScriptChecksums.class),
                readTimeout
        );
    }

    /**
     * Получить текущие чексуммы инсталляции
     *
     * @param readTimeout время ожидания ответа
     * @return чексуммы
     */
    public ScriptChecksums getScriptsStatus(Long readTimeout) {
        String PATH_SEGMENT = "scripts/status";
        String path = BASE_SMPSYNC_PATH + "/" + PATH_SEGMENT;
        var httpGet = new HttpGet(getUri(path));
        return executeGet(
                httpGet,
                PATH_SEGMENT,
                (ClassicHttpResponse response) -> readBodyAsJson(response, ScriptChecksums.class),
                readTimeout
        );
    }

    /**
     * Получить текущие чексуммы инсталляции
     *
     * @return чексуммы
     */
    public ScriptChecksums getScriptsStatus() {
        return getScriptsStatus(null);
    }
}
