package com.fullstackyang.httpclient;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Slf4j
public class DefaultProxyProvider {

    private static final String proxyapi;

    private static final int delay;

    static {
        Properties properties = new Properties();
        InputStream inputStream = DefaultProxyProvider.class.getClassLoader().getResourceAsStream("proxy.properties");
        try {
            properties.load(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        proxyapi = properties.getProperty("proxy.api");
        String proxydelay = properties.getProperty("proxy.delay");
        delay = StringUtils.isNumeric(proxydelay) ? Integer.parseInt(proxydelay) : 5000;
    }

    private static HttpHost proxy = getProxy();

    public static HttpHost getProxy() {
        if (Strings.isNullOrEmpty(proxyapi))
            return null;
        return getProxy(delay);
    }

    public static HttpHost getProxy(int time) {
        String str = "";
        boolean first = false;
        while (StringUtils.isEmpty(str) || !str.startsWith("{") || str.equals("{\"success\":false}")) {
            if (first) {
                try {
                    Thread.sleep(time);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            str = HttpClientInstance.getOnce(proxyapi, null);
            first = true;
        }
        JSONObject json = new JSONObject(str);
        if (json.keys().hasNext()) {
            String ip = (String) json.keys().next();
            int port = json.getInt(ip);
            return new HttpHost(ip, port);
        }
        return null;
    }


}
