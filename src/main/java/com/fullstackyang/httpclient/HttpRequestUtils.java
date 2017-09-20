package com.fullstackyang.httpclient;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.http.Consts;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicNameValuePair;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 辅助类，用于创建各种带参数，请求头等信息的http请求
 * 
 * @author fullstackyang
 *
 */
public class HttpRequestUtils {

	private static final String UTF_8 = Consts.UTF_8.name();

	public static HttpGet createHttpGet(String url, Map<String, String> headers) {
		HttpGet get = new HttpGet(url);
		if (headers != null)
			headers.forEach(get::addHeader);
		return get;
	}

	public static HttpPost createHttpPost(String url, Map<String, String> headers, Map<String, String> params) {
		 HttpPost httpPost = new HttpPost();
		try {
			httpPost.setURI(new URI(url));
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
		if (headers != null)
			headers.forEach(httpPost::addHeader);
		
		if (params != null) {
			try {
				List<BasicNameValuePair> list = Lists.newArrayList();
				params.forEach((k, v) -> list.add(new BasicNameValuePair(k, v)));
				UrlEncodedFormEntity entity = new UrlEncodedFormEntity(list, UTF_8);
				httpPost.setEntity(entity);
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
		return httpPost;
	}

	public static BasicClientCookie createCookie(String name, String value, String domain, String path) {
		BasicClientCookie cookie = new BasicClientCookie(name, value);
		cookie.setDomain(domain);
		cookie.setPath(path);
		return cookie;
	}

	public static BasicCookieStore addCookie(BasicCookieStore cookieStore, BasicClientCookie... cookies) {
		if (cookies != null && cookies.length > 0)
			Arrays.stream(cookies).forEach(cookieStore::addCookie);
		return cookieStore;
	}
}
