package com.fullstackyang.httpclient;

import org.junit.Test;

public class TestHttpClient {

    @Test
    public void get(){
        String html = HttpClientInstance.instance().get("http://www.baidu.com");
        System.out.println(html);
    }
}
