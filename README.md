# fullstackyang-httpclient
## 重新封装的httpclient
- 单例模式实现
```
private static class Holder {
    private static HttpClientInstance httpClientInstance = new HttpClientInstance();
}

public static HttpClientInstance instance() {
    return Holder.httpClientInstance;
}
```
- 可自定义的失败重试
```
 String tryExecute(HttpRequestBase httpRequest, HttpClientContext context, Predicate<String> predicate)
```
tryExecute方法允许传入一个Predicate函数来自定义失败重试的条件

默认情况下，statusCode不等于200时，切换Proxy进行重试

若出现连接Proxy超时，则捕获异常，继续切换Proxy进行重试

- 包装RequestConfig

每个传入的HttpRequest，对其Config进行重新包装
```
if (requestConfig != null)
    return requestConfig.getProxy() != null ? requestConfig : 
        RequestConfig.copy(requestConfig).setProxy(httpHost).build();
```

- 提供代理IP

DefaultProxyProvider类提供了代理IP，可自行实现其他获取方式