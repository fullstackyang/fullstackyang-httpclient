package com.fullstackyang.httpclient;

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
import java.util.Objects;
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

    private CloseableHttpClient httpClient;

    @Getter
    private HttpHost httpHost;

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
        return execute(new HttpGet(url), null);
    }

    public String execute(HttpRequestBase httpRequest, HttpClientContext context) {
        return tryExecute(httpRequest, context, Objects::isNull);
    }

    public String tryExecute(HttpRequestBase httpRequest, HttpClientContext context, Predicate<String> predicate) {
        if (httpRequest.getURI() == null || "".equals(httpRequest.getURI().toString())) {
            log.warn("url is null or empty!");
            return null;
        }

        String responseContent = null;
        boolean flag = false;
        while (!flag) {
            try {
                CloseableHttpResponse response = execute(httpRequest, httpClient, context);
                HttpEntity httpEntity = response.getEntity();
                try {
                    responseContent = getResponseContent(httpEntity);
                    if (response.getStatusLine().getStatusCode() == HttpStatus.SC_ACCEPTED
                            && (predicate == null || !predicate.test(responseContent))) {
                        flag = true;
                    }
                } finally {
                    EntityUtils.consume(httpEntity);
                    response.close();
                }
            } catch (IOException e) {
                log.debug("[" + e.getMessage() + "] " + httpRequest.getURI().toString());
                flag = false;
            } finally {
                if (!flag) {
                    changeProxy();
                    if (httpRequest.getConfig().getProxy() != null)
                        httpRequest.setConfig(RequestConfig.copy(httpRequest.getConfig()).setProxy(null).build());
                }
            }
        }
        return responseContent;
    }


    private CloseableHttpResponse execute(HttpRequestBase httpRequest, CloseableHttpClient httpClient,
                                          HttpClientContext context) throws IOException {
        RequestConfig requestConfig = wrapper(httpRequest.getConfig());
        httpRequest.setConfig(requestConfig);
        return httpClient.execute(httpRequest, context);
    }

    /**
     * 在原有的RequestConfig基础上添加proxy
     *
     * @param requestConfig
     * @return 若proxy已存在，则维持原样，否则加入当前proxy
     */
    private RequestConfig wrapper(RequestConfig requestConfig) {
        if (requestConfig != null)
            return requestConfig.getProxy() != null ? requestConfig : RequestConfig.copy(requestConfig)
                    .setProxy(httpHost).build();
        else
            return RequestConfig.custom().setProxy(httpHost).setConnectionRequestTimeout(2000).
                    setConnectTimeout(1000).setSocketTimeout(4000).build();
    }


    public JSONObject getAsJSON(String url, String prefix, String surffix) {
        for (; ; ) {
            log.debug("[getAsJSON] : " + url);
            String str = execute(new HttpGet(url), null);
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
                JSONObject jsonObject = new JSONObject(json);
                return jsonObject;
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
                .connectTimeout(5000).socketTimeout(5000).execute().returnContent()
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
