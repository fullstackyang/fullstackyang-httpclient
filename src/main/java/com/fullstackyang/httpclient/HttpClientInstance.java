package com.fullstackyang.httpclient;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * <ul>
 * <li>单例模式</li>
 * <li>线程安全</li>
 * <li>含线程池</li>
 * <li>默认使用代理IP</li>
 * <li>可以使用本地IP，务必慎重</li>
 * </ul>
 *
 * @author fullstackyang
 */
@Slf4j
public class HttpClientInstance {

    public static final int CONNECTION_REQUEST_TIMEOUT = 2000;
    public static final int CONNECT_TIMEOUT = 1000;
    public static final int SOCKET_TIMEOUT = 4000;

    private CloseableHttpClient httpClient;

    @Getter
    private HttpHost httpHost;

    private ExecutorService executor = Executors.newCachedThreadPool();

    private HttpClientInstance() {
        init();
    }

    /**
     * 私有静态类实现单例模式
     */
    private static class Holder {
        private static HttpClientInstance httpClientInstance = new HttpClientInstance();
    }

    public static HttpClientInstance instance() {
        return Holder.httpClientInstance;
    }

    private Lock lock = new ReentrantLock();

    public void close() {
        lock.lock();
        try {
            if (httpClient != null)
                httpClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            httpClient = null;
            lock.unlock();
        }
    }

    public void build() {
        if (lock.tryLock()) {
            try {
                init();
            } finally {
                lock.unlock();
            }
        }
    }

    private void init() {
        this.httpHost = getProxy();
        this.httpClient = HttpClientManager.generateClient(httpHost);
    }

    private HttpHost getProxy() {
        return DefaultProxyProvider.getProxy();
    }


    /**
     * 最为常用的get方法
     *
     * @param url
     * @return
     */
    public String get(String url) {
        return get(createHttpGet(url), null);
    }

    private HttpGet createHttpGet(String url) {
        HttpGet httpget = new HttpGet(url);
        httpget.setConfig(getRequestConfig());
        httpget.setHeader(HttpHeaders.USER_AGENT, HttpClientManager.randomUserAgent());
        return httpget;
    }

    private RequestConfig getRequestConfig() {
        return RequestConfig.custom()
                .setSocketTimeout(3000)
                .setConnectTimeout(3000)
                .setConnectionRequestTimeout(3000).setProxy(httpHost)
                .build();
    }

    public String get(HttpGet httpGet, HttpClientContext context) {
        Predicate<String> predicate = s -> Strings.isNullOrEmpty(s)
                ||s.contains("ERROR: The requested URL could not be retrieved")
                ||s.contains("ERR_ACCESS_DENIED");
        return get(httpGet, context, predicate);
    }

    public String get(HttpGet httpGet, HttpClientContext context, Predicate<String> predicate) {
        if (httpGet.getURI() == null || "".equals(httpGet.getURI().toString())) {
            log.warn("url is null or empty!");
            return null;
        }

        String responseContent = null;
        boolean flag = false;
        while (!flag) {
            try {
                CloseableHttpResponse response = execute(httpGet, httpClient, context);
                HttpEntity httpEntity = response.getEntity();
                try {
                    responseContent = getResponseContent(httpEntity);
                    if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK
                            && (predicate == null || !predicate.test(responseContent))) {
                        flag = true;
                    }
                } finally {
                    EntityUtils.consume(httpEntity);
                    response.close();
                }
            } catch (IOException e) {
                log.debug("[" + e.getMessage() + "] " + httpGet.getURI().toString());
                flag = false;
            } finally {
                if (!flag) {
                    changeProxy();
                    httpGet.abort();
                    httpGet = createHttpGet(httpGet.getURI().toString());
                }
            }
        }
        return responseContent;
    }


    private CloseableHttpResponse execute(HttpRequestBase httpRequest, CloseableHttpClient httpClient,
                                          HttpClientContext context) throws IOException {
        Future<CloseableHttpResponse> future = executor.submit(() -> httpClient.execute(httpRequest, context == null
                ? HttpClientContext.create() : context));
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException(e.getMessage());
        }
    }




    public JSONObject getAsJSON(String url, String prefix, String surffix) {
        for (; ; ) {
            log.debug("[getAsJSON] : " + url);
            String str = get(new HttpGet(url), null);
            String json = str.replaceAll("&#039;|&quot;", "'").replaceAll("&amp;", "&").replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">");
            if (prefix != null && json.contains(prefix)) {
                if (surffix == null) {
                    json = json.substring(json.indexOf(prefix) + prefix.length());
                    json = json.substring(0, json.indexOf(";"));
                } else {
                    json = json.substring(json.indexOf(prefix) + prefix.length(), json.lastIndexOf(surffix));
                }
            }
            try {
                return new JSONObject(json);
            } catch (JSONException e) {
                log.warn("[getAsJSON] : " + url);
                log.warn("[getAsJSON] : " + str);
                changeProxy();
            }
        }
    }

    public InputStream getAsInputStream(String url) {
        HttpGet httpGet = new HttpGet(url);

        InputStream inputStream = null;
        try {
            CloseableHttpResponse response = execute(httpGet, httpClient, null);
            inputStream = response.getEntity().getContent();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            httpGet.abort();
        }
        return inputStream;
    }

    public void changeProxy() {
        if (lock.tryLock()) {
            try {
                this.httpHost = getProxy();
            } finally {
                lock.unlock();
            }
        }

    }

    /**
     * 本地IP直接访问，请勿使用该方法频繁访问同一个host
     *
     * @param url
     * @return
     * @throws ClientProtocolException
     * @throws IOException
     */
    public static String getOnce(String url) {
        return getOnce(url, null);
    }

    public static String getOnce(String url, HttpHost proxy) {
        String content = "";
        try {
            content = getOnce(url, proxy, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return content;
    }

    public static String getOnce(String url, HttpHost proxy, String charset)
            throws IOException {
        return Request.Get(url).useExpectContinue().viaProxy(proxy).userAgent(HttpClientManager.randomUserAgent())
                .connectTimeout(CONNECT_TIMEOUT).socketTimeout(SOCKET_TIMEOUT).execute().returnContent()
                .asString(Charset.forName(charset));
    }

    public static String getResponseContent(final HttpEntity httpEntity) throws IOException {
        if (httpEntity == null)
            return null;
        InputStream in = null;
        try {
            Header header = httpEntity.getContentEncoding();
            if (null != header && "gzip".equals(header.getValue())) {
                in = new GzipDecompressingEntity(httpEntity).getContent();
            } else {
                in = httpEntity.getContent();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(in, baos);

            Charset charset = null;
            ContentType contentType = ContentType.get(httpEntity);
            if (contentType != null) {
                charset = contentType.getCharset();
            }
            if (charset == null) {
                String content = IOUtils.toString(new ByteArrayInputStream(baos.toByteArray()),
                        Charset.defaultCharset().displayName());
                charset = getHtmlCharset(content);
                if (charset == null) {
                    return content;
                }
            }
            return IOUtils.toString(new ByteArrayInputStream(baos.toByteArray()), charset.displayName());

        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    public static Charset getHtmlCharset(final String html) {
        if (!StringUtils.isEmpty(html)) {
            Document document = Jsoup.parse(html);
            Elements links = document.select("meta");
            for (Element link : links) {
                String metaContent = link.attr("content");
                String metaCharset = link.attr("charset");
                if (metaContent.contains("charset=")) {
                    metaContent = metaContent.substring(metaContent.indexOf("charset"), metaContent.length());
                    return Charset.forName(metaContent.split("=")[1]);
                } else if (!StringUtils.isEmpty(metaCharset)) {
                    return Charset.forName(metaCharset);
                }
            }
        }
        return null;
    }
}
