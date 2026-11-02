package com.xzxg.shop.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the migration infrastructure. Defaults mirror the Go backend:
 * wildcard CORS, 120 requests per minute per client, 12 MiB request bodies.
 */
@ConfigurationProperties(prefix = "shop")
public class ShopProperties {

    private final Web web = new Web();
    private final Seed seed = new Seed();
    private final Files files = new Files();
    private final Retrieval retrieval = new Retrieval();
    private final Agent agent = new Agent();
    private final Speech speech = new Speech();
    private final Nacos nacos = new Nacos();

    public Web getWeb() {
        return web;
    }

    public Seed getSeed() {
        return seed;
    }

    public Files getFiles() {
        return files;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Agent getAgent() {
        return agent;
    }

    public Speech getSpeech() {
        return speech;
    }

    public Nacos getNacos() {
        return nacos;
    }

    public static class Web {

        private String requestIdHeader = "X-Request-ID";
        private long maxRequestBodyBytes = 12L * 1024 * 1024;
        private boolean trustAllProxies = false;
        private List<String> trustedProxies = new ArrayList<>();
        private final Cors cors = new Cors();
        private final RateLimit rateLimit = new RateLimit();

        public String getRequestIdHeader() {
            return requestIdHeader;
        }

        public void setRequestIdHeader(String requestIdHeader) {
            this.requestIdHeader = requestIdHeader;
        }

        public long getMaxRequestBodyBytes() {
            return maxRequestBodyBytes;
        }

        public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }

        public boolean isTrustAllProxies() {
            return trustAllProxies;
        }

        public void setTrustAllProxies(boolean trustAllProxies) {
            this.trustAllProxies = trustAllProxies;
        }

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(List<String> trustedProxies) {
            this.trustedProxies = trustedProxies;
        }

        public Cors getCors() {
            return cors;
        }

        public RateLimit getRateLimit() {
            return rateLimit;
        }
    }

    /**
     * Mirrors {@code http.cors.allowed_origins}: empty or {@code *} means wildcard.
     */
    public static class Cors {

        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    /**
     * Mirrors the Go fixed-window limiter: 120 requests per 60 seconds.
     * The window is keyed by role:account when authenticated, otherwise by client IP.
     */
    public static class RateLimit {

        private boolean enabled = true;
        private int limit = 120;
        private long windowSeconds = 60;
        private int maxEntries = 100_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /**
     * Demo rows are part of the Go migration file but must not be written to a
     * production database by the Java service.
     */
    public static class Seed {

        private boolean demoEnabled = false;

        public boolean isDemoEnabled() {
            return demoEnabled;
        }

        public void setDemoEnabled(boolean demoEnabled) {
            this.demoEnabled = demoEnabled;
        }
    }

    public static class Files {

        private long maxUploadBytes = 10L * 1024 * 1024;
        private final Minio minio = new Minio();

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }

        public Minio getMinio() {
            return minio;
        }
    }

    public static class Minio {

        private String endpoint = "127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "xzxg-shop-assets";
        private boolean useSsl = false;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public boolean isUseSsl() {
            return useSsl;
        }

        public void setUseSsl(boolean useSsl) {
            this.useSsl = useSsl;
        }
    }

    public static class Retrieval {

        private final Vector vector = new Vector();
        private final Embedding embedding = new Embedding();
        private final ImageEmbedding imageEmbedding = new ImageEmbedding();

        public Vector getVector() {
            return vector;
        }

        public Embedding getEmbedding() {
            return embedding;
        }

        public ImageEmbedding getImageEmbedding() {
            return imageEmbedding;
        }
    }

    public static class Vector {

        private boolean enabled = false;
        private String milvusAddress = "http://127.0.0.1:19530";
        private String milvusToken = "root:Milvus";
        private String database = "";
        private String productCollection = "product_text_vectors";
        private String knowledgeCollection = "knowledge_text_chunks";
        private String imageCollection = "product_image_vectors_v2";
        private int topN = 40;
        private double minScore = 0.58;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMilvusAddress() {
            return milvusAddress;
        }

        public void setMilvusAddress(String milvusAddress) {
            this.milvusAddress = milvusAddress;
        }

        public String getMilvusToken() {
            return milvusToken;
        }

        public void setMilvusToken(String milvusToken) {
            this.milvusToken = milvusToken;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getProductCollection() {
            return productCollection;
        }

        public void setProductCollection(String productCollection) {
            this.productCollection = productCollection;
        }

        public String getKnowledgeCollection() {
            return knowledgeCollection;
        }

        public void setKnowledgeCollection(String knowledgeCollection) {
            this.knowledgeCollection = knowledgeCollection;
        }

        public String getImageCollection() {
            return imageCollection;
        }

        public void setImageCollection(String imageCollection) {
            this.imageCollection = imageCollection;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }
    }

    public static class Embedding {

        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String model = "text-embedding-v4";
        private int batchSize = 10;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class ImageEmbedding {
        private String provider = "local_histogram";
        private String baseUrl = "https://dashscope.aliyuncs.com";
        private String apiKey = "";
        private String model = "qwen3-vl-embedding";
        private int dimension = 512;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }

    public static class Agent {

        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String smallModel = "qwen3.5-flash";
        private String largeModel = "qwen3.7-plus";
        private boolean enableThinking = false;
        private double temperature = 0.3;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSmallModel() {
            return smallModel;
        }

        public void setSmallModel(String smallModel) {
            this.smallModel = smallModel;
        }

        public String getLargeModel() {
            return largeModel;
        }

        public void setLargeModel(String largeModel) {
            this.largeModel = largeModel;
        }

        public boolean isEnableThinking() {
            return enableThinking;
        }

        public void setEnableThinking(boolean enableThinking) {
            this.enableThinking = enableThinking;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public static class Speech {

        private final Realtime realtime = new Realtime();
        private final Tts tts = new Tts();

        public Realtime getRealtime() {
            return realtime;
        }

        public Tts getTts() {
            return tts;
        }
    }

    public static class Realtime {

        private String appId = "";
        private String apiKey = "";
        private String apiSecret = "";
        private String baseUrl = "wss://ws-api.xfyun.cn/v2/iat";
        private String path = "v2/iat";
        private String lang = "zh_cn";
        private String audioEncode = "raw";
        private String sampleRate = "16000";

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getLang() {
            return lang;
        }

        public void setLang(String lang) {
            this.lang = lang;
        }

        public String getAudioEncode() {
            return audioEncode;
        }

        public void setAudioEncode(String audioEncode) {
            this.audioEncode = audioEncode;
        }

        public String getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(String sampleRate) {
            this.sampleRate = sampleRate;
        }
    }

    public static class Tts {

        private String provider = "xunfei";
        private final Xunfei xunfei = new Xunfei();
        private final Doubao doubao = new Doubao();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Xunfei getXunfei() {
            return xunfei;
        }

        public Doubao getDoubao() {
            return doubao;
        }
    }

    public static class Xunfei {

        private boolean enabled = false;
        private String appId = "";
        private String apiKey = "";
        private String apiSecret = "";
        private String baseUrl = "wss://tts-api.xfyun.cn/v2/tts";
        private String voice = "xiaoyan";
        private int speed = 50;
        private int volume = 50;
        private int pitch = 50;
        private String audioEncoding = "raw";
        private String textEncoding = "UTF8";
        private int timeoutSeconds = 20;
        private int maxRunes = 800;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public int getSpeed() {
            return speed;
        }

        public void setSpeed(int speed) {
            this.speed = speed;
        }

        public int getVolume() {
            return volume;
        }

        public void setVolume(int volume) {
            this.volume = volume;
        }

        public int getPitch() {
            return pitch;
        }

        public void setPitch(int pitch) {
            this.pitch = pitch;
        }

        public String getAudioEncoding() {
            return audioEncoding;
        }

        public void setAudioEncoding(String audioEncoding) {
            this.audioEncoding = audioEncoding;
        }

        public String getTextEncoding() {
            return textEncoding;
        }

        public void setTextEncoding(String textEncoding) {
            this.textEncoding = textEncoding;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxRunes() {
            return maxRunes;
        }

        public void setMaxRunes(int maxRunes) {
            this.maxRunes = maxRunes;
        }
    }

    public static class Doubao {

        private boolean enabled = false;
        private String appId = "";
        private String apiKey = "";
        private String baseUrl = "https://openspeech.bytedance.com/api/v1/tts";
        private String cluster = "";
        private String voice = "";
        private String encoding = "mp3";
        private String uid = "xzxg-shop";
        private String textType = "plain";
        private String operation = "query";
        private int withFrontend = 1;
        private String frontendType = "text";
        private double speedRatio = 1.0;
        private double volumeRatio = 1.0;
        private double pitchRatio = 1.0;
        private int maxRunes = 800;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getCluster() {
            return cluster;
        }

        public void setCluster(String cluster) {
            this.cluster = cluster;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getTextType() {
            return textType;
        }

        public void setTextType(String textType) {
            this.textType = textType;
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public int getWithFrontend() {
            return withFrontend;
        }

        public void setWithFrontend(int withFrontend) {
            this.withFrontend = withFrontend;
        }

        public String getFrontendType() {
            return frontendType;
        }

        public void setFrontendType(String frontendType) {
            this.frontendType = frontendType;
        }

        public double getSpeedRatio() {
            return speedRatio;
        }

        public void setSpeedRatio(double speedRatio) {
            this.speedRatio = speedRatio;
        }

        public double getVolumeRatio() {
            return volumeRatio;
        }

        public void setVolumeRatio(double volumeRatio) {
            this.volumeRatio = volumeRatio;
        }

        public double getPitchRatio() {
            return pitchRatio;
        }

        public void setPitchRatio(double pitchRatio) {
            this.pitchRatio = pitchRatio;
        }

        public int getMaxRunes() {
            return maxRunes;
        }

        public void setMaxRunes(int maxRunes) {
            this.maxRunes = maxRunes;
        }
    }

    public static class Nacos {

        private boolean enabled = false;
        private String serverAddr = "http://127.0.0.1:8848";
        private String namespace = "";
        private String group = "XZXG_SHOP";
        private String dataId = "xzxg-shop-app-config.json";
        private String username = "";
        private String password = "";
        private long refreshMs = 30_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public long getRefreshMs() {
            return refreshMs;
        }

        public void setRefreshMs(long refreshMs) {
            this.refreshMs = refreshMs;
        }
    }
}
