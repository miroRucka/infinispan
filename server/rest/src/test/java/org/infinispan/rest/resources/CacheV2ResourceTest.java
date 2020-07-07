package org.infinispan.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.infinispan.client.rest.configuration.Protocol.HTTP_11;
import static org.infinispan.client.rest.configuration.Protocol.HTTP_20;
import static org.infinispan.commons.api.CacheContainerAdmin.AdminFlag.VOLATILE;
import static org.infinispan.commons.dataconversion.MediaType.APPLICATION_JSON;
import static org.infinispan.commons.dataconversion.MediaType.APPLICATION_JSON_TYPE;
import static org.infinispan.commons.dataconversion.MediaType.APPLICATION_XML;
import static org.infinispan.commons.dataconversion.MediaType.APPLICATION_XML_TYPE;
import static org.infinispan.commons.dataconversion.MediaType.TEXT_PLAIN_TYPE;
import static org.infinispan.commons.test.CommonsTestingUtil.tmpDirectory;
import static org.infinispan.commons.util.Util.getResourceAsString;
import static org.infinispan.context.Flag.SKIP_CACHE_LOAD;
import static org.infinispan.context.Flag.SKIP_INDEXING;
import static org.infinispan.globalstate.GlobalConfigurationManager.CONFIG_STATE_CACHE_NAME;
import static org.infinispan.query.remote.client.ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME;
import static org.infinispan.util.concurrent.CompletionStages.join;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;

import org.infinispan.Cache;
import org.infinispan.client.rest.RestCacheClient;
import org.infinispan.client.rest.RestEntity;
import org.infinispan.client.rest.RestRawClient;
import org.infinispan.client.rest.RestResponse;
import org.infinispan.commons.configuration.JsonWriter;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.globalstate.ConfigurationStorage;
import org.infinispan.globalstate.ScopedState;
import org.infinispan.globalstate.impl.CacheState;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.dummy.DummyInMemoryStoreConfigurationBuilder;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.infinispan.rest.assertion.ResponseAssertion;
import org.testng.annotations.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Test(groups = "functional", testName = "rest.CacheV2ResourceTest")
public class CacheV2ResourceTest extends AbstractRestResourceTest {

   private static final String PERSISTENT_LOCATION = tmpDirectory(CacheV2ResourceTest.class.getName());
   private final ObjectMapper objectMapper = new ObjectMapper();

   @Override
   protected void defineCaches(EmbeddedCacheManager cm) {
      cm.defineConfiguration("default", getDefaultCacheBuilder().build());

      Cache<String, String> metadataCache = cm.getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME);
      String proto = "/* @Indexed */ message Entity { /* @Field */ required int32 value=1; }";
      metadataCache.putIfAbsent("sample.proto", proto);
      assertFalse(metadataCache.containsKey(ProtobufMetadataManagerConstants.ERRORS_KEY_SUFFIX));

      cm.defineConfiguration("indexedCache", getIndexedPersistedCache().build());
   }

   @Override
   public Object[] factory() {
      return new Object[]{
            new CacheV2ResourceTest().withSecurity(false).protocol(HTTP_11).ssl(false),
            new CacheV2ResourceTest().withSecurity(true).protocol(HTTP_20).ssl(false),
            new CacheV2ResourceTest().withSecurity(true).protocol(HTTP_11).ssl(true),
            new CacheV2ResourceTest().withSecurity(true).protocol(HTTP_20).ssl(true),
      };
   }

   private ConfigurationBuilder getIndexedPersistedCache() {
      ConfigurationBuilder builder = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, false);
      builder.indexing().enable()
            .addIndexedEntity("Entity")
            .addProperty("default.directory_provider", "local-heap")
            .statistics().enable()
            .persistence().addStore(DummyInMemoryStoreConfigurationBuilder.class).shared(true).storeName("store");
      return builder;
   }

   @Override
   protected void createCacheManagers() throws Exception {
      Util.recursiveFileRemove(PERSISTENT_LOCATION);
      super.createCacheManagers();
   }

   @Override
   protected GlobalConfigurationBuilder getGlobalConfigForNode(int id) {
      GlobalConfigurationBuilder config = super.getGlobalConfigForNode(id);
      config.globalState().enable()
            .configurationStorage(ConfigurationStorage.OVERLAY)
            .persistentLocation(Paths.get(PERSISTENT_LOCATION, Integer.toString(id)).toString());
      return config;
   }

   @Test
   public void testCacheV2KeyOps() {
      RestCacheClient cacheClient = client.cache("default");

      CompletionStage<RestResponse> response = cacheClient.post("key", "value");
      ResponseAssertion.assertThat(response).isOk();

      response = cacheClient.post("key", "value");
      ResponseAssertion.assertThat(response).isConflicted();

      response = cacheClient.put("key", "value-new");
      ResponseAssertion.assertThat(response).isOk();

      response = cacheClient.get("key");
      ResponseAssertion.assertThat(response).hasReturnedText("value-new");

      response = cacheClient.head("key");
      ResponseAssertion.assertThat(response).isOk();
      ResponseAssertion.assertThat(response).hasNoContent();

      response = cacheClient.remove("key");
      ResponseAssertion.assertThat(response).isOk();

      response = cacheClient.get("key");
      ResponseAssertion.assertThat(response).isNotFound();
   }

   @Test
   public void testCreateCacheEncodedName() throws Exception {
      testCreateAndUseCache("a/");
      testCreateAndUseCache("a/b/c");
      testCreateAndUseCache("a-b-c");
      testCreateAndUseCache("áb\\ćé/+-$");
      testCreateAndUseCache("org.infinispan.cache");
      testCreateAndUseCache("a%25bc");
   }

   @Test
   public void testCreateCacheEncoding() throws Exception {
      String cacheName = "encoding-test";
      String json = "{\"local-cache\":{\"encoding\":{\"media-type\":\"text/plain\"}}}";

      createCache(json, cacheName);
      String cacheConfig = getCacheConfig(APPLICATION_JSON_TYPE, cacheName);

      JsonNode encoding = objectMapper.readTree(cacheConfig).get("local-cache").get("encoding");
      JsonNode keyMediaType = encoding.get("key").get("media-type");
      JsonNode valueMediaType = encoding.get("value").get("media-type");

      assertEquals(TEXT_PLAIN_TYPE, keyMediaType.asText());
      assertEquals(TEXT_PLAIN_TYPE, valueMediaType.asText());
   }

   private void testCreateAndUseCache(String name) throws Exception {
      String cacheConfig = "{\"distributed-cache\":{\"mode\":\"SYNC\"}}";

      RestCacheClient cacheClient = client.cache(name);
      RestEntity config = RestEntity.create(APPLICATION_JSON, cacheConfig);
      CompletionStage<RestResponse> response = cacheClient.createWithConfiguration(config);

      ResponseAssertion.assertThat(response).isOk();

      CompletionStage<RestResponse> sizeResponse = cacheClient.size();
      ResponseAssertion.assertThat(sizeResponse).isOk();
      ResponseAssertion.assertThat(sizeResponse).containsReturnedText("0");


      RestResponse namesResponse = join(client.caches());
      ResponseAssertion.assertThat(namesResponse).isOk();
      List<String> names = Arrays.asList(objectMapper.readValue(namesResponse.getBody(), String[].class));
      assertTrue(names.contains(name));

      CompletionStage<RestResponse> putResponse = cacheClient.post("key", "value");
      ResponseAssertion.assertThat(putResponse).isOk();

      CompletionStage<RestResponse> getResponse = cacheClient.get("key");
      ResponseAssertion.assertThat(getResponse).isOk();
      ResponseAssertion.assertThat(getResponse).containsReturnedText("value");
   }

   @Test
   public void testCacheV2LifeCycle() throws Exception {
      String xml = getResourceAsString("cache.xml", getClass().getClassLoader());
      String json = getResourceAsString("cache.json", getClass().getClassLoader());

      RestEntity xmlEntity = RestEntity.create(APPLICATION_XML, xml);
      RestEntity jsonEntity = RestEntity.create(APPLICATION_JSON, json);

      CompletionStage<RestResponse> response = client.cache("cache1").createWithConfiguration(xmlEntity, VOLATILE);
      ResponseAssertion.assertThat(response).isOk();
      assertPersistence("cache1", false);

      response = client.cache("cache2").createWithConfiguration(jsonEntity);
      ResponseAssertion.assertThat(response).isOk();
      assertPersistence("cache2", true);

      String mediaList = "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
      response = client.cache("cache1").configuration(mediaList);
      ResponseAssertion.assertThat(response).isOk();
      String cache1Cfg = join(response).getBody();

      response = client.cache("cache2").configuration();
      ResponseAssertion.assertThat(response).isOk();
      String cache2Cfg = join(response).getBody();

      assertEquals(cache1Cfg, cache2Cfg);
   }

   @Test
   public void testCreateDeleteCache() throws Exception {
      String xml = getResourceAsString("cache.xml", getClass().getClassLoader());

      RestEntity xmlEntity = RestEntity.create(APPLICATION_XML, xml);

      RestCacheClient cacheClient = client.cache("cacheCRUD");

      CompletionStage<RestResponse> response = cacheClient.createWithConfiguration(xmlEntity, VOLATILE);
      ResponseAssertion.assertThat(response).isOk();

      response = cacheClient.stats();
      ResponseAssertion.assertThat(response).isOk();

      response = cacheClient.delete();
      ResponseAssertion.assertThat(response).isOk();

      response = cacheClient.stats();
      ResponseAssertion.assertThat(response).isNotFound();
   }

   private void assertPersistence(String name, boolean persisted) {
      EmbeddedCacheManager cm = cacheManagers.iterator().next();
      Cache<ScopedState, CacheState> configCache = cm.getCache(CONFIG_STATE_CACHE_NAME);
      assertEquals(persisted, configCache.entrySet()
            .stream().anyMatch(e -> e.getKey().getName().equals(name) && !e.getValue().getFlags().contains(VOLATILE)));
   }

   @Test
   public void testCacheV2Stats() throws Exception {
      String cacheJson = "{ \"distributed-cache\" : { \"statistics\":true } }";
      RestCacheClient cacheClient = client.cache("statCache");

      RestEntity jsonEntity = RestEntity.create(APPLICATION_JSON, cacheJson);
      CompletionStage<RestResponse> response = cacheClient.createWithConfiguration(jsonEntity, VOLATILE);
      ResponseAssertion.assertThat(response).isOk();

      putStringValueInCache("statCache", "key1", "data");
      putStringValueInCache("statCache", "key2", "data");

      response = cacheClient.stats();
      ResponseAssertion.assertThat(response).isOk();

      JsonNode jsonNode = objectMapper.readTree(join(response).getBody());
      assertEquals(jsonNode.get("current_number_of_entries").asInt(), 2);
      assertEquals(jsonNode.get("stores").asInt(), 2);

      response = cacheClient.clear();
      ResponseAssertion.assertThat(response).isOk();
      response = cacheClient.stats();
      ResponseAssertion.assertThat(response).isOk();
      assertEquals(objectMapper.readTree(join(response).getBody()).get("current_number_of_entries").asInt(), 0);
   }

   @Test
   public void testCacheSize() throws Exception {
      for (int i = 0; i < 100; i++) {
         putInCache("default", i, "" + i, APPLICATION_JSON_TYPE);
      }

      CompletionStage<RestResponse> response = client.cache("default").size();

      ResponseAssertion.assertThat(response).isOk();
      ResponseAssertion.assertThat(response).containsReturnedText("100");
   }

   @Test
   public void testCacheFullDetail() {
      RestResponse response = join(client.cache("default").details());
      String body = response.getBody();
      ResponseAssertion.assertThat(response).isOk();
      assertThat(body).contains("stats");
      assertThat(body).contains("size");
      assertThat(body).contains("configuration");
      assertThat(body).contains("rehash_in_progress");
      assertThat(body).contains("persistent");
      assertThat(body).contains("bounded");
      assertThat(body).contains("indexed");
      assertThat(body).contains("has_remote_backup");
      assertThat(body).contains("secured");
      assertThat(body).contains("indexing_in_progress");
      assertThat(body).contains("queryable");
   }

   public void testCacheQueryable() throws Exception {
      // Default config
      createCache(new ConfigurationBuilder(), "cacheNotQueryable");
      JsonNode details = getCacheDetail("cacheNotQueryable");
      assertFalse(details.get("queryable").asBoolean());

      // Indexed
      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.indexing().addProperty("default.directory_provider", "local-heap").enable();
      builder.indexing().enable();
      createCache(builder, "cacheIndexed");
      details = getCacheDetail("cacheIndexed");
      assertTrue(details.get("queryable").asBoolean());

      // NonIndexed
      ConfigurationBuilder proto = new ConfigurationBuilder();
      proto.encoding().mediaType(MediaType.APPLICATION_PROTOSTREAM_TYPE);
      createCache(proto, "cacheQueryable");
      details = getCacheDetail("cacheQueryable");
      assertTrue(details.get("queryable").asBoolean());
   }

   @Test
   public void testCreateInvalidCache() {
      String invalidConfig = "<infinispan>\n" +
            " <cache-container>\n" +
            "   <replicated-cache name=\"books\">\n" +
            "     <indexing>\n" +
            "       <indexed-entities>\n" +
            "         <indexed-entity>Dummy</indexed-entity>\n" +
            "        </indexed-entities>\n" +
            "     </indexing>\n" +
            "   </replicated-cache>\n" +
            " </cache-container>\n" +
            "</infinispan>";

      CompletionStage<RestResponse> response = client.cache("CACHE").createWithConfiguration(RestEntity.create(APPLICATION_XML, invalidConfig));
      ResponseAssertion.assertThat(response).isBadRequest();

      response = client.cache("CACHE").exists();
      ResponseAssertion.assertThat(response).isOk();

      CompletionStage<RestResponse> healthResponse = client.cacheManager("default").health();
      ResponseAssertion.assertThat(healthResponse).isOk();

      // The only way to recover from a broken cache is to delete it
      response = client.cache("CACHE").delete();
      ResponseAssertion.assertThat(response).isOk();

      response = client.cache("CACHE").exists();
      ResponseAssertion.assertThat(response).isNotFound();
   }

   private void createCache(ConfigurationBuilder builder, String name) {
      String json = new JsonWriter().toJSON(builder.build());
      createCache(json, name);
   }

   private void createCache(String json, String name) {
      RestEntity jsonEntity = RestEntity.create(APPLICATION_JSON, json);

      CompletionStage<RestResponse> response = client.cache(name).createWithConfiguration(jsonEntity);
      ResponseAssertion.assertThat(response).isOk();
   }

   private JsonNode getCacheDetail(String name) throws JsonProcessingException {
      RestResponse response = join(client.cache(name).details());

      ResponseAssertion.assertThat(response).isOk();

      return objectMapper.readTree(response.getBody());
   }

   @Test
   public void testCacheNames() throws Exception {
      CompletionStage<RestResponse> response = client.caches();

      ResponseAssertion.assertThat(response).isOk();

      JsonNode jsonNode = objectMapper.readTree(join(response).getBody());
      Set<String> cacheNames = cacheManagers.get(0).getCacheNames();
      assertEquals(cacheNames.size(), jsonNode.size());
      for (int i = 0; i < jsonNode.size(); i++) {
         assertTrue(cacheNames.contains(jsonNode.get(i).asText()));
      }
   }

   @Test
   public void testFlags() {
      String proto = "/* @Indexed */ message Entity { /* @Field */ required int32 value=1; }";
      registerSchema("sample.proto", proto);
      RestResponse response = insertEntity(1, 1000);
      System.out.println(response.getBody());
      ResponseAssertion.assertThat(response).isOk();
      assertIndexed(1000);

      response = insertEntity(2, 1200, SKIP_INDEXING.toString(), SKIP_CACHE_LOAD.toString());
      ResponseAssertion.assertThat(response).isOk();
      assertNotIndexed(1200);

      response = insertEntity(3, 1200, "Invalid");
      ResponseAssertion.assertThat(response).isBadRequest();
   }

   @Test
   public void testValidateCacheQueryable() throws Exception {
      registerSchema("simple.proto", "message Simple { required int32 value=1;}");
      correctReportNotQueryableCache("jsonCache", new ConfigurationBuilder().encoding().mediaType(APPLICATION_JSON_TYPE).build());
   }

   private void correctReportNotQueryableCache(String name, Configuration configuration) throws Exception {
      createAndWriteToCache(name, configuration);

      RestResponse response = queryCache(name);
      ResponseAssertion.assertThat(response).isBadRequest();

      JsonNode json = new ObjectMapper().readTree(response.getBody());
      assertTrue(json.get("error").get("cause").toString().matches(".*ISPN028015.*"));
   }

   private RestResponse queryCache(String name) {
      return join(client.cache(name).query("FROM Simple"));
   }

   private void createAndWriteToCache(String name, Configuration configuration) {
      String jsonConfig = new JsonWriter().toJSON(configuration);
      RestEntity configEntity = RestEntity.create(APPLICATION_JSON, jsonConfig);

      CompletionStage<RestResponse> response = client.cache(name).createWithConfiguration(configEntity);
      ResponseAssertion.assertThat(response).isOk();

      RestEntity valueEntity = RestEntity.create(APPLICATION_JSON, "{\"_type\":\"Simple\",\"value\":1}");

      response = client.cache(name).post("1", valueEntity);
      ResponseAssertion.assertThat(response).isOk();
   }

   @Test
   public void testGetAllKeys() throws Exception {
      RestResponse response = join(client.cache("default").keys());
      Set<?> emptyKeys = objectMapper.readValue(response.getBody(), Set.class);
      assertEquals(0, emptyKeys.size());

      putStringValueInCache("default", "1", "value");
      response = join(client.cache("default").keys());
      Set<?> singleSet = objectMapper.readValue(response.getBody(), Set.class);
      assertEquals(1, singleSet.size());

      int entries = 1000;
      for (int i = 0; i < entries; i++) {
         putStringValueInCache("default", String.valueOf(i), "value");
      }
      response = join(client.cache("default").keys());
      Set<?> keys = objectMapper.readValue(response.getBody(), Set.class);
      assertEquals(entries, keys.size());
      assertTrue(IntStream.range(0, entries).allMatch(keys::contains));
   }

   @Test
   public void testProtobufMetadataManipulation() throws Exception {
      // Special role {@link ProtobufMetadataManager#SCHEMA_MANAGER_ROLE} is needed for authz. Subject USER has it
      String cache = PROTOBUF_METADATA_CACHE_NAME;
      putStringValueInCache(cache, "file1.proto", "message A{}");
      putStringValueInCache(cache, "file2.proto", "message B{}");

      RestResponse response = join(client.cache(PROTOBUF_METADATA_CACHE_NAME).keys());
      String contentAsString = response.getBody();
      Set<?> keys = objectMapper.readValue(contentAsString, Set.class);
      assertEquals(2, keys.size());
   }

   @Test
   public void testGetProtoCacheConfig() {
      testGetProtoCacheConfig(APPLICATION_XML_TYPE);
      testGetProtoCacheConfig(APPLICATION_JSON_TYPE);
   }

   private void testGetProtoCacheConfig(String accept) {
      getCacheConfig(accept, PROTOBUF_METADATA_CACHE_NAME);
   }

   private String getCacheConfig(String accept, String name) {
      RestResponse response = join(client.cache(name).configuration(accept));
      ResponseAssertion.assertThat(response).isOk();
      return response.getBody();
   }

   @Test
   public void testJSONConversion() throws JsonProcessingException {
      RestRawClient rawClient = client.raw();

      String xml = "<infinispan>\n" +
            "    <cache-container>\n" +
            "        <distributed-cache name=\"cacheName\" mode=\"SYNC\">\n" +
            "            <memory>\n" +
            "                <object size=\"20\"/>\n" +
            "            </memory>\n" +
            "        </distributed-cache>\n" +
            "    </cache-container>\n" +
            "</infinispan>";

      CompletionStage<RestResponse> response = rawClient.post("/rest/v2/caches?action=toJSON", xml, APPLICATION_XML_TYPE);
      ResponseAssertion.assertThat(response).isOk();

      JsonNode jsonNode = objectMapper.readTree(join(response).getBody());

      JsonNode distCache = jsonNode.get("distributed-cache");
      JsonNode memory = distCache.get("memory");
      assertEquals("SYNC", distCache.get("mode").asText());
      assertEquals(20, memory.get("max-count").asInt());
   }

   @Test
   public void testCacheExists() {
      assertEquals(404, checkCache("nonexistent"));
      assertEquals(200, checkCache("invalid"));
      assertEquals(200, checkCache("default"));
      assertEquals(200, checkCache("indexedCache"));
   }

   private int checkCache(String name) {
      CompletionStage<RestResponse> response = client.cache(name).exists();
      return join(response).getStatus();
   }

   private void registerSchema(String name, String schema) {
      CompletionStage<RestResponse> response = client.schemas().put(name, schema);
      ResponseAssertion.assertThat(response).isOk();
   }

   private RestResponse insertEntity(int key, int value, String... flags) {
      String json = String.format("{\"_type\": \"Entity\",\"value\": %d}", value);
      RestEntity restEntity = RestEntity.create(APPLICATION_JSON, json);
      RestCacheClient cacheClient = client.cache("indexedCache");

      return join(cacheClient.put(String.valueOf(key), restEntity, flags));
   }

   private void assertIndexed(int value) {
      assertIndex(value, true);
   }

   private void assertNotIndexed(int value) {
      assertIndex(value, false);
   }

   private void assertIndex(int value, boolean present) {
      String query = "FROM Entity WHERE value = " + value;
      RestResponse response = join(client.cache("indexedCache").query(query));
      ResponseAssertion.assertThat(response).isOk();
      assertEquals(present, response.getBody().contains(String.valueOf(value)));
   }
}
