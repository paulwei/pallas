package com.vip.pallas.search.rampup;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.vip.pallas.search.filter.rest.RestInvokerFilter;
import com.vip.pallas.search.model.IndexRampup;
import com.vip.pallas.search.service.PallasCacheFactory;
import com.vip.pallas.search.utils.HttpClientUtil;
import com.vip.pallas.search.utils.PallasSearchProperties;
import com.vip.pallas.thread.ExtendableThreadPoolExecutor;
import com.vip.pallas.thread.PallasThreadFactory;
import com.vip.pallas.thread.TaskQueue;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.PallasHttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RampupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RampupHandler.class);

    public static CloseableHttpAsyncClient httpClient;

    private static RestInvokerFilter.IdleConnectionEvictor connEvictor = null;

    private static final ExtendableThreadPoolExecutor RAMPUP_EXECUTOR = new ExtendableThreadPoolExecutor(
            3 , 20, 2L, TimeUnit.MINUTES, new TaskQueue(
            20480), new PallasThreadFactory("pallas-index-rampup-pool", Thread.MAX_PRIORITY));

    static {
        //????????????HttpClient
        ConnectionKeepAliveStrategy keepAliveStrategy = (response, context) -> PallasSearchProperties.HTTP_SERVER_KEEPALIVE_TIMEOUT;

        ConnectingIOReactor ioReactor;
        try {
            IOReactorConfig config = IOReactorConfig.custom().setSelectInterval(40)
                    .setIoThreadCount(PallasSearchProperties.CONNECTION_IO_THREAD_NUM).build();
            ioReactor = new DefaultConnectingIOReactor(config);
        } catch (IOReactorException e) {
            LOGGER.error(e.getMessage(), e);
            throw new RuntimeException(e);// Noncompliant
        }

        PoolingNHttpClientConnectionManager cm = new PoolingNHttpClientConnectionManager(ioReactor);
        cm.setDefaultMaxPerRoute(PallasSearchProperties.CONNECTION_MAX_PER_ROUTE);
        cm.setMaxTotal(PallasSearchProperties.PALLAS_CONNECTION_MAX);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(PallasSearchProperties.HTTP_POOL_AQUIRE_TIMEOUT)
                .setConnectTimeout(PallasSearchProperties.HTTP_CONNECTION_TIMEOUT)
                .setSocketTimeout(PallasSearchProperties.HTTP_SOCKET_TIMEOUT)
                .build();

        if (httpClient == null) {
            httpClient = PallasHttpAsyncClientBuilder.create().setConnectionManager(cm).setKeepAliveStrategy(keepAliveStrategy)
                    .setDefaultRequestConfig(requestConfig).setThreadFactory(
                            new ThreadFactoryBuilder().setNameFormat("Pallas-Search-Rampup-Http-Rest-Client").build())
                    .build();
            httpClient.start();
        }

        if (connEvictor == null) {
            connEvictor = new RestInvokerFilter.IdleConnectionEvictor(cm);
            connEvictor.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));
    }

    public static void rampupIfNecessary(HttpHost targetHost, String requestUrl, DefaultFullHttpRequest outBoundRequest, HttpRequestBase httpRequest, final HttpEntity entity, String indexName, String clusterId) {
        RAMPUP_EXECUTOR.submit(() -> {
            try{
                List<IndexRampup> rampupList = PallasCacheFactory.getCacheService().getRampupByIndexNameAndCluster(indexName, clusterId);

                if(rampupList != null && !rampupList.isEmpty()){
                    //????????????????????????????????????????????????????????????????????????????????????

                    HttpEntity httpEntity = entity;

                    if(httpEntity == null){
                        httpEntity = ((HttpPost) httpRequest).getEntity();
                    }

                    for (IndexRampup rampup : rampupList) {
                        if (rampup != null) {
                            HttpRequestBase request = HttpClientUtil.getHttpUriRequest(targetHost, outBoundRequest, httpEntity);
                            request.setURI(URI.create(requestUrl.replace(indexName, rampup.getFullIndexName())));
                            HttpClientContext httpContext = HttpClientContext.create();

                            httpClient.execute(targetHost, request, httpContext, new RampupCallback(targetHost, requestUrl, clusterId, rampup));
                        }
                    }
                }
            }catch (Exception e){
                LOGGER.error("index rampup error cause by {}???targetHost: {}, requestUrl: {}, indexName: {}, clusterId: {}", e.getMessage(), targetHost, requestUrl, indexName, clusterId, e);
            }
        });
    }

    public static void shutdown() {
        if (connEvictor != null) {
            connEvictor.shutdown();
        }

        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }
}
